package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationData
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.index.koreDeclarationData
import io.github.ayfri.kore.koreassistant.psi.ResolvingPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.koreCallAt
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference

private const val KORE_PACKAGE_PREFIX = "io.github.ayfri.kore."
private const val DATA_PACK_RECEIVER_NAME = "DataPack"

private val LOGGER = Logger.getInstance(KoreElementFinder::class.java)

/**
 * Turns [KoreDeclarationIndex] hits into [KoreElement]s. The index is syntactic, so every candidate is
 * confirmed here with `analyze { }` - one session per file rather than one per call.
 */
@OptIn(KaIdeApi::class)
internal data object KoreElementFinder {
	/** Must run inside a smart-mode read action on a background thread; the caller owns the threading so tests can supply their own. */
	fun collect(project: Project, scope: GlobalSearchScope): List<KoreElement> {
		val psiManager = PsiManager.getInstance(project)
		val confirmed = KoreDeclarationIndex.findAll(scope).flatMap { (file, declarations) ->
			ProgressManager.checkCanceled()
			val ktFile = psiManager.findFile(file) as? KtFile ?: return@flatMap emptyList()
			confirmKoreDeclarations(ktFile, file, declarations)
		}

		val owners = resolveExtensionOwners(project, scope, confirmed)
		val soleDataPack = confirmed.filter { it.data.kind == KoreDeclarationKind.DATA_PACK }
			.map { it.data.name }
			.distinct()
			.singleOrNull()

		return confirmed.map { it.toElement(owners, soleDataPack) }
	}

	private fun confirmKoreDeclarations(
		ktFile: KtFile,
		file: VirtualFile,
		declarations: List<KoreDeclarationData>,
	): List<ConfirmedDeclaration> {
		val confirmed = mutableListOf<ConfirmedDeclaration>()
		val document = FileDocumentManager.getInstance().getDocument(file)

		try {
			analyze(ktFile) {
				for (declaration in declarations) {
					val call = ktFile.koreCallAt(declaration.offset, declaration.kind.builderName) ?: continue
					if (!isKoreCall(call)) continue
					// Re-read the call now that references resolve: the indexer could not follow a constant out of its file.
					val data = call.koreDeclarationData(declaration.kind, ResolvingPropertyResolver) ?: continue
					val lineNumber = document?.takeIf { data.offset <= it.textLength }?.getLineNumber(data.offset)?.plus(1) ?: -1
					confirmed += ConfirmedDeclaration(data, file, lineNumber, call)
				}
			}
		} catch (e: ProcessCanceledException) {
			throw e
		} catch (e: Exception) {
			LOGGER.warn("Error confirming Kore declarations in ${file.name}", e)
		}

		return confirmed
	}

	/** Any callable declared under Kore's root package counts - checking ~230 exact FqNames buys nothing here. */
	private fun KaSession.isKoreCall(call: KtCallExpression): Boolean {
		val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		return symbol.callableId?.asSingleFqName()?.asString()?.startsWith(KORE_PACKAGE_PREFIX) == true
	}

	/**
	 * Kore's recommended layout puts resources in `fun DataPack.xxx()` extensions, which have no enclosing
	 * `dataPack("name") { }` to read a name from. Walking out from each datapack block into the extensions it
	 * calls (transitively) recovers the owner; anything still unattached falls back to the project's only
	 * datapack, if there is exactly one.
	 */
	private fun resolveExtensionOwners(
		project: Project,
		scope: GlobalSearchScope,
		confirmed: List<ConfirmedDeclaration>,
	): Map<KtNamedFunction, String> {
		// The inline style attaches everything syntactically, so it never pays for the walk below.
		if (confirmed.none { it.data.dataPackName == null }) return emptyMap()

		val owners = mutableMapOf<KtNamedFunction, String>()

		for (root in confirmed.filter { it.data.kind == KoreDeclarationKind.DATA_PACK }) {
			ProgressManager.checkCanceled()
			val body = root.call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: continue
			for (extension in reachableDataPackExtensions(project, scope, body)) {
				owners.putIfAbsent(extension, root.data.name)
			}
		}

		return owners
	}

	private fun reachableDataPackExtensions(project: Project, scope: GlobalSearchScope, root: KtExpression): Set<KtNamedFunction> {
		val visited = LinkedHashSet<KtNamedFunction>()
		// A datapack body repeats the same callees (`function`, `say`, ...) hundreds of times; each name is looked up once.
		val queriedNames = HashSet<String>()
		val frontier = ArrayDeque(listOf(root))

		while (frontier.isNotEmpty()) {
			ProgressManager.checkCanceled()
			for (call in PsiTreeUtil.findChildrenOfType(frontier.removeFirst(), KtCallExpression::class.java)) {
				val calleeName = call.calleeName()?.takeIf(queriedNames::add) ?: continue
				for (candidate in KotlinFunctionShortNameIndex[calleeName, project, scope]) {
					if (!candidate.isDataPackExtension() || !visited.add(candidate)) continue
					candidate.bodyExpression?.let(frontier::addLast)
				}
			}
		}

		return visited
	}
}

/** A declaration that resolved into Kore, still holding the PSI the datapack-owner walk needs. */
private class ConfirmedDeclaration(
	val data: KoreDeclarationData,
	private val file: VirtualFile,
	private val lineNumber: Int,
	val call: KtCallExpression,
) {
	fun toElement(owners: Map<KtNamedFunction, String>, soleDataPack: String?): KoreElement {
		// The outermost named function around the declaration is the unit `reachableDataPackExtensions` matches on.
		val container = PsiTreeUtil.getTopmostParentOfType(call, KtNamedFunction::class.java)
		val dataPack = data.dataPackName ?: owners[container] ?: soleDataPack ?: UNKNOWN_DATA_PACK

		return KoreElement(
			kind = data.kind,
			name = data.name,
			namespace = data.namespace ?: dataPack,
			dataPackName = dataPack,
			directory = data.directory,
			isDynamic = data.isDynamic,
			fileUrl = file.url,
			fileName = file.name,
			offset = data.offset,
			lineNumber = lineNumber,
		)
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
