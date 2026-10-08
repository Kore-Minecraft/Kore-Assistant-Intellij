package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.index.koreDeclarationData
import io.github.ayfri.kore.koreassistant.inspections.KoreProjectElements
import io.github.ayfri.kore.koreassistant.psi.ResolvingPropertyResolver
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import io.github.ayfri.kore.koreassistant.toolwindow.KoreDataPackNode
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElement
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElementNode
import io.github.ayfri.kore.koreassistant.toolwindow.UNKNOWN_DATA_PACK
import io.github.ayfri.kore.koreassistant.toolwindow.toKoreElement
import io.github.ayfri.kore.koreassistant.toolwindow.tooltip
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.Icon

/**
 * Shared line marker logic for Kore declarations. The syntactic gate ([declarationAt]) and the cached Kore-project check
 * run first so the Analysis API ([confirms]) is only invoked on plausible candidates in projects that actually depend on Kore.
 *
 * The hover is the tool window's card, built when shown rather than with the marker: by then the inspections have usually
 * refreshed the project's elements, whose datapack attribution follows `fun DataPack.xxx()` extensions. Until they have, it
 * shows what the call itself spells, read here where resolution is allowed.
 */
abstract class KoreCallGutterProvider : LineMarkerProviderDescriptor() {
	final override fun getName() = this::class.simpleName

	/** The declaration call [element] is the callee identifier of, with its kind, read without resolution. */
	protected abstract fun declarationAt(element: PsiElement): Pair<KtCallExpression, KoreDeclarationKind>?

	protected abstract fun KaSession.confirms(call: KtCallExpression, kind: KoreDeclarationKind): Boolean

	final override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
		val (call, kind) = declarationAt(element) ?: return null
		val project = element.project
		if (!project.service<KoreLibraryService>().isKoreProject) return null

		val data = analyze(call) {
			if (!confirms(call, kind)) return null
			call.koreDeclarationData(kind, ResolvingPropertyResolver)
		} ?: return null

		val file = call.containingFile
		val virtualFile = file.virtualFile ?: return null
		val lineNumber = PsiDocumentManager.getInstance(project).getDocument(file)?.getLineNumber(data.offset)?.plus(1) ?: -1
		val written = data.toKoreElement(data.dataPackName ?: UNKNOWN_DATA_PACK, virtualFile.url, virtualFile.name, lineNumber)

		return createMarker(element, call, kind.icon, "${kind.displayName} '${data.name}'") { hoverCard(project, written) }
	}

	protected open fun createMarker(element: PsiElement, call: KtCallExpression, icon: Icon, accessibleName: String, tooltip: () -> String): LineMarkerInfo<*> =
		LineMarkerInfo(element, element.textRange, icon, { tooltip() }, null, GutterIconRenderer.Alignment.CENTER) { accessibleName }
}

/** A datapack answers over everything it holds, like its tool window row; any other declaration shows its own card. */
private fun hoverCard(project: Project, written: KoreElement): String {
	val known = project.service<KoreProjectElements>().upToDateOrNull
	val element = known?.firstOrNull { it.fileUrl == written.fileUrl && it.offset == written.offset } ?: written

	if (known == null || element.kind != KoreDeclarationKind.DATA_PACK) return KoreElementNode(element).tooltip()
	return KoreDataPackNode(element.name, known.filter { it.dataPackName == element.name && it.kind != KoreDeclarationKind.DATA_PACK }).tooltip()
}
