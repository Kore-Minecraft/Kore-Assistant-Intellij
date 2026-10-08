package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.util.parents
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.ResolvingPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.hasFunctionBody
import io.github.ayfri.kore.koreassistant.psi.koreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.koreStringValue
import io.github.ayfri.kore.koreassistant.psi.namedArgument
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

private const val STORAGE_BUILDER = "storage"
private const val DATA_COMMAND = "data"
private const val MERGE_OPERATION = "merge"
private const val DEFAULT_NAMESPACE = "minecraft"

/**
 * `function("x", arguments = storage("args", "ns"), path = "x")` reads its macros from a storage at runtime. What the calling
 * function writes to that storage under `path` before the call (`data(st) { modify("x.a", ...) }`, `storage(st, "x.a", ...)` in a
 * `storeResult`, ...) stands for the passed compound. A storage persists across functions and ticks, so nothing is reported unless
 * the caller writes there at all, nor when a write covers the whole compound or a path is built at runtime.
 */
internal fun ProblemsHolder.checkStorageArguments(call: KtCallExpression, target: KtExpression, label: String, used: Collection<String>, storage: KtExpression, path: KtExpression?) {
	val id = storage.storageId() ?: return
	val prefix = path?.let { it.staticString() ?: return }
	val writes = call.storageWritesBefore(id) ?: return

	val keys = HashSet<String>()
	for (write in writes) {
		val relative = when {
			prefix == null -> write
			write == prefix || prefix.startsWith("$write.") -> return
			write.startsWith("$prefix.") -> write.removePrefix("$prefix.")
			else -> continue
		}
		keys += relative.split('.', '[').first()
	}

	val missing = used.filter { it !in keys }
	if (keys.isEmpty() || missing.isEmpty()) return
	val location = "'$id'${prefix?.let { " at '$it'" }.orEmpty()}"
	val subject = if (missing.size > 1) "Macro arguments ${missing.joinToString { "'$it'" }} of function '$label' are" else "Macro argument '${missing.single()}' of function '$label' is"
	registerProblem(target, "$subject never written to storage $location before this call", ProblemHighlightType.WARNING)
}

/** `ns:name` of a `storage("name", "ns")` call, or of the variable holding one. */
private fun KtExpression.storageId(): String? {
	val call = (if (this is KtSimpleNameExpression) ResolvingPropertyResolver.initializerOf(this) else this) as? KtCallExpression ?: return null
	if (call.calleeName() != STORAGE_BUILDER) return null
	val name = (call.namedArgument(STORAGE_BUILDER) ?: call.positionalArgument(0))?.staticString() ?: return null
	val namespace = (call.namedArgument("namespace") ?: call.positionalArgument(1))?.let { it.staticString() ?: return null } ?: DEFAULT_NAMESPACE
	return "$namespace:$name"
}

/**
 * Every path the enclosing function body writes to storage [id] before [this] call, `null` when one of them cannot be read. Any call
 * taking the storage counts its string arguments as paths, so a read (`data(st).get("x")`) only ever hides a report.
 */
private fun KtCallExpression.storageWritesBefore(id: String): List<String>? {
	val declaration = parents(false).filterIsInstance<KtCallExpression>().firstOrNull { it.hasFunctionBody() && it.koreDeclarationKind()?.isFunction == true }
	val body = declaration?.lambdaArguments?.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return null
	val end = textRange.startOffset
	val writes = mutableListOf<String?>()

	body.accept(object : KtTreeVisitorVoid() {
		override fun visitCallExpression(expression: KtCallExpression) {
			if (expression.textRange.startOffset >= end) return
			val arguments = expression.valueArguments.mapNotNull { it.getArgumentExpression() }
			if (arguments.any { it.storageId() == id }) writes += when (expression.calleeName()) {
				DATA_COMMAND -> expression.dataWrites()
				// Another macro call reading the same storage, whose name and path are no writes.
				KoreNames.FUNCTION_COMMAND.shortName().asString() -> emptyList()
				else -> arguments.filterIsInstance<KtStringTemplateExpression>().map { it.staticString() }
			}
			super.visitCallExpression(expression)
		}
	})
	return writes.takeIf { null !in it }?.filterNotNull()
}

/** The paths `data(st) { ... }`, `data(st).modify("x", ...)` and `data(st)["x"] = ...` write, `merge` naming its compound's keys. */
private fun KtCallExpression.dataWrites(): List<String?> {
	val block = lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression?.statements.orEmpty()
	val chained = when (val parent = parent) {
		is KtDotQualifiedExpression -> listOfNotNull(parent.selectorExpression)
		is KtArrayAccessExpression -> listOfNotNull(parent.parent as? KtBinaryExpression)
		else -> emptyList()
	}
	return (block + chained).flatMap { it.dataWrite() }
}

private fun KtExpression.dataWrite(): List<String?> = when {
	this is KtBinaryExpression && operationToken == KtTokens.EQ -> listOf((left as? KtArrayAccessExpression)?.indexExpressions?.singleOrNull()?.staticString())
	this is KtCallExpression && calleeName() == MERGE_OPERATION ->
		(positionalArgument(0) ?: lambdaArguments.singleOrNull()?.getLambdaExpression()).macroArguments().let { if (it.isComplete) it.names.keys.toList() else listOf(null) }

	this is KtCallExpression -> listOfNotNull(positionalArgument(0)).map { it.staticString() }
	else -> emptyList()
}

private fun KtExpression.staticString() = koreStringValue(ResolvingPropertyResolver)?.takeUnless { it.isDynamic }?.text
