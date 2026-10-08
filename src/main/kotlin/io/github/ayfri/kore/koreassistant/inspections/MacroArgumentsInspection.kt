package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.psi.ResolvingPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.asFunctionCommandName
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.hasFunctionBody
import io.github.ayfri.kore.koreassistant.psi.koreCallAt
import io.github.ayfri.kore.koreassistant.psi.koreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.koreStringValue
import io.github.ayfri.kore.koreassistant.psi.macroUsage
import io.github.ayfri.kore.koreassistant.psi.namedArgument
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.resolve.ImportPath

private const val ARGUMENTS_PARAMETER = "arguments"
private const val BUILDER_PARAMETER = "builder"
private const val FUNCTION_PARAMETER = "function"
private const val GROUP_PARAMETER = "group"
private const val NAME_PARAMETER = "name"
private const val PATH_PARAMETER = "path"
private const val NBT_BUILDER = "nbt"
private const val NBT_IMPORT = "io.github.ayfri.kore.utils.nbt"
private const val SET_IMPORT = "io.github.ayfri.kore.utils.set"
private const val KORE_UTILS_STAR = "io.github.ayfri.kore.utils.*"
private const val PUT_PREFIX = "put"
private const val MAX_SUGGESTION_DISTANCE = 2

/**
 * A function writing `$(x)` fails at runtime when called without an `x` argument, and an argument it never reads is most likely
 * a typo of one it does. Kore only checks `function(typedFunction, nbt { })`, at generation time; calls by name and functions
 * using `macro("x")` are checked by nobody. Arguments read from a storage go through [checkStorageArguments], from an entity or a
 * block are not checked.
 */
class MacroArgumentsInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				// No `hasFunctionBody()` gate: the typed `function(fn) { this["x"] = 1 }` call takes a trailing lambda too.
				if (expression.calleeName() != KoreNames.FUNCTION_COMMAND.shortName().asString()) return
				val (arguments, typed, fromData) = analyze(expression) {
					val call = expression.resolveToCall()?.successfulFunctionCallOrNull() ?: return
					if (call.symbol.callableId?.asSingleFqName() != KoreNames.FUNCTION_COMMAND) return
					Triple(
						call.valueArgumentMapping.entries.associate { (argument, parameter) -> parameter.name.asString() to argument },
						// The typed overloads are the generic ones, `<reified T : Macros>`.
						call.typeArgumentsMapping.isNotEmpty(),
						// Only the `DataArgument` overloads take a path.
						call.symbol.valueParameters.any { it.name.asString() == PATH_PARAMETER },
					)
				}
				if (arguments[GROUP_PARAMETER]?.text == "true") return

				val target = arguments[NAME_PARAMETER] ?: arguments[FUNCTION_PARAMETER] ?: return
				val usage = target.targetDeclaration()?.macroUsage() ?: return
				val passed = arguments[ARGUMENTS_PARAMETER] ?: arguments[BUILDER_PARAMETER]
				val label = (target as? KtStringTemplateExpression)?.let(ElementManipulators::getValueText) ?: target.text
				if (fromData) return holder.checkStorageArguments(expression, target, label, usage.used, passed ?: return, arguments[PATH_PARAMETER])

				val keys = passed.macroArguments()

				val missing = usage.used.filter { it !in keys.names }
				// On the target, not the compound: the platform hides a warning nested in an error of the same inspection.
				if (keys.isComplete && missing.isNotEmpty()) {
					val plural = if (missing.size > 1) "s" else ""
					holder.registerProblem(target, "Missing macro argument$plural ${missing.joinToString { "'$it'" }} for function '$label'", AddMacroArgumentsFix(missing))
				}
				if (!usage.isComplete) return

				// Kore's `verifyArguments` throws on an extra argument of a typed call, Minecraft ignores it.
				val severity = if (typed) ProblemHighlightType.GENERIC_ERROR else ProblemHighlightType.WARNING
				keys.names.filterKeys { it !in usage.used }.forEach { (name, key) ->
					val fixes = missing.filter { editDistance(it, name) <= MAX_SUGGESTION_DISTANCE }.map(::ChangeNameFix)
					holder.registerProblem(key, "Function '$label' does not use macro '$name'", severity, *fixes.toTypedArray())
				}
			}
		}
	}
}

/**
 * Appends `this["x"] = ""` for each missing macro to the call's `nbt { }` (or typed builder lambda), keeping it on one line if it
 * was, else passes a new `arguments = nbt { }`. Syntactic only: quick-fixes run on the EDT, where nothing may be resolved.
 */
