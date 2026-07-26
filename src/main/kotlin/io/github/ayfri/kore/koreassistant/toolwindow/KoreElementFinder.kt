package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationData
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.calleeName
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

private const val KORE_PACKAGE_PREFIX = "io.github.ayfri.kore."
private const val DATA_PACK_RECEIVER_NAME = "DataPack"

/**
 * Turns [KoreDeclarationIndex] hits into [KoreElement]s. The index is syntactic, so every candidate is
 * confirmed here with `analyze { }` - one session per file rather than one per call, since resolving a
 * whole datapack's worth of declarations one session at a time is what made the old `ReferencesSearch`
 * approach slow.
 */
@OptIn(KaIdeApi::class)
internal data object KoreElementFinder {
	private val LOGGER = Logger.getInstance(KoreElementFinder::class.java)

	fun findAll(project: Project, indicator: ProgressIndicator): List<KoreElement> =
		ReadAction.nonBlocking<List<KoreElement>> { collect(project, GlobalSearchScope.projectScope(project), indicator) }
			.wrapProgress(indicator)
			.executeSynchronously()

	/** The whole search, minus the threading. Must run inside a read action; split out so tests can call it directly. */
	fun collect(project: Project, scope: GlobalSearchScope, indicator: ProgressIndicator): List<KoreElement> {
		indicator.checkCanceled()

		val declarationsByFile = KoreDeclarationIndex.findAll(project, scope).groupBy({ it.first }, { it.second })
		val psiManager = PsiManager.getInstance(project)
		val documentManager = FileDocumentManager.getInstance()

		val confirmed = declarationsByFile.flatMap { (file, declarations) ->
			indicator.checkCanceled()
			val ktFile = psiManager.findFile(file) as? KtFile ?: return@flatMap emptyList()
			confirmKoreDeclarations(ktFile, file, documentManager.getDocument(file), declarations)
		}

		val owners = resolveExtensionOwners(project, scope, confirmed, indicator)
		val soleDataPack = confirmed.filter { it.data.kind == KoreDeclarationKind.DATA_PACK }
			.map { it.data.name }
			.distinct()
			.singleOrNull()

		return confirmed.map { it.toElement(owners, soleDataPack) }
	}

	private fun confirmKoreDeclarations(
		ktFile: KtFile,
		file: VirtualFile,
		document: Document?,
		declarations: List<KoreDeclarationData>,
	): List<ConfirmedDeclaration> {
		val confirmed = mutableListOf<ConfirmedDeclaration>()

		try {
			analyze(ktFile) {
				for (declaration in declarations) {
					val call = ktFile.callExpressionAt(declaration) ?: continue
					if (!isKoreCall(call)) continue
					confirmed += ConfirmedDeclaration(declaration, file, document, call)
				}
			}
		} catch (e: Exception) {
			if (e is ProcessCanceledException) throw e
			LOGGER.warn("Error confirming Kore declarations in ${file.name}", e)
		}

		return confirmed
	}

	/** Any callable declared under Kore's root package counts - checking ~88 exact FqNames buys nothing here. */
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
		indicator: ProgressIndicator,
	): Map<KtNamedFunction, String> {
		// The inline style attaches everything syntactically, so it never pays for the walk below.
		if (confirmed.none { it.data.dataPackName == null }) return emptyMap()

		val owners = mutableMapOf<KtNamedFunction, String>()

		for (root in confirmed.filter { it.data.kind == KoreDeclarationKind.DATA_PACK }) {
			indicator.checkCanceled()
			val body = root.call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: continue
			for (extension in reachableDataPackExtensions(project, scope, body, indicator)) {
				owners.putIfAbsent(extension, root.data.name)
			}
		}

		return owners
	}

	private fun reachableDataPackExtensions(
		project: Project,
		scope: GlobalSearchScope,
		root: KtExpression,
		indicator: ProgressIndicator,
	): Set<KtNamedFunction> {
		val visited = LinkedHashSet<KtNamedFunction>()
		val frontier = ArrayDeque(listOf(root))

		while (frontier.isNotEmpty()) {
			indicator.checkCanceled()
			for (call in PsiTreeUtil.findChildrenOfType(frontier.removeFirst(), KtCallExpression::class.java)) {
				val calleeName = call.calleeName() ?: continue
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
	private val document: Document?,
	val call: KtCallExpression,
) {
	/** The outermost named function around the declaration - the unit `reachableDataPackExtensions` matches on. */
	private val container: KtNamedFunction?
		get() {
			var current: PsiElement? = call
			var outermost: KtNamedFunction? = null
			while (current != null) {
				if (current is KtNamedFunction) outermost = current
				current = current.parent
			}
			return outermost
		}

	fun toElement(owners: Map<KtNamedFunction, String>, soleDataPack: String?): KoreElement {
		val dataPack = data.dataPackName ?: owners[container] ?: soleDataPack ?: UNKNOWN_DATA_PACK

		return KoreElement(
			kind = data.kind,
			name = data.name,
			namespace = data.namespace ?: dataPack,
			dataPackName = dataPack,
			directory = data.directory,
			fileUrl = file.url,
			fileName = file.name,
			offset = data.offset,
			lineNumber = document?.lineNumberAt(data.offset) ?: -1,
		)
	}
}

/** The indexed offset can be stale after an edit, so the callee name is re-checked before trusting the hit. */
private fun KtFile.callExpressionAt(declaration: KoreDeclarationData): KtCallExpression? {
	val leaf = findElementAt(declaration.offset) ?: return null
	val call = PsiTreeUtil.getParentOfType(leaf, KtCallExpression::class.java, false) ?: return null
	return call.takeIf { it.calleeName() == declaration.kind.builderName }
}

// Matches both `fun DataPack.x()` and the fully qualified spelling, without paying for resolution per candidate.
private fun KtNamedFunction.isDataPackExtension() =
	receiverTypeReference?.text?.substringAfterLast('.') == DATA_PACK_RECEIVER_NAME

private fun Document.lineNumberAt(offset: Int) = if (offset in 0..textLength) getLineNumber(offset) + 1 else null
