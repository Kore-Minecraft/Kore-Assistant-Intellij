package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElementVisitor
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.hasFunctionBody
import io.github.ayfri.kore.koreassistant.psi.macroUsage
import io.github.ayfri.kore.koreassistant.psi.namedArgument
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val MACROS_PARAMETER = "macros"

/** A macro of the `Macros` class given to `function("x", ::XMacros) { }` that its body (helpers included) never writes. */
class UnusedMacroInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				if (expression.calleeName() != KoreNames.FUNCTION.shortName().asString() || !expression.hasFunctionBody()) return
				// `::XMacros` or `{ XMacros() }` second, which no other function overload takes: a cheap gate before reading the body.
				val macros = expression.namedArgument(MACROS_PARAMETER)
					?: expression.valueArguments.getOrNull(1)?.takeUnless { it.isNamed() || it is KtLambdaArgument }?.getArgumentExpression()
				if (macros !is KtCallableReferenceExpression && macros !is KtLambdaExpression) return

				val usage = expression.macroUsage()
				if (!usage.isComplete) return
				val unused = usage.declared - usage.used
				if (unused.isEmpty()) return

				val plural = if (unused.size > 1) "s" else ""
				holder.registerProblem(macros, "Macro$plural ${unused.joinToString { "'$it'" }} never used by this function", ProblemHighlightType.LIKE_UNUSED_SYMBOL)
			}
		}
	}
}
