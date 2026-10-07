package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElementVisitor
import io.github.ayfri.kore.koreassistant.psi.asVanillaIdArgument
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import io.github.ayfri.kore.koreassistant.services.KoreRegistryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val KORE_PACKAGE_PREFIX = "io.github.ayfri.kore."
private const val MAX_SUGGESTION_DISTANCE = 2
private const val MAX_SUGGESTIONS = 3

/**
 * `item("diamond_swrod")` compiles and generates, then Minecraft drops the recipe, loot table or command using it. The
 * id is checked against the registry enum of the user's own Kore (`Items`, `Blocks`, `EntityTypes`, ...), so it always
 * matches the Minecraft version they target. Nothing is reported when that registry cannot be read.
 */
class UnknownVanillaIdInspection : LocalInspectionTool() {
	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		if (!holder.project.service<KoreLibraryService>().isKoreProject) return PsiElementVisitor.EMPTY_VISITOR
		val module = ModuleUtilCore.findModuleForFile(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
		val registries = holder.project.service<KoreRegistryService>()

		return object : KtVisitorVoid() {
			override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
				val argument = expression.asVanillaIdArgument() ?: return
				val ids = registries.ids(module, argument.argumentClass).ifEmpty { return }
				val id = argument.id
				if (id.isEmpty() || id in ids) return

				analyze(argument.call) {
					val callable = argument.call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return
					if (!callable.asSingleFqName().asString().startsWith(KORE_PACKAGE_PREFIX)) return
				}

				val fixes = ids.map { it to editDistance(it, id) }
					.filter { it.second <= MAX_SUGGESTION_DISTANCE }
					.sortedWith(compareBy({ it.second }, { it.first }))
					.take(MAX_SUGGESTIONS)
					.map { ChangeNameFix(it.first) }

				holder.registerProblem(expression, "Unknown ${argument.registryName} '$id'", *fixes.toTypedArray())
			}
		}
	}
}
