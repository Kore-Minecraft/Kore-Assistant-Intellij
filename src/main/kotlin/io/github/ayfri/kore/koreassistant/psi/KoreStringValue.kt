package io.github.ayfri.kore.koreassistant.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

// Deep enough for `const val A = B + C` chains, low enough that a cyclic or generated file cannot stall indexing.
private const val MAX_EVALUATION_DEPTH = 8
private const val MAX_PLACEHOLDER_LENGTH = 80
private val WHITESPACE = Regex("\\s+")

/**
 * A string the plugin managed to read out of the source.
 *
 * [isDynamic] marks a value only partly known statically: the DSL is arbitrary Kotlin, so a declaration named
 * from a loop variable or a runtime call can never be computed here. [text] then keeps the source spelling of
 * the unknown parts (`blocks/$leafId`), which still identifies the declaration for the user - much better
 * than dropping it, which is what the plugin used to do.
 */
data class KoreStringValue(val text: String, val isDynamic: Boolean)

/** How a name reference is turned back into the expression it was declared with. */
fun interface KorePropertyResolver {
	fun initializerOf(reference: KtSimpleNameExpression): KtExpression?
}

/**
 * Reads [this] as a string, following constants and concatenations as far as [resolver] allows, and falling
 * back to the source spelling for the parts it cannot compute. `null` means the expression is not string-shaped
 * at all (a call, a number, a lambda), so the caller should not treat it as a declaration name.
 */
fun KtExpression.koreStringValue(resolver: KorePropertyResolver): KoreStringValue? = evaluate(resolver, MAX_EVALUATION_DEPTH)

private fun KtExpression.evaluate(resolver: KorePropertyResolver, depth: Int): KoreStringValue? = when {
	depth <= 0 -> placeholder()
	this is KtParenthesizedExpression -> expression?.evaluate(resolver, depth - 1)
	this is KtStringTemplateExpression -> evaluateTemplate(resolver, depth)
	this is KtBinaryExpression && operationToken == KtTokens.PLUS -> evaluateConcatenation(resolver, depth)
	this is KtSimpleNameExpression -> resolver.initializerOf(this)?.evaluate(resolver, depth - 1) ?: placeholder()
	// `Constants.NAMESPACE` / `Items.OAK_LEAVES`: only the selector names a property, the receiver is scoping.
	this is KtDotQualifiedExpression -> (selectorExpression as? KtSimpleNameExpression)
		?.let { resolver.initializerOf(it)?.evaluate(resolver, depth - 1) }
		?: placeholder()

	else -> null
}

private fun KtStringTemplateExpression.evaluateTemplate(resolver: KorePropertyResolver, depth: Int): KoreStringValue {
	var dynamic = false

	val text = entries.joinToString("") { entry ->
		when (entry) {
			is KtEscapeStringTemplateEntry -> entry.unescapedValue
			is KtStringTemplateEntryWithExpression -> {
				val value = entry.expression?.evaluate(resolver, depth - 1)?.takeUnless(KoreStringValue::isDynamic)
				// The entry's own text (`$leafId`) reads far better than whatever expression it stands for.
				if (value == null) entry.text.also { dynamic = true } else value.text
			}

			else -> entry.text
		}
	}

	return KoreStringValue(text, dynamic)
}

private fun KtBinaryExpression.evaluateConcatenation(resolver: KorePropertyResolver, depth: Int): KoreStringValue? {
	val leftValue = left?.evaluateOrPlaceholder(resolver, depth - 1) ?: return null
	val rightValue = right?.evaluateOrPlaceholder(resolver, depth - 1) ?: return null

	return KoreStringValue(leftValue.text + rightValue.text, leftValue.isDynamic || rightValue.isDynamic)
}

private fun KtExpression.evaluateOrPlaceholder(resolver: KorePropertyResolver, depth: Int) =
	evaluate(resolver, depth)?.takeUnless(KoreStringValue::isDynamic) ?: placeholder()

/**
 * Stands in for an expression the plugin cannot compute, showing its source spelling. The text is squeezed
 * onto one line and clipped: a name can be built by an arbitrarily long expression, a tree row cannot.
 */
fun KtExpression.koreStringPlaceholder() = placeholder()

private fun KtExpression.placeholder() =
	KoreStringValue(text.replace(WHITESPACE, " ").take(MAX_PLACEHOLDER_LENGTH), true)

/**
 * Same-file, resolution-free property lookup - the only kind an indexer may run. Locals declared before the
 * usage shadow file-level constants, which is close enough to Kotlin's own scoping for constant names.
 */
class LocalPropertyResolver(file: KtFile) : KorePropertyResolver {
	// Built once per file: a datapack file can hold hundreds of references and each miss would rescan the file.
	private val constants by lazy {
		PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)
			.filterNot(KtProperty::isLocal)
			.mapNotNull { property -> property.name?.let { it to property } }
			.toMap()
	}

	override fun initializerOf(reference: KtSimpleNameExpression): KtExpression? {
		val name = reference.getReferencedName()
		var scope: PsiElement? = reference.parent

		while (scope != null && scope !is KtFile) {
			scope.children.filterIsInstance<KtProperty>()
				.lastOrNull { it.name == name && it.textOffset < reference.textOffset }
				?.let { return it.initializer }
			scope = scope.parent
		}

		return constants[name]?.initializer
	}
}

/** Query-time resolver: follows a reference wherever it points, other files included. Needs a read action. */
data object ResolvingPropertyResolver : KorePropertyResolver {
	override fun initializerOf(reference: KtSimpleNameExpression) =
		(reference.mainReference.resolve() as? KtProperty)?.initializer
}
