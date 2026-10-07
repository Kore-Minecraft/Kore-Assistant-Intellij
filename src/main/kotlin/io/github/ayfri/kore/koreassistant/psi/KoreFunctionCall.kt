package io.github.ayfri.kore.koreassistant.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

private const val FUNCTION_CALLEE = "function"
private const val NAME_PARAMETER = "name"
private const val NAMESPACE_PARAMETER = "namespace"
private const val GROUP_PARAMETER = "group"
private const val BLOCK_PARAMETER = "block"

/**
 * What a `/function` command (`function("x")`, `function("ns", "x")`, `function("ns:x")`) targets, read from its name literal.
 * [namespace] is `null` when only resolution could tell (a `fun DataPack.xxx()` layout, a constant from another file).
 * [pathRange] is the `x` part inside the literal, after any `ns:` prefix.
 */
data class KoreFunctionCommand(val literal: KtStringTemplateExpression, val namespace: String?, val path: String, val group: Boolean, val pathRange: TextRange) {
	/** The last `/` segment, which is the declared name: `utils/helper` is `function("helper", directory = "utils")`. */
	val name get() = path.substringAfterLast('/')

	val location get() = "${if (group) "#" else ""}${namespace ?: "?"}:$path"
}

/**
 * Reads [this] literal as the name of a `/function` command, without resolution so it is safe on the EDT and in dumb-free
 * contexts like references. The name-only overload keeps an already namespaced name (`"other:helper"`) as is, else uses the
 * datapack's name.
 *
 * The overload is told apart by the arguments' shapes: the name-only one takes a `Boolean` second, so a string-shaped
 * second argument means `(namespace, name)`.
 */
fun KtStringTemplateExpression.asFunctionCommandName(): KoreFunctionCommand? {
	if (hasInterpolation()) return null
	val argument = parent as? KtValueArgument ?: return null
	val call = argument.parent?.parent as? KtCallExpression ?: return null
	if (call.calleeName() != FUNCTION_CALLEE || call.hasFunctionBody()) return null

	val positional = call.valueArguments.filter { it.getArgumentName() == null && it.getArgumentExpression() !is KtLambdaExpression }
	val position = positional.indexOf(argument)
	val namespaceFirst = when (argument.getArgumentName()?.asName?.asString()) {
		NAME_PARAMETER -> call.namedArgument(NAMESPACE_PARAMETER) != null || positional.isNotEmpty()
		null -> when (position) {
			// A string-shaped second argument makes this first one the namespace, which names no function by itself.
			0 -> if (positional.getOrNull(1)?.getArgumentExpression().isStringShaped()) return null else false
			1 -> true
			else -> return null
		}

		else -> return null
	}

	val group = (call.namedArgument(GROUP_PARAMETER) ?: positional.getOrNull(if (namespaceFirst) 2 else 1)?.getArgumentExpression())?.text == "true"
	val valueRange = ElementManipulators.getValueTextRange(this)
	val value = valueRange.substring(text)
	val resolver = LocalPropertyResolver(containingFile as KtFile)

	val separator = if (namespaceFirst) -1 else value.indexOf(':')
	val namespace = when {
		separator >= 0 -> value.substring(0, separator).removePrefix("#")
		namespaceFirst -> (call.namedArgument(NAMESPACE_PARAMETER) ?: positional.firstOrNull()?.getArgumentExpression())?.staticString(resolver)
		else -> call.enclosingDataPackName(resolver)?.takeUnless(KoreStringValue::isDynamic)?.text
	}

	val path = value.substring(separator + 1)
	val pathStart = valueRange.startOffset + separator + 1
	return KoreFunctionCommand(this, namespace, path, group, TextRange(pathStart, pathStart + path.length))
}

/** The kind of the function or function tag [this] literal names, when it is the name argument of such a declaration. */
fun KtStringTemplateExpression.functionDeclarationKind(): KoreDeclarationKind? {
	val call = (parent as? KtValueArgument)?.parent?.parent as? KtCallExpression ?: return null
	val kind = call.koreDeclarationKind()?.takeIf { it.isFunction || it == KoreDeclarationKind.FUNCTION_TAG } ?: return null
	return kind.takeIf { call.declarationNameArgument() == this && (!it.isFunction || call.hasFunctionBody()) }
}

/** Every function declaration takes its body while the `/function` command sharing its callee name never does: no resolution needed. */
fun KtCallExpression.hasFunctionBody() = lambdaArguments.isNotEmpty() || namedArgument(BLOCK_PARAMETER) != null

private fun KtExpression?.isStringShaped() = this is KtStringTemplateExpression || this?.koreStringValue(LocalPropertyResolver(containingFile as KtFile))?.isDynamic == false

private fun KtExpression.staticString(resolver: KorePropertyResolver) = koreStringValue(resolver)?.takeUnless(KoreStringValue::isDynamic)?.text
