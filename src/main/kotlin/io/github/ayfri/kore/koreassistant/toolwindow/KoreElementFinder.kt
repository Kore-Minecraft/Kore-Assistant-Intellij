package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationData
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.index.koreDeclarationData
import io.github.ayfri.kore.koreassistant.psi.TrackingPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.koreCallAt
import io.github.ayfri.kore.koreassistant.psi.resolvesToKoreDeclaration
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.modification.createProjectWideSourceModificationTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference

private const val DATA_PACK_RECEIVER_NAME = "DataPack"

private val LOGGER = Logger.getInstance(KoreElementFinder::class.java)

/**
 * Turns [KoreDeclarationIndex] hits into [KoreElement]s. The index is syntactic, so every candidate is
 * confirmed here with `analyze { }` - one session per file rather than one per call.
 *
 * Both costly parts are cached per file, so an edit only redoes the file it touched: the confirmed declarations of a
 * file, and the datapack-taking functions each body calls. They depend on their own file and on Kotlin's out-of-block
 * modifications (a declaration added, a signature changed anywhere), never on in-body edits elsewhere.
 */
@OptIn(KaIdeApi::class)
internal data object KoreElementFinder {
	/** Must run inside a smart-mode read action on a background thread; the caller owns the threading so tests can supply their own. */
	fun collect(project: Project): List<KoreElement> {
		val psiManager = PsiManager.getInstance(project)
		val confirmed = KoreDeclarationIndex.findAll(GlobalSearchScope.projectScope(project)).keys.flatMap { file ->
			ProgressManager.checkCanceled()
			(psiManager.findFile(file) as? KtFile)?.confirmedDeclarations().orEmpty()
		}

		val owners = resolveExtensionOwners(confirmed)
		val soleDataPack = confirmed.filter { it.data.kind == KoreDeclarationKind.DATA_PACK }
			.map { it.data.name }
			.distinct()
			.singleOrNull()

		return confirmed.map { it.toElement(owners, soleDataPack) }
	}

	@OptIn(KaPlatformInterface::class)
	private fun KtFile.confirmedDeclarations(): List<ConfirmedDeclaration> = CachedValuesManager.getCachedValue(this) {
		// Constants read from other files are dependencies too: an in-body edit there is not an out-of-block one.
		val resolver = TrackingPropertyResolver()
		CachedValueProvider.Result(
			confirmKoreDeclarations(resolver),
			this, project.createProjectWideSourceModificationTracker(), *resolver.files.toTypedArray(),
		)
	}

	private fun KtFile.confirmKoreDeclarations(resolver: TrackingPropertyResolver): List<ConfirmedDeclaration> {
		val file = virtualFile ?: return emptyList()
		val confirmed = mutableListOf<ConfirmedDeclaration>()
		val document = PsiDocumentManager.getInstance(project).getDocument(this)

		try {
			analyze(this) {
				for (declaration in KoreDeclarationIndex.inFile(file, project)) {
					val call = koreCallAt(declaration.offset, declaration.kind.builderName) ?: continue
					if (!resolvesToKoreDeclaration(call, declaration.kind)) continue
					// Re-read the call now that references resolve: the indexer could not follow a constant out of its file.
					val data = call.koreDeclarationData(declaration.kind, resolver) ?: continue
					val lineNumber = document?.takeIf { data.offset <= it.textLength }?.getLineNumber(data.offset)?.plus(1) ?: -1
					confirmed += ConfirmedDeclaration(data, file.url, file.name, lineNumber, call)
				}
			}
		} catch (e: ProcessCanceledException) {
			throw e
		} catch (e: Exception) {
			LOGGER.warn("Error confirming Kore declarations in ${file.name}", e)
		}

		return confirmed
	}

