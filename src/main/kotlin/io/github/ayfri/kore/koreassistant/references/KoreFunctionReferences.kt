package io.github.ayfri.kore.koreassistant.references

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.KoreFunctionCommand
import io.github.ayfri.kore.koreassistant.psi.asFunctionCommandName
import io.github.ayfri.kore.koreassistant.psi.functionDeclarationKind
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/** Minecraft resource location path characters; `/` is excluded since a rename only changes the last segment. */
private val FUNCTION_NAME = Regex("[a-z0-9_.-]+")
private val KORE_FUNCTION_CALL = UsageType { "Kore function call" }

/**
 * Turns the name literal of `function("x")` commands into references to the declared function, and the name literal of
 * the declaration itself into a reference to its own target, which is what Find Usages and Rename start from there.
 * Resolution goes through the declaration index and syntax only: references resolve on the EDT, where the Analysis API refuses to run.
 */
class KoreFunctionReferenceContributor : PsiReferenceContributor() {
	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		registrar.registerReferenceProvider(psiElement(KtStringTemplateExpression::class.java).withParent(KtValueArgument::class.java), object : PsiReferenceProvider() {
			override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
				val literal = element as KtStringTemplateExpression
				if (!literal.project.service<KoreLibraryService>().isKoreProject) return PsiReference.EMPTY_ARRAY

				literal.asFunctionCommandName()?.let { return arrayOf(KoreFunctionReference(it)) }
				literal.functionDeclarationKind()?.takeUnless { literal.hasInterpolation() }?.let { return arrayOf(KoreFunctionDeclarationReference(literal, it)) }
				return PsiReference.EMPTY_ARRAY
			}
		})
	}
}

/** `function("utils/helper")` inside a function body: resolves to every declaration it can mean, so ambiguous layouts still navigate. */
class KoreFunctionReference(val command: KoreFunctionCommand) :
	PsiPolyVariantReferenceBase<KtStringTemplateExpression>(command.literal, ElementManipulators.getValueTextRange(command.literal), true) {
	override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
		ResolveCache.getInstance(element.project).resolveWithCaching(this, Resolver, false, incompleteCode)

	override fun isReferenceTo(element: PsiElement) = element is KoreFunctionTarget && element.name == command.name && super.isReferenceTo(element)

	override fun handleElementRename(newElementName: String): PsiElement {
		// Only the declared name changes: an `ns:` prefix and a directory are separate parts of the declaration.
		val nameStart = command.pathRange.startOffset + command.path.lastIndexOf('/') + 1
		return ElementManipulators.handleContentChange(element, TextRange(nameStart, command.pathRange.endOffset), newElementName)
	}

	override fun getVariants(): Array<Any> = emptyArray()

	private object Resolver : ResolveCache.PolyVariantResolver<KoreFunctionReference> {
		override fun resolve(reference: KoreFunctionReference, incompleteCode: Boolean): Array<ResolveResult> {
			val project = reference.element.project
			if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
			val command = reference.command
			return KoreFunctionTarget.find(project, command.namespace, command.path, command.group).map(::PsiElementResolveResult).toTypedArray()
		}
	}
}

/** The declaration's own name: never a usage of itself, only the handle Find Usages and Rename pick the target up from. */
class KoreFunctionDeclarationReference(literal: KtStringTemplateExpression, private val kind: KoreDeclarationKind) :
	PsiReferenceBase<KtStringTemplateExpression>(literal, ElementManipulators.getValueTextRange(literal), true) {
	override fun resolve() = KoreFunctionTarget(element, kind == KoreDeclarationKind.FUNCTION_TAG)

	override fun isReferenceTo(element: PsiElement) = false
}

/** Usages of a function are string literals, which the word index only scans for when asked to look inside strings. */
class KoreFunctionReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
	override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
		val target = queryParameters.elementToSearch as? KoreFunctionTarget ?: return
		queryParameters.optimizer.searchWord(target.name, queryParameters.effectiveSearchScope, UsageSearchContext.IN_STRINGS, true, target)
	}
}

/** Kotlin's own handler only knows Kotlin declarations; the default handler is enough once the searcher above exists. */
class KoreFunctionFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
	override fun canFindUsages(element: PsiElement) = element is KoreFunctionTarget

	override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean) = object : FindUsagesHandler(element) {}
}

/** Kotlin's identifier rules would reject `my-function` and accept `MyFunction`; Minecraft's are the other way round. */
class KoreFunctionRenameInputValidator : RenameInputValidator {
	override fun getPattern() = psiElement(KoreFunctionTarget::class.java)

	override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext) = FUNCTION_NAME.matches(newName)
}

class KoreFunctionUsageTypeProvider : UsageTypeProvider {
	override fun getUsageType(element: PsiElement) = KORE_FUNCTION_CALL.takeIf { (element as? KtStringTemplateExpression)?.asFunctionCommandName() != null }
}
