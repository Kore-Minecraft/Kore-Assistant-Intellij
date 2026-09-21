package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parents
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.ResolvingPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.koreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.koreStringValue
import io.github.ayfri.kore.koreassistant.psi.namedArgument
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import io.github.ayfri.kore.koreassistant.psi.resolvedOverload
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElement
import io.github.ayfri.kore.koreassistant.toolwindow.UNKNOWN_DATA_PACK
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val NAME_PARAMETER = "name"
private const val NAMESPACE_PARAMETER = "namespace"
private const val GROUP_PARAMETER = "group"
private const val FUNCTION_TAG_FOLDER = "tags/function"
private const val MAX_SUGGESTION_DISTANCE = 2

/**
 * `function("helper")` inside a function body emits `/function <datapack>:helper` (`commands/Function.kt`), and nothing
 * checks that such a function exists: a typo, or a helper declared under another namespace, is a dead command at
 * runtime. The target is looked up among the project's confirmed declarations; the namespace comes from the call's
 * own argument or, for the name-only overload, from the datapack the enclosing declaration belongs to.
 *
 * Nothing is reported when the namespace is unknown to the project (another pack on the server) or holds a
 * runtime-built declaration (`function("gen_$i")`), which could be the target.
 */
class UnresolvedFunctionReferenceInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR

		return object : KtVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				if (expression.calleeName() != KoreNames.FUNCTION_COMMAND.shortName().asString()) return
				if (expression.lambdaArguments.isNotEmpty()) return
				val namespaceFirst = analyze(expression) {
					val overload = resolvedOverload(expression, KoreNames.FUNCTION_COMMAND) ?: return
					overload.valueParameters.firstOrNull()?.name?.asString() == NAMESPACE_PARAMETER
				}

				val reference = expression.functionReference(namespaceFirst) ?: return
				val elements = holder.project.koreElements()
				val targets = elements.filter { it.isFunction || it.isFunctionTag }
				if (targets.any { it.resourceLocation == reference.location }) return
				val sameKind = targets.filter { it.isFunctionTag == reference.group }
				val message = "Function '${reference.location}' is not declared in this project"

				// `function("tick", "minecraft")`: the namespace is unknown to the project precisely because it is the name.
				if (namespaceFirst && sameKind.any { it.namespace == reference.name && it.path == reference.namespace }) {
					holder.registerProblem(reference.nameExpression, message, SwapArgumentsFix)
					return
				}
				if (targets.any { it.namespace == reference.namespace && it.isDynamic }) return
				if (elements.none { it.namespace == reference.namespace || it.dataPackName == reference.namespace }) return

				val fixes = buildList {
					sameKind.filter { it.namespace == reference.namespace }
						.map { it.path }
						.filter { editDistance(it, reference.name) <= MAX_SUGGESTION_DISTANCE }
						.distinct()
						.mapTo(this, ::ChangeNameFix)
					if (!namespaceFirst) sameKind.filter { it.path == reference.name }.map { it.namespace }.distinct().mapTo(this, ::AddNamespaceFix)
					if (!reference.group && expression.enclosingDeclaration()?.parent is KtBlockExpression) add(CreateFunctionFix(reference.name))
				}

				holder.registerProblem(reference.nameExpression, message, *fixes.toTypedArray())
			}
		}
	}
}

private class FunctionReference(val nameExpression: KtExpression, val namespace: String, val name: String, val group: Boolean) {
	val location get() = "${if (group) "#" else ""}$namespace:$name"
}

