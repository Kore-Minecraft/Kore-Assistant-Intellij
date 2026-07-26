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
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

private const val DATA_PACK_BUILDER_NAME = "dataPack"
private const val NAMESPACE_PROPERTY_NAME = "namespace"

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

/** The callee's short name, or `null` when the callee is not a plain identifier. Purely syntactic. */
fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

/**
 * The content of a non-interpolated string literal, or `null` for anything else. Purely syntactic, safe for indexers.
 *
 * Note a plain `"foo"` already holds one [KtLiteralStringTemplateEntry], so emptiness of `entries` means the
 * empty string, not "no interpolation" - only a [KtStringTemplateEntryWithExpression] makes a template dynamic.
 */
fun KtExpression.constantStringValue(): String? {
	val template = this as? KtStringTemplateExpression ?: return null
	if (template.entries.any { it is KtStringTemplateEntryWithExpression }) return null

	return template.entries.joinToString("") { entry ->
		if (entry is KtEscapeStringTemplateEntry) entry.unescapedValue else entry.text
	}
}

/**
 * The first value argument's constant string content, or `null` if it is missing, interpolated, or not a
 * string at all. Purely syntactic - no resolution, safe for indexers.
 */
fun KtCallExpression.firstStringLiteralArgument(): String? =
	valueArguments.firstOrNull()?.getArgumentExpression()?.constantStringValue()

/** Constant string content of the argument passed by name as [parameterName]. */
fun KtCallExpression.namedStringArgument(parameterName: String): String? = valueArguments
	.firstOrNull { it.getArgumentName()?.asName?.asString() == parameterName }
	?.getArgumentExpression()
	?.constantStringValue()

/** Constant string content of the [index]-th positional argument, named arguments and lambdas excluded. */
fun KtCallExpression.positionalStringArgument(index: Int): String? = valueArguments
	.filter { it.getArgumentName() == null }
	.getOrNull(index)
	?.getArgumentExpression()
	?.constantStringValue()

/**
 * `namespace = "x"` assigned at the top level of the trailing lambda - how every generator outside the
 * function family sets its namespace (`Generator.namespace` is a `var`, not a builder parameter).
 */
fun KtCallExpression.namespaceAssignmentInBlock(): String? {
	val body = lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return null
	return body.statements.asSequence()
		.filterIsInstance<KtBinaryExpression>()
		.filter { it.operationToken == KtTokens.EQ }
		.filter { (it.left as? KtNameReferenceExpression)?.getReferencedName() == NAMESPACE_PROPERTY_NAME }
		.mapNotNull { it.right?.constantStringValue() }
		.lastOrNull()
}

/**
 * Walks up from [this] call looking for an enclosing `dataPack("name") { }` call in the same file and
 * returns its name literal. `null` if none is found (common: functions declared in a `DataPack.xxx()`
 * extension in a separate file) - callers should fall back to "any namespace" matching in that case.
 */
fun KtCallExpression.enclosingDataPackName(): String? {
	var current: PsiElement? = parent
	while (current != null) {
		if (current is KtCallExpression && current.calleeName() == DATA_PACK_BUILDER_NAME) {
			return current.firstStringLiteralArgument()
		}
		current = current.parent
	}
	return null
}
