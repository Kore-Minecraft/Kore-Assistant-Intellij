package io.github.ayfri.kore.koreassistant.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

private const val DATA_PACK_BUILDER_NAME = "dataPack"

/**
 * Cheap syntactic gate: `element` is the callee identifier of a call named [shortName].
 * No resolution happens here, safe to call from dumb-mode contexts like line marker providers.
 */
fun PsiElement.asCalleeOf(shortName: Name): KtCallExpression? {
	if (this !is LeafPsiElement || elementType != KtTokens.IDENTIFIER) return null

	val nameReferenceExpr = parent as? KtNameReferenceExpression ?: return null
	val callExpression = nameReferenceExpr.parent as? KtCallExpression ?: return null
	if (callExpression.calleeExpression !== nameReferenceExpr) return null
	if (nameReferenceExpr.getReferencedNameAsName() != shortName) return null

	return callExpression
}

/** Resolves [this] call and checks it targets the function identified by [fqName] / [shortName]. Must run inside `analyze { }`. */
fun KaSession.resolvesTo(call: KtCallExpression, fqName: FqName, shortName: Name): Boolean {
	val functionSymbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
	return functionSymbol.callableId?.asSingleFqName() == fqName && functionSymbol.name == shortName
}

/**
 * The first value argument's constant string content, or `null` if it is missing, interpolated, or not a
 * string at all. Purely syntactic - no resolution, safe for indexers.
 */
fun KtCallExpression.firstStringLiteralArgument(): String? {
	val argument = valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression ?: return null
	if (argument.entries.isNotEmpty()) return null
	return argument.text?.removeSurrounding("\"\"\"")?.removeSurrounding("\"")
}

/**
 * Walks up from [this] call looking for an enclosing `dataPack("name") { }` call in the same file and
 * returns its name literal. `null` if none is found (common: functions declared in a `DataPack.xxx()`
 * extension in a separate file) - callers should fall back to "any namespace" matching in that case.
 */
fun KtCallExpression.namespaceHint(): String? {
	var current: PsiElement? = parent
	while (current != null) {
		if (current is KtCallExpression) {
			val calleeName = (current.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
			if (calleeName == DATA_PACK_BUILDER_NAME) return current.firstStringLiteralArgument()
		}
		current = current.parent
	}
	return null
}
