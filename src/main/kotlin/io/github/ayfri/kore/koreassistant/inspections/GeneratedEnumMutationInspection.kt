package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElementVisitor
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val GENERATED_PACKAGE = "io.github.ayfri.kore.generated"
private val ENTRY_NAME = Regex("[A-Z][A-Z0-9_]*")

/**
 * `Items.DIAMOND_SWORD.components = ...` compiles because Kore's generated enums implement argument types with `var`
 * members, but an enum entry is a singleton: the assignment changes the sword everywhere in the datapack, every recipe and
 * loot table generated after it included. `Items.DIAMOND_SWORD { ... }` returns a modified copy instead.
 * Covers `=`, compound assignments and indexed sets (`Blocks.STONE.states["axis"] = "y"`).
 */
class GeneratedEnumMutationInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitBinaryExpression(expression: KtBinaryExpression) {
				if (expression.operationToken !in KtTokens.ALL_ASSIGNMENTS) return
				val left = expression.left.let { (it as? KtArrayAccessExpression)?.arrayExpression ?: it } as? KtDotQualifiedExpression ?: return
				val member = (left.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
				val entry = left.receiverExpression.entryName() ?: return
				if (!ENTRY_NAME.matches(entry.getReferencedName())) return

				analyze(entry) {
					val symbol = entry.resolveToCall()?.successfulVariableAccessCall()?.symbol as? KaEnumEntrySymbol ?: return
					if (symbol.callableId?.classId?.packageFqName?.asString() != GENERATED_PACKAGE) return
				}

				val receiver = left.receiverExpression.text
				holder.registerProblem(
					left,
					"Changing '$member' on '$receiver' changes this entry for the whole datapack, use '$receiver { ... }' to get a modified copy",
				)
			}
		}
	}
}

/** `DIAMOND_SWORD` in both `Items.DIAMOND_SWORD` and an imported `DIAMOND_SWORD`. */
private fun KtExpression.entryName(): KtNameReferenceExpression? = when (this) {
	is KtNameReferenceExpression -> this
	is KtDotQualifiedExpression -> selectorExpression as? KtNameReferenceExpression
	else -> null
}
