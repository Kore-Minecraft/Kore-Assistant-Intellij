package io.github.ayfri.kore.koreassistant.psi

import com.intellij.psi.ElementManipulators
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

private const val ARGUMENT_SUFFIX = "Argument"
private const val NAMESPACE_PARAMETER = "namespace"
private const val VANILLA_NAMESPACE = "minecraft"

/** Builders whose first parameter is a vanilla id, by the argument type they return. Every `XxxArgument(name)` invoke counts too. */
private val BUILDER_ARGUMENTS = mapOf("block" to "BlockArgument", "item" to "ItemArgument")
private val NAME_PARAMETERS = setOf("block", "item", "name")
private val WORD_BOUNDARY = Regex("(?<=.)(?=[A-Z])")

/**
 * A literal naming a vanilla registry entry: `item("diamond")`, `block("stone")`, `EntityTypeArgument("zombie")`.
 * [argumentClass] is the simple name of the argument type the registry enum implements, e.g. `ItemArgument` for `Items`.
 */
data class KoreVanillaIdArgument(val literal: KtStringTemplateExpression, val call: KtCallExpression, val argumentClass: String) {
	val id: String get() = ElementManipulators.getValueText(literal)

	/** `EntityTypeArgument` -> `entity type`, for messages. */
	val registryName get() = argumentClass.removeSuffix(ARGUMENT_SUFFIX).replace(WORD_BOUNDARY, " ").lowercase()
}

/**
 * Reads [this] literal as a vanilla id, syntactically: callers needing certainty confirm the call resolves into Kore.
 * A literal holding its own namespace, or a call passing a namespace other than `minecraft`, names no vanilla entry.
 */
fun KtStringTemplateExpression.asVanillaIdArgument(): KoreVanillaIdArgument? {
	if (hasInterpolation()) return null
	val argument = parent as? KtValueArgument ?: return null
	val call = argument.parent?.parent as? KtCallExpression ?: return null
	val callee = call.calleeName() ?: return null
	val argumentClass = BUILDER_ARGUMENTS[callee] ?: callee.takeIf { it.endsWith(ARGUMENT_SUFFIX) && it.first().isUpperCase() } ?: return null

	val isName = when (val name = argument.getArgumentName()?.asName?.asString()) {
		null -> call.positionalArgument(0) == this
		else -> name in NAME_PARAMETERS
	}
	if (!isName || ':' in ElementManipulators.getValueText(this)) return null

	val namespace = call.namedArgument(NAMESPACE_PARAMETER) ?: call.positionalArgument(1)
	if (namespace != null && namespace.koreStringValue(LocalPropertyResolver(containingFile as KtFile))?.text != VANILLA_NAMESPACE) return null

	return KoreVanillaIdArgument(this, call, argumentClass)
}
