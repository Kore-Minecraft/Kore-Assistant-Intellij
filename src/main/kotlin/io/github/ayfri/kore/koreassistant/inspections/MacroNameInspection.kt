package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.parentOfType
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

/** Kore's `Macros.MACRO_NAME_REGEX`. */
private val MACRO_NAME = Regex("[a-zA-Z0-9_]+")
private val INVALID_MACRO_CHARACTERS = Regex("[^a-zA-Z0-9_]")
private val MACRO_FUNCTION = FqName("io.github.ayfri.kore.functions.macro")
private const val MACROS_CLASS = "Macros"

/**
 * Macro names are restricted to letters, digits and underscores: `val id by "item-id"` inside a `Macros` subclass fails
 * Kore's `require` and stops the generation, `macro("item-id")` writes a `$(item-id)` the game refuses to load.
 */
class MacroNameInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitProperty(property: KtProperty) {
				val name = property.delegateExpression as? KtStringTemplateExpression ?: return
				val owner = property.parentOfType<KtClassOrObject>() ?: return
				if (owner.superTypeListEntries.none { it.typeReference?.text?.substringAfterLast('.') == MACROS_CLASS }) return
				check(name)
			}

			override fun visitCallExpression(expression: KtCallExpression) {
				if (expression.calleeName() != MACRO_FUNCTION.shortName().asString()) return
				val name = expression.positionalArgument(0) as? KtStringTemplateExpression ?: return
				analyze(expression) {
					val callable = expression.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return
					if (callable.asSingleFqName() != MACRO_FUNCTION) return
				}
				check(name)
			}

			private fun check(literal: KtStringTemplateExpression) {
				if (literal.hasInterpolation()) return
				val name = ElementManipulators.getValueText(literal)
				if (MACRO_NAME.matches(name)) return

				val fixed = name.replace(INVALID_MACRO_CHARACTERS, "_")
				val fixes = if (fixed.isEmpty()) emptyArray() else arrayOf(ChangeNameFix(fixed))
				holder.registerProblem(literal, "Macro names may only hold letters, digits and underscores", *fixes)
			}
		}
	}
}
