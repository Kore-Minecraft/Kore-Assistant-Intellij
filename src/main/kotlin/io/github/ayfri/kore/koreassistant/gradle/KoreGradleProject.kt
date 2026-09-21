package io.github.ayfri.kore.koreassistant.gradle

import com.intellij.icons.AllIcons
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.service.project.GradleTasksIndices
import org.jetbrains.plugins.gradle.util.GradleConstants
import javax.swing.Icon

/** Gradle plugin id shared by `plugins { id("...") }` and the setup intention. */
const val KORE_GRADLE_PLUGIN_ID = "io.github.ayfri.kore"

/** The tasks the Kore Gradle plugin registers, as offered by the run marker. */
enum class KoreGradleTask(val taskName: String, val text: String, val icon: Icon) {
	BUILD("koreBuild", "Build datapack", AllIcons.Actions.Compile),
	LINK("koreLink", "Link into worlds", AllIcons.Nodes.Deploy),
	RELOAD("koreReload", "Reload server", AllIcons.Actions.Refresh),
	RUN("koreRun", "Run 'koreRun'", AllIcons.Actions.Execute),
}

/** The Gradle project directory this module is imported from, or `null` when it is not a Gradle module. */
fun Module.gradleProjectPath(): String? =
	if (ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, this)) ExternalSystemApiUtil.getExternalProjectPath(this) else null

/** Whether the last sync saw the Kore Gradle plugin's tasks in the project at [gradleProjectPath]; the platform caches the task index. */
fun Project.hasKoreGradlePlugin(gradleProjectPath: String): Boolean =
	GradleTasksIndices.getInstance(this).findTasks(gradleProjectPath, KoreGradleTask.RUN.taskName).isNotEmpty()
