package io.github.ayfri.kore.koreassistant.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.index.KoreScope
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

private const val DATA_PACK_BUILDER_NAME = "dataPack"
private const val NAMESPACE_PROPERTY_NAME = "namespace"

// The function family names its first parameter `name`, every other generator builder calls it `fileName`.
private const val NAME_PARAMETER_NAME = "name"
private const val FILE_NAME_PARAMETER_NAME = "fileName"

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

/** Resolves [call] and checks it targets the function [fqName]. Must run inside `analyze { }`. */
fun KaSession.resolvesTo(call: KtCallExpression, fqName: FqName): Boolean =
	call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId?.asSingleFqName() == fqName

/** The callee's short name, or `null` when the callee is not a plain identifier. Purely syntactic. */
fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

/** The Kore builder this call is named after, scope-aware (`noise` inside `densityFunctions { }` is not the worldgen one). */
fun KtCallExpression.koreDeclarationKind(): KoreDeclarationKind? =
	calleeName()?.let { KoreDeclarationKind.byBuilderName(it, ::activeKoreScopes) }

/**
 * The scopes this call sits in, read syntactically: every enclosing `recipes { }` call plus a `recipesBuilder`
 * property anywhere up the chain, as a receiver (`dp.recipesBuilder.smelting("x")`, `recipesBuilder.apply { }`)
 * or as an argument (`with(recipesBuilder) { }`).
 */
fun KtCallExpression.activeKoreScopes(): Set<KoreScope> {
	val scopes = mutableSetOf<KoreScope>()
	var current: PsiElement? = parent
	while (current != null) {
		when (current) {
			is KtCallExpression -> {
				current.calleeName()?.let(KoreScope::byBuilderName)?.let(scopes::add)
				current.valueArguments.forEach { it.getArgumentExpression()?.lastName()?.let(KoreScope::byReceiverName)?.let(scopes::add) }
			}

			is KtQualifiedExpression -> current.receiverExpression.lastName()?.let(KoreScope::byReceiverName)?.let(scopes::add)
		}
		current = current.parent
	}
	return scopes
}

/** `recipesBuilder` for both `recipesBuilder` and `dp.recipesBuilder`. */
private fun KtExpression.lastName(): String? = when (this) {
	is KtNameReferenceExpression -> getReferencedName()
	is KtQualifiedExpression -> (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
	else -> null
}

/** The call named [builderName] at [offset]; an indexed offset can be stale after an edit, hence the callee re-check. */
fun KtFile.koreCallAt(offset: Int, builderName: String): KtCallExpression? {
	val leaf = findElementAt(offset) ?: return null
	return PsiTreeUtil.getParentOfType(leaf, KtCallExpression::class.java, false)?.takeIf { it.calleeName() == builderName }
}

/** The argument passed by name as [parameterName], whatever expression it holds. */
fun KtCallExpression.namedArgument(parameterName: String): KtExpression? = valueArguments
	.firstOrNull { it.getArgumentName()?.asName?.asString() == parameterName }
	?.getArgumentExpression()

/** The [index]-th positional argument, named arguments and lambdas excluded - `valueArguments` holds those too. */
fun KtCallExpression.positionalArgument(index: Int): KtExpression? = valueArguments
	.filter { it.getArgumentName() == null && it.getArgumentExpression() !is KtLambdaExpression }
	.getOrNull(index)
	?.getArgumentExpression()

/**
 * The argument holding the declaration's name, by parameter name first so a reordered call still reads right,
 * then by position. Kore spells that parameter `name` in the function family and `fileName` everywhere else.
 */
fun KtCallExpression.declarationNameArgument(): KtExpression? = namedArgument(NAME_PARAMETER_NAME)
	?: namedArgument(FILE_NAME_PARAMETER_NAME)
	?: positionalArgument(0)

/**
 * `namespace = "x"` assigned at the top level of the trailing lambda - how every generator outside the
 * function family sets its namespace (`Generator.namespace` is a `var`, not a builder parameter).
 */
fun KtCallExpression.namespaceAssignmentInBlock(resolver: KorePropertyResolver): KoreStringValue? {
	val body = lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return null
	return body.statements.asSequence()
		.filterIsInstance<KtBinaryExpression>()
		.filter { it.operationToken == KtTokens.EQ }
		.filter { (it.left as? KtNameReferenceExpression)?.getReferencedName() == NAMESPACE_PROPERTY_NAME }
		.mapNotNull { it.right?.koreStringValue(resolver) }
		.lastOrNull()
}

/**
 * Walks up from [this] call looking for an enclosing `dataPack(name) { }` in the same file and returns its
 * name. `null` if none is found (common: functions declared in a `DataPack.xxx()` extension in a separate
 * file) - callers should fall back to "any namespace" matching in that case.
 */
fun KtCallExpression.enclosingDataPackName(resolver: KorePropertyResolver): KoreStringValue? {
	var current: PsiElement? = parent
	while (current != null) {
		current.enclosingDataPackCall()?.let { return it.declarationNameArgument()?.koreStringValue(resolver) }
		current = current.parent
	}
	return null
}

/**
 * The `dataPack(...)` call [this] element puts in scope: itself when it is the builder, or - for the scope
 * function layouts `dataPack("x").apply { }` and `with(dataPack("x")) { }` - the receiver/argument it is
 * applied to, which is a sibling of the block rather than one of its ancestors.
 */
private fun PsiElement.enclosingDataPackCall(): KtCallExpression? = when (this) {
	is KtCallExpression -> takeIf { it.isDataPackCall() }
		?: valueArguments.firstNotNullOfOrNull { (it.getArgumentExpression() as? KtCallExpression)?.takeIf(KtCallExpression::isDataPackCall) }

	is KtDotQualifiedExpression -> (receiverExpression as? KtCallExpression)?.takeIf(KtCallExpression::isDataPackCall)
	else -> null
}

private fun KtCallExpression.isDataPackCall() = calleeName() == DATA_PACK_BUILDER_NAME
