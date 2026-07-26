package io.github.ayfri.kore.koreassistant.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

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
@OptIn(KaIdeApi::class)
fun KaSession.resolvesTo(call: KtCallExpression, fqName: FqName, shortName: Name): Boolean {
	val functionSymbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
	return functionSymbol.callableId?.asSingleFqName() == fqName && functionSymbol.name == shortName
}