/** What the call targets, `null` when the name or namespace is runtime-built or unknown. */
private fun KtCallExpression.functionReference(namespaceFirst: Boolean): FunctionReference? {
	val nameExpression = namedArgument(NAME_PARAMETER) ?: positionalArgument(if (namespaceFirst) 1 else 0) ?: return null
	val name = nameExpression.koreStringValue(ResolvingPropertyResolver)?.takeUnless { it.isDynamic } ?: return null
	val group = (namedArgument(GROUP_PARAMETER) ?: positionalArgument(if (namespaceFirst) 2 else 1))?.text == "true"

	val namespace = if (namespaceFirst) {
		(namedArgument(NAMESPACE_PARAMETER) ?: positionalArgument(0))?.koreStringValue(ResolvingPropertyResolver)?.takeUnless { it.isDynamic }?.text
	} else {
		val declaration = enclosingDeclaration() ?: return null
		project.koreElements().firstOrNull { it.fileUrl == containingFile.virtualFile?.url && it.offset == declaration.textOffset }
			?.dataPackName?.takeUnless { it == UNKNOWN_DATA_PACK }
	}

	return FunctionReference(nameExpression, namespace ?: return null, name.text, group)
}

/** The `function("x") { }` / `load { }` / `tick { }` declaration whose body holds this command. */
private fun KtCallExpression.enclosingDeclaration(): KtCallExpression? =
	parents(false).filterIsInstance<KtCallExpression>().firstOrNull { it.koreDeclarationKind()?.isFunction == true }

private val KoreElement.isFunction get() = kind.isFunction
private val KoreElement.isFunctionTag get() = kind.resourceFolder == FUNCTION_TAG_FOLDER

/** The part after `ns:` (and `#`), i.e. what a call spells as its name, directory included. */
private val KoreElement.path get() = resourceLocation.orEmpty().substringAfter(':')

private fun editDistance(a: String, b: String): Int {
	var previous = IntArray(b.length + 1) { it }
	for (i in 1..a.length) {
		val current = IntArray(b.length + 1).also { it[0] = i }
		for (j in 1..b.length) {
			current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
		}
		previous = current
	}
	return previous[b.length]
}

private class ChangeNameFix(private val name: String) : LocalQuickFix {
	override fun getFamilyName() = "Change to '$name'"

	override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
		descriptor.psiElement.replace(KtPsiFactory(project).createExpression("\"$name\""))
	}
}

/** Switches to the `function(namespace, name)` overload, since the name-only one always targets the datapack's namespace. */
private class AddNamespaceFix(private val namespace: String) : LocalQuickFix {
	override fun getFamilyName() = "Call '$namespace:…' instead"

	override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
		val argument = descriptor.psiElement.parentOfType<KtValueArgument>() ?: return
		val list = argument.parentOfType<KtCallExpression>()?.valueArgumentList ?: return
		val namespaceArgument = if (argument.isNamed()) "$NAMESPACE_PARAMETER = \"$namespace\"" else "\"$namespace\""
		list.replace(KtPsiFactory(project).createCallArguments("($namespaceArgument, ${list.arguments.joinToString { it.text }})"))
	}
}

/** `function("tick", "minecraft")` reads naturally but the overload is `(namespace, name)`. */
private object SwapArgumentsFix : LocalQuickFix {
	override fun getFamilyName() = "Swap the namespace and name arguments"

	override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
		val call = descriptor.psiElement.parentOfType<KtCallExpression>() ?: return
		val namespace = call.positionalArgument(0) ?: return
		val name = call.positionalArgument(1) ?: return
		val namespaceCopy = namespace.copy()
		namespace.replace(name.copy())
		name.replace(namespaceCopy)
	}
}

/** Inserts an empty `function("name") { }` right after the declaration holding the call, directory split out of the name. */
private class CreateFunctionFix(private val name: String) : LocalQuickFix {
	override fun getFamilyName() = "Create function '$name'"

	override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
		val declaration = (descriptor.psiElement as? KtExpression)?.parentOfType<KtCallExpression>()?.enclosingDeclaration() ?: return
		val block = declaration.parent as? KtBlockExpression ?: return
		val directory = name.substringBeforeLast('/', "")
		val arguments = if (directory.isEmpty()) "\"$name\"" else "\"${name.substringAfterLast('/')}\", directory = \"$directory\""
		val factory = KtPsiFactory(project)
		val created = block.addAfter(factory.createExpression("function($arguments) {\n}"), declaration)
		block.addBefore(factory.createNewLine(2), created)
	}
}