private class AddMacroArgumentsFix(private val missing: List<String>) : LocalQuickFix {
	override fun getFamilyName() = "Add missing macro arguments"

	override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
		val call = descriptor.psiElement.parentOfType<KtCallExpression>() ?: return
		val factory = KtPsiFactory(project)
		val additions = missing.map { "this[\"$it\"] = \"\"" }
		val lambda = call.valueArguments.firstNotNullOfOrNull { argument ->
			when (val expression = argument.getArgumentExpression()) {
				is KtLambdaExpression -> expression
				is KtCallExpression -> expression.takeIf { it.calleeName() == NBT_BUILDER }?.lambdaArguments?.singleOrNull()?.getLambdaExpression()
				else -> null
			}
		}

		if (lambda == null) {
			val compound = factory.createExpression("$NBT_BUILDER { ${additions.joinToString("; ")} }")
			call.namedArgument(ARGUMENTS_PARAMETER)?.replace(compound)
				?: call.valueArgumentList?.addArgument(factory.createArgument(compound, Name.identifier(ARGUMENTS_PARAMETER)))
		} else {
			val body = lambda.bodyExpression ?: return
			val multiline = '\n' in lambda.text
			val append = Runnable {
				for (addition in additions) {
					val separators = if (multiline) listOf(factory.createNewLine()) else listOf(factory.createSemicolon(), factory.createWhiteSpace())
					if (body.statements.isNotEmpty()) separators.forEach(body::add)
					body.add(factory.createExpression(addition))
				}
			}
			// The formatter would split a one-line lambda holding several statements; a multiline one needs it for the indentation.
			if (multiline) append.run() else PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside(append)
		}

		val file = call.containingKtFile
		val imported = file.importDirectives.mapNotNull { it.importPath?.pathStr }.toSet()
		if (KORE_UTILS_STAR in imported) return
		listOfNotNull(NBT_IMPORT.takeIf { lambda == null }, SET_IMPORT).filterNot(imported::contains).forEach {
			file.importList?.add(factory.createImportDirective(ImportPath(FqName(it), false)))
		}
	}
}

/** The keys a call passes, [isComplete] being `false` when some are runtime-built or the compound is not an inline `nbt { }`. */
internal class MacroArguments(val names: Map<String, KtExpression>, val isComplete: Boolean)

/** `nbt { this["x"] = 1 }`, its builder lambda alone for the typed overload, or nothing at all. */
internal fun KtExpression?.macroArguments(): MacroArguments {
	if (this == null || this is KtConstantExpression && text == "null") return MacroArguments(emptyMap(), true)
	val body = when (this) {
		is KtLambdaExpression -> bodyExpression
		is KtCallExpression -> takeIf { it.calleeName() == NBT_BUILDER }?.lambdaArguments?.singleOrNull()?.getLambdaExpression()?.bodyExpression
		else -> null
	} ?: return MacroArguments(emptyMap(), false)

	val names = LinkedHashMap<String, KtExpression>()
	var complete = true
	for (statement in body.statements) {
		val key = when {
			statement is KtBinaryExpression && statement.operationToken == KtTokens.EQ ->
				(statement.left as? KtArrayAccessExpression)?.takeIf { it.arrayExpression is KtThisExpression }?.indexExpressions?.singleOrNull()

			statement is KtCallExpression && statement.calleeName()?.startsWith(PUT_PREFIX) == true -> statement.positionalArgument(0) ?: statement.namedArgument("key")
			else -> null
		}
		val name = key?.koreStringValue(ResolvingPropertyResolver)?.takeUnless { it.isDynamic }
		if (name == null) complete = false else names[name.text] = key
	}
	return MacroArguments(names, complete)
}

/** The `function("x") { }` declaration a call targets, by name through the index or as the variable holding it. */
private fun KtExpression.targetDeclaration(): KtCallExpression? {
	val declaration = when (this) {
		is KtStringTemplateExpression -> asFunctionCommandName()?.let { command ->
			KoreDeclarationIndex.findByLocation(command.namespace, command.path, GlobalSearchScope.projectScope(project)) { it.isFunction }
				.singleOrNull()
				?.let { (file, data) -> (PsiManager.getInstance(project).findFile(file) as? KtFile)?.koreCallAt(data.offset, data.kind.builderName) }
		}

		is KtSimpleNameExpression -> ResolvingPropertyResolver.initializerOf(this) as? KtCallExpression
		else -> null
	}
	return declaration?.takeIf { it.hasFunctionBody() && it.koreDeclarationKind()?.isFunction == true }
}
