package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElementVisitor
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.KoreStringValue
import io.github.ayfri.kore.koreassistant.psi.ResolvingPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.koreStringValue
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import io.github.ayfri.kore.koreassistant.psi.resolvesTo
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val MAX_GRID_SIZE = 3
private const val PATTERN_BUILDER_NAME = "pattern"
private const val PATTERN_LINE_BUILDER_NAME = "patternLine"
private const val KEY_BUILDER_NAME = "key"
private const val KEYS_BUILDER_NAME = "keys"
private const val KEYS_PAIR_OPERATOR = "to"

/**
 * Checks a `craftingShaped("x") { pattern(...); key(...) }` block statically: grid size, uniform row width, every
 * pattern character mapped by a `key(...)` / `keys { "X" to ... }` entry, and no key left unused. Minecraft rejects the
 * first three at load time and drops the recipe, Kore writes them all without complaint.
 */
class CraftingShapedInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				if (expression.calleeName() != KoreNames.CRAFTING_SHAPED.shortName().asString()) return
				val body = expression.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return
				analyze(expression) { if (!resolvesTo(expression, KoreNames.CRAFTING_SHAPED)) return }

				val rows = mutableListOf<Pair<KtExpression, KoreStringValue>>()
				val keys = mutableListOf<Pair<KtExpression, KoreStringValue>>()
				for (statement in body.statements) {
					val call = statement as? KtCallExpression ?: continue
					when (call.calleeName()) {
						PATTERN_BUILDER_NAME -> call.valueArguments.mapNotNullTo(rows) { it.getArgumentExpression()?.withValue() }
						PATTERN_LINE_BUILDER_NAME -> call.positionalArgument(0)?.withValue()?.let(rows::add)
						KEY_BUILDER_NAME -> call.positionalArgument(0)?.withValue()?.let(keys::add)
						KEYS_BUILDER_NAME -> call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression?.statements
							?.filterIsInstance<KtBinaryExpression>()
							?.filter { it.operationReference.getReferencedName() == KEYS_PAIR_OPERATOR }
							?.mapNotNullTo(keys) { it.left?.withValue() }
					}
				}

				checkGrid(holder, rows)
				if (rows.none { it.second.isDynamic } && keys.none { it.second.isDynamic }) checkKeys(holder, rows, keys)
			}
		}
	}

	private fun checkGrid(holder: ProblemsHolder, rows: List<Pair<KtExpression, KoreStringValue>>) {
		val width = rows.firstOrNull()?.second?.takeUnless(KoreStringValue::isDynamic)?.text?.length

		rows.forEachIndexed { index, (expression, row) ->
			when {
				index >= MAX_GRID_SIZE -> holder.registerProblem(expression, "A shaped recipe has at most $MAX_GRID_SIZE rows")
				row.isDynamic -> {}
				row.text.length > MAX_GRID_SIZE -> holder.registerProblem(expression, "A pattern row has at most $MAX_GRID_SIZE characters")
				row.text.isEmpty() -> holder.registerProblem(expression, "A pattern row cannot be empty")
				width != null && row.text.length != width -> holder.registerProblem(expression, "Every row must be as wide as the first one ($width)")
			}
		}
	}

	private fun checkKeys(holder: ProblemsHolder, rows: List<Pair<KtExpression, KoreStringValue>>, keys: List<Pair<KtExpression, KoreStringValue>>) {
		val declaredKeys = keys.map { it.second.text }.toSet()
		val usedChars = rows.flatMap { it.second.text.toList() }.filter { it != ' ' }.map(Char::toString).toSet()

		for ((expression, row) in rows) {
			val missing = row.text.filter { it != ' ' }.map(Char::toString).distinct().filterNot(declaredKeys::contains)
			if (missing.isNotEmpty()) holder.registerProblem(expression, "No key defined for ${missing.joinToString { "'$it'" }}")
		}

		for ((expression, key) in keys) {
			when {
				key.text.length != 1 -> holder.registerProblem(expression, "A key is a single character")
				key.text == " " -> holder.registerProblem(expression, "A space is an empty slot and cannot be a key")
				rows.isNotEmpty() && key.text !in usedChars ->
					holder.registerProblem(expression, "Key '${key.text}' is not used in the pattern", ProblemHighlightType.WEAK_WARNING)
			}
		}
	}
}

private fun KtExpression.withValue(): Pair<KtExpression, KoreStringValue>? = koreStringValue(ResolvingPropertyResolver)?.let { this to it }
