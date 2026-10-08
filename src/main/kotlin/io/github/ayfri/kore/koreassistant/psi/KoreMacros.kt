package io.github.ayfri.kore.koreassistant.psi

import com.intellij.psi.ElementManipulators
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

private val MACRO_FUNCTION = FqName("io.github.ayfri.kore.functions.macro")
private val EVAL_FUNCTION = FqName("io.github.ayfri.kore.functions.eval")
private const val KORE_PACKAGE_PREFIX = "io.github.ayfri.kore."
private const val MACROS_PARAMETER = "macros"

/** Kore's `Macros.MACRO_REGEX`, which `Function.addLine` also matches to turn a hand-written line into a macro line. */
private val MACRO_USAGE = Regex("""\$\(([a-zA-Z0-9_]+)\)""")

/**
 * The macros a function declaration writes, [declared] being the names its typed `Macros` class (`function("x", ::XMacros) { }`)
 * holds. [isComplete] is `false` once a name is built at runtime (`macro(name)`), then [used] only holds the certain ones.
 */
class KoreMacroUsage(val used: Set<String>, val declared: Set<String>, val isComplete: Boolean)

/**
 * Reads [this] `function("x") { }` declaration's body: `macro("x")`, `eval("x")`, `$(x)` in a string and `macros.x`, plus the
 * project functions it calls, since a `fun Function.helper()` writes into the caller. Nested declarations are their own.
 */
fun KtCallExpression.macroUsage(): KoreMacroUsage = CachedValuesManager.getCachedValue(this) {
	val usage = analyze(this) {
		val collector = MacroCollector(this, declaredMacros(this@macroUsage))
		valueArguments.mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }.lastOrNull()?.bodyExpression?.accept(collector)
		KoreMacroUsage(collector.used, collector.declared.values.toSet(), collector.isComplete)
	}
	CachedValueProvider.Result(usage, PsiModificationTracker.MODIFICATION_COUNT)
}

/** The `val x by "name"` properties of the `Macros` class a typed declaration takes, as their macro name. */
private fun KaSession.declaredMacros(declaration: KtCallExpression): Map<KtProperty, String> {
	val call = declaration.resolveToCall()?.successfulFunctionCallOrNull() ?: return emptyMap()
	if (call.symbol.valueParameters.none { it.name.asString() == MACROS_PARAMETER }) return emptyMap()
	val macrosClass = (call.typeArgumentsMapping.values.singleOrNull() as? KaClassType)?.symbol as? KaClassSymbol ?: return emptyMap()

	return macrosClass.memberScope.callables.filterIsInstance<KaPropertySymbol>().mapNotNull { symbol ->
		val property = symbol.psi as? KtProperty ?: return@mapNotNull null
		val name = (property.delegateExpression as? KtStringTemplateExpression)?.takeUnless { it.hasInterpolation() } ?: return@mapNotNull null
		property to ElementManipulators.getValueText(name)
	}.toMap()
}

private class MacroCollector(private val session: KaSession, val declared: Map<KtProperty, String>) : KtTreeVisitorVoid() {
	val used = LinkedHashSet<String>()
	var isComplete = true
	private val visited = HashSet<KtNamedFunction>()
	private val declaredNames = declared.keys.mapNotNullTo(HashSet()) { it.name }

	override fun visitCallExpression(expression: KtCallExpression) {
		if (expression.hasFunctionBody() && expression.koreDeclarationKind()?.isFunction == true) return
		when (expression.calleeName()) {
			MACRO_FUNCTION.shortName().asString() -> if (session.resolvesTo(expression, MACRO_FUNCTION)) {
				(expression.namedArgument("name") ?: expression.positionalArgument(0))?.let(::addName)
			}

			EVAL_FUNCTION.shortName().asString() -> if (session.resolvesTo(expression, EVAL_FUNCTION)) {
				expression.valueArguments.forEach { if (it.getSpreadElement() != null) isComplete = false else it.getArgumentExpression()?.let(::addName) }
			}

			else -> visitHelper(expression)
		}
		super.visitCallExpression(expression)
	}

	override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
		if (expression.getReferencedName() in declaredNames) (expression.mainReference.resolve() as? KtProperty)?.let(declared::get)?.let(used::add)
		super.visitSimpleNameExpression(expression)
	}

	override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
		super.visitStringTemplateExpression(expression)
		val text = expression.entries.joinToString("") {
			when (it) {
				is KtLiteralStringTemplateEntry -> it.text
				is KtEscapeStringTemplateEntry -> it.unescapedValue
				is KtBlockStringTemplateEntry -> if (it.expression?.text == "'$'") "$" else "\u0000"
				else -> "\u0000"
			}
		}
		MACRO_USAGE.findAll(text).mapTo(used) { it.groupValues[1] }
	}

	private fun addName(expression: KtExpression) {
		val name = expression.koreStringValue(ResolvingPropertyResolver)
		if (name == null || name.isDynamic) isComplete = false else used += name.text
	}

	/** A project function the body calls writes its lines (and macros) into this function, so its body is read too. */
	private fun visitHelper(expression: KtCallExpression) {
		val name = expression.calleeName() ?: return
		if (KotlinFunctionShortNameIndex[name, expression.project, GlobalSearchScope.projectScope(expression.project)].isEmpty()) return

		val symbol = with(session) { expression.resolveToCall()?.successfulFunctionCallOrNull()?.symbol }
		if (symbol == null) {
			isComplete = false
			return
		}
		if (symbol.origin != KaSymbolOrigin.SOURCE || symbol.callableId?.asSingleFqName()?.asString()?.startsWith(KORE_PACKAGE_PREFIX) == true) return
		val helper = symbol.psi as? KtNamedFunction ?: return
		if (visited.add(helper)) helper.bodyExpression?.accept(this)
	}
}
