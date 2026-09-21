package io.github.ayfri.kore.koreassistant.gradle

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.asCalleeOf
import io.github.ayfri.kore.koreassistant.psi.resolvesTo
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.plugins.gradle.action.GradleExecuteTaskAction

private val MENU = listOf(KoreGradleTask.RUN, KoreGradleTask.BUILD, KoreGradleTask.LINK, KoreGradleTask.RELOAD)

/**
 * The run marker on `dataPack(...)`, offering the Kore Gradle plugin's tasks. Only shows once a sync has seen `koreRun`
 * in the module's Gradle project: before that, the plain Kotlin "Run main" marker is all there is and
 * [ApplyKoreGradlePluginIntention] is the way in. Same gate order as the navigation gutter: syntactic first, `analyze { }` last.
 */
class KoreRunLineMarkerContributor : RunLineMarkerContributor() {
	override fun getInfo(element: PsiElement): Info? {
		val call = element.asCalleeOf(KoreNames.DATA_PACK.shortName()) ?: return null
		val project = element.project
		if (!project.service<KoreLibraryService>().isKoreProject) return null

		val gradleProjectPath = ModuleUtilCore.findModuleForPsiElement(element)?.gradleProjectPath() ?: return null
		if (!project.hasKoreGradlePlugin(gradleProjectPath)) return null

		analyze(call) {
			if (!resolvesTo(call, KoreNames.DATA_PACK)) return null
		}

		val actions = MENU.map { RunKoreGradleTaskAction(gradleProjectPath, it) }.toTypedArray<AnAction>()
		return Info(AllIcons.RunConfigurations.TestState.Run, actions, java.util.function.Function { "Run the datapack through the Kore Gradle plugin" })
	}
}

/** Runs one Kore task the way Run Anything does: a temporary Gradle run configuration, selected and executed. */
private class RunKoreGradleTaskAction(private val gradleProjectPath: String, private val task: KoreGradleTask) :
	AnAction(task.text, "Runs the '${task.taskName}' Gradle task", task.icon) {

	override fun getActionUpdateThread() = ActionUpdateThread.BGT

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		GradleExecuteTaskAction.runGradle(project, DefaultRunExecutor.getRunExecutorInstance(), gradleProjectPath, task.taskName)
	}

	// Line marker infos are compared between passes; stable equality keeps the marker from flickering.
	override fun equals(other: Any?) = other is RunKoreGradleTaskAction && other.gradleProjectPath == gradleProjectPath && other.task == task

	override fun hashCode() = 31 * gradleProjectPath.hashCode() + task.hashCode()
}
