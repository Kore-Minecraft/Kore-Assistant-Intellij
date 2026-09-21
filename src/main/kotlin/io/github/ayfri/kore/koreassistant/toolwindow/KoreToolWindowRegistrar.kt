package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService

const val KORE_TOOL_WINDOW_ID = "Kore Elements"

/**
 * Keeps the tool window's availability in step with the project. `ToolWindowFactory.shouldBeAvailable` is
 * evaluated once when the project opens, which loses the race against Gradle sync; re-syncing here and on
 * every root change (declared in `plugin.xml`) also picks up Kore being added or removed later.
 */
class KoreToolWindowRegistrar : ProjectActivity {
	override suspend fun execute(project: Project) = syncToolWindow(project)
}

class KoreRootsListener(private val project: Project) : ModuleRootListener {
	override fun rootsChanged(event: ModuleRootEvent) = syncToolWindow(project)
}

/** Tool window operations must go through [ToolWindowManager.invokeLater], not the plain application one. */
private fun syncToolWindow(project: Project) {
	val manager = ToolWindowManager.getInstance(project)
	manager.invokeLater {
		if (project.isDisposed) return@invokeLater

		manager.getToolWindow(KORE_TOOL_WINDOW_ID)?.isAvailable = project.service<KoreLibraryService>().isKoreProject
	}
}
