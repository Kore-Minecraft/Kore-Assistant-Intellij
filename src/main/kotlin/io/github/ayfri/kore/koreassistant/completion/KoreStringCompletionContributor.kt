package io.github.ayfri.kore.koreassistant.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import com.intellij.util.ThreeState
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.KoreFunctionCommand
import io.github.ayfri.kore.koreassistant.psi.asFunctionCommandName
import io.github.ayfri.kore.koreassistant.psi.asVanillaIdArgument
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import io.github.ayfri.kore.koreassistant.services.KoreRegistryService
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Completes inside Kore id strings: the project's function names in `function("<caret>")`, vanilla ids from the user's own
 * Kore in `item("<caret>")`, `block("<caret>")` and every `XxxArgument("<caret>")`. Purely syntactic: the lists come from
 * the declaration index and [KoreRegistryService], and a wrong guess only costs an irrelevant popup.
 */
class KoreStringCompletionContributor : CompletionContributor() {
	override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
		if (parameters.completionType != CompletionType.BASIC) return
		val literal = parameters.position.koreLiteral() ?: return
		val project = literal.project
		if (!project.service<KoreLibraryService>().isKoreProject) return

		// Ids hold `/`, `.` and `:`, so the prefix is the whole value before the caret, not the identifier tail the default matcher takes.
		val valueStart = literal.textRange.startOffset + ElementManipulators.getValueTextRange(literal).startOffset
		val ids = result.withPrefixMatcher(literal.containingFile.text.substring(valueStart, parameters.offset))

		literal.asFunctionCommandName()?.let {
			ids.addFunctionNames(project, it)
			// The platform offers file names in every string, never the right answer here.
			return result.stopHere()
		}

		val vanilla = literal.asVanillaIdArgument() ?: return
		val module = ModuleUtilCore.findModuleForPsiElement(parameters.originalFile) ?: return
		val registry = project.service<KoreRegistryService>().ids(module, vanilla.argumentClass).ifEmpty { return }
		val typeText = vanilla.registryName
		registry.forEach { ids.addElement(LookupElementBuilder.create(it).withTypeText(typeText)) }
		result.stopHere()
	}

	private fun CompletionResultSet.addFunctionNames(project: Project, command: KoreFunctionCommand) {
		val wanted = if (command.group) setOf(KoreDeclarationKind.FUNCTION_TAG) else KoreDeclarationKind.entries.filter { it.isFunction }.toSet()
		val declarations = KoreDeclarationIndex.findAll(GlobalSearchScope.projectScope(project)).values.flatten()

		declarations.filter { it.kind in wanted && !it.isDynamic }.map { data ->
			val namespace = data.effectiveNamespace
			// Another namespace only resolves spelled out: the name-only overload targets the current datapack.
			val lookup = if (namespace == null || namespace == command.namespace) data.path else "$namespace:${data.path}"
			LookupElementBuilder.create(lookup).withIcon(data.kind.icon).withTypeText(namespace)
		}.distinctBy { it.lookupString }.forEach(::addElement)
	}
}

/** Kotlin never auto-pops completion inside a string; Kore id strings are the exception, since they always hold a known id. */
class KoreStringCompletionConfidence : CompletionConfidence() {
	override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
		val literal = contextElement.koreLiteral() ?: return ThreeState.UNSURE
		if (!psiFile.project.service<KoreLibraryService>().isKoreProject) return ThreeState.UNSURE
		return if (literal.asFunctionCommandName() != null || literal.asVanillaIdArgument() != null) ThreeState.NO else ThreeState.UNSURE
	}
}

/** The string literal [this] leaf is part of: its content, or a quote of an empty one, but nothing inside a `${}` entry. */
private fun PsiElement.koreLiteral() = parentOfType<KtStringTemplateExpression>()?.takeIf { it == parent || it == parent?.parent }
