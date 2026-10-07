package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElementVisitor
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.namedArgument
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val MATHS_PACKAGE = "io.github.ayfri.kore.arguments.maths"
private val TRIPLE_BUILDERS = setOf("coordinate", "vec3")
private const val POS_BUILDER = "pos"
private const val TYPE_PARAMETER = "type"

private enum class CoordinateType { LOCAL, RELATIVE, WORLD }

/** How each spelling Kore offers sets a component's type: `1.localPos`, `2.pos.relative`, `PosNumber.Type.LOCAL`, `pos(3)` (relative)... */
private val TYPE_BY_NAME = mapOf(
	"LOCAL" to CoordinateType.LOCAL, "local" to CoordinateType.LOCAL, "localPos" to CoordinateType.LOCAL,
	"RELATIVE" to CoordinateType.RELATIVE, "relative" to CoordinateType.RELATIVE, "relativePos" to CoordinateType.RELATIVE,
	"WORLD" to CoordinateType.WORLD, "world" to CoordinateType.WORLD, "worldPos" to CoordinateType.WORLD, "pos" to CoordinateType.WORLD,
)

/**
 * Minecraft only accepts `^` local coordinates all together (`^ ^1 ^`): `vec3(1.localPos, 2.pos, 0.relativePos)` renders
 * `^1 2 ~` and the command fails to parse when the function loads, taking the whole function down with it. Components whose
 * type is not written out (a variable, a computed value) make the call unknown, so it is skipped.
 */
class MixedCoordinatesInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				if (expression.calleeName() !in TRIPLE_BUILDERS || expression.valueArguments.size != 3) return
				val types = (0..2).map { expression.positionalArgument(it)?.coordinateType() ?: return }
				if (CoordinateType.LOCAL !in types || types.all { it == CoordinateType.LOCAL }) return

				analyze(expression) {
					val callable = expression.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return
					if (callable.packageName != FqName(MATHS_PACKAGE)) return
				}

				holder.registerProblem(expression, "Local '^' coordinates cannot be mixed with relative '~' or absolute ones, Minecraft rejects this position")
			}
		}
	}
}

private fun KtExpression.coordinateType(): CoordinateType? = when (this) {
	is KtConstantExpression -> CoordinateType.WORLD
	is KtPrefixExpression -> if (operationToken == KtTokens.MINUS) baseExpression?.coordinateType() else null
	is KtParenthesizedExpression -> expression?.coordinateType()
	is KtNameReferenceExpression -> TYPE_BY_NAME[getReferencedName()]?.takeIf { getReferencedName().first().isUpperCase() }
	is KtDotQualifiedExpression -> (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()?.let(TYPE_BY_NAME::get)
	// `pos(value)` defaults to relative, unlike the `.pos` property.
	is KtCallExpression -> when {
		calleeName() != POS_BUILDER -> null
		valueArguments.size <= 1 -> CoordinateType.RELATIVE
		else -> (namedArgument(TYPE_PARAMETER) ?: positionalArgument(1))?.coordinateType()
	}

	else -> null
}
