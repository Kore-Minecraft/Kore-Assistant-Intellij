package io.github.ayfri.kore.koreassistant.templates

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.impl.TemplateContext
import com.intellij.codeInsight.template.impl.TemplateOptionalProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

/** A call spelled with its full Kore package, every segment lowercase, so `io.github.ayfri.kore.generated.Items.X` never matches. */
private val KORE_QUALIFIED_CALL = Regex("""io\.github\.ayfri\.kore(?:\.[a-z][a-zA-Z0-9]*)+(?=\s*[({])""")

/**
 * Kotlin cannot resolve a package-qualified extension call that relies on an implicit receiver (`io.github.ayfri.kore.functions.load { }`
 * inside `dataPack { }`), so its FqName shortener leaves those as is. Most Kore builders are such extensions: this rewrites them to
 * their short name plus an import, leaving everything else (`Items.DIAMOND`, `allEntities()`) to the regular shortener.
 */
class KoreTemplateImportProcessor : TemplateOptionalProcessor {
	override fun processText(project: Project, template: Template, document: Document, templateRange: RangeMarker, editor: Editor) {
		val documentManager = PsiDocumentManager.getInstance(project)
		val calls = KORE_QUALIFIED_CALL.findAll(document.getText(templateRange.textRange)).toList()
		if (calls.isEmpty()) return

		for (call in calls.asReversed()) {
			val start = templateRange.startOffset + call.range.first
			document.replaceString(start, start + call.value.length, call.value.substringAfterLast('.'))
		}
		documentManager.commitDocument(document)

		val file = documentManager.getPsiFile(document) as? KtFile ?: return
		val importList = file.importList ?: return
		val imported = file.importDirectives.mapNotNull { it.importPath?.pathStr }.toSet()
		val factory = KtPsiFactory(project)
		calls.map { it.value }.distinct().filterNot(imported::contains).forEach {
			importList.add(factory.createImportDirective(ImportPath(FqName(it), false)))
		}
		documentManager.doPostponedOperationsAndUnblockDocument(document)
	}

	override fun getOptionName() = "Import Kore builders"

	override fun isEnabled(template: Template) = true

	// Always on, so there is no checkbox to show in the template editor.
	override fun isVisible(template: Template, context: TemplateContext) = false
}
