package io.github.ayfri.kore.koreassistant.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.inspections.koreElements
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

const val LOCATION_OPTION = "location"
const val PATH_OPTION = "path"

/**
 * Shows where each declaration ends up after its arguments: `function("helper") ⟨pack:helper⟩ { }`, or the output file
 * path when that option is on. Reads the same confirmed elements as the tool window, so datapack attribution matches it.
 */
class KoreLocationInlayHintsProvider : InlayHintsProvider {
	override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
		if (file !is KtFile || !file.project.service<KoreLibraryService>().isKoreProject) return null
		val url = file.virtualFile?.url ?: return null
		val elements = file.project.koreElements()
			.filter { it.fileUrl == url && it.kind != KoreDeclarationKind.DATA_PACK }
			.associateBy { it.offset }
		if (elements.isEmpty()) return null

		return object : SharedBypassCollector {
			override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
				val call = element as? KtCallExpression ?: return
				val koreElement = elements[call.textOffset] ?: return
				val position = InlineInlayPosition(call.valueArgumentList?.textRange?.endOffset ?: return, relatedToPrevious = true)

				sink.whenOptionEnabled(LOCATION_OPTION) {
					koreElement.resourceLocation?.let { location -> sink.addPresentation(position, hintFormat = HINT_FORMAT) { text(location) } }
				}
				sink.whenOptionEnabled(PATH_OPTION) {
					sink.addPresentation(position, hintFormat = HINT_FORMAT) { text(koreElement.outputPath) }
				}
			}
		}
	}
}

private val HINT_FORMAT = HintFormat.default