	/**
	 * Kore's recommended layout puts resources in `fun DataPack.xxx()` extensions, which have no enclosing
	 * `dataPack("name") { }` to read a name from. Walking out from each datapack block into the extensions it
	 * calls (transitively) recovers the owner; anything still unattached falls back to the project's only
	 * datapack, if there is exactly one.
	 *
	 * Callees are matched by name, so an extension is often reachable from several datapacks: it goes to the one reaching
	 * it in the fewest calls, the one declared in its own file on a tie, then the first in source order.
	 */
	private fun resolveExtensionOwners(confirmed: List<ConfirmedDeclaration>): Map<KtNamedFunction, String> {
		// The inline style attaches everything syntactically, so it never pays for the walk below.
		if (confirmed.none { it.data.dataPackName == null }) return emptyMap()

		val owners = HashMap<KtNamedFunction, String>()
		var level = confirmed.filter { it.data.kind == KoreDeclarationKind.DATA_PACK }
			.sortedWith(compareBy({ it.fileUrl }, { it.data.offset }))
			.mapNotNull { root -> root.call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression?.let { Reach(it, root) } }

		while (level.isNotEmpty()) {
			ProgressManager.checkCanceled()
			val claims = LinkedHashMap<KtNamedFunction, Reach>()
			for (reach in level) for (candidate in reach.body.calledDataPackFunctions()) {
				if (candidate in owners) continue
				val claim = claims[candidate]
				if (claim == null || !claim.isFrom(candidate) && reach.isFrom(candidate)) claims[candidate] = reach
			}
			claims.forEach { (extension, reach) -> owners[extension] = reach.root.data.name }
			level = claims.mapNotNull { (extension, reach) -> extension.bodyExpression?.let { Reach(it, reach.root) } }
		}

		return owners
	}

	/** A body the walk got to from [root]. */
	private class Reach(val body: KtExpression, val root: ConfirmedDeclaration) {
		fun isFrom(extension: KtNamedFunction) = root.call.containingFile == extension.containingFile
	}

	/** The project functions taking a `DataPack` that [this] body may call, matched by callee name. */
	@OptIn(KaPlatformInterface::class)
	private fun KtExpression.calledDataPackFunctions(): List<KtNamedFunction> = CachedValuesManager.getCachedValue(this) {
		// A datapack body repeats the same callees (`function`, `say`, ...) hundreds of times; each name is looked up once.
		val names = PsiTreeUtil.findChildrenOfType(this, KtCallExpression::class.java).mapNotNullTo(HashSet()) { it.calleeName() }
		val scope = GlobalSearchScope.projectScope(project)
		val candidates = names.flatMap { KotlinFunctionShortNameIndex[it, project, scope] }.filter { it.isDataPackExtension() }
		CachedValueProvider.Result(candidates, containingFile, project.createProjectWideSourceModificationTracker())
	}
}

/** A declaration that resolved into Kore, still holding the PSI the datapack-owner walk needs. */
private class ConfirmedDeclaration(
	val data: KoreDeclarationData,
	val fileUrl: String,
	private val fileName: String,
	private val lineNumber: Int,
	val call: KtCallExpression,
) {
	fun toElement(owners: Map<KtNamedFunction, String>, soleDataPack: String?): KoreElement {
		// The outermost named function around the declaration is the unit the owner walk matches on.
		val container = PsiTreeUtil.getTopmostParentOfType(call, KtNamedFunction::class.java)
		return data.toKoreElement(data.dataPackName ?: owners[container] ?: soleDataPack ?: UNKNOWN_DATA_PACK, fileUrl, fileName, lineNumber)
	}
}

/**
 * Every way a function can take the datapack it fills: as a receiver (what Kore recommends), as a plain
 * parameter, or as a Kotlin 2.4 context parameter, which `oop` and `helpers` use throughout. Matched on the
 * type's spelling, fully qualified or not, so no candidate has to be resolved.
 */
private fun KtNamedFunction.isDataPackExtension(): Boolean {
	if (receiverTypeReference.namesDataPack()) return true
	if (valueParameters.any { it.typeReference.namesDataPack() }) return true
	return contextReceivers.any { it.typeReference().namesDataPack() }
}

private fun KtTypeReference?.namesDataPack() = this?.typeElement?.text?.substringAfterLast('.') == DATA_PACK_RECEIVER_NAME
