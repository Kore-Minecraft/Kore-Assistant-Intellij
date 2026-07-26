package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import java.util.function.Supplier

const val KORE_TOOL_WINDOW_ID = "Kore Elements"

/**
 * Registers the tool window programmatically instead of declaratively: `ToolWindowFactory.isApplicableAsync` is
 * evaluated only once when the project opens, which loses the race against Gradle sync, so the window stayed hidden
 * until the next IDE restart. Re-syncing on root changes also picks up Kore being added or removed later.
 */
class KoreToolWindowRegistrar : ProjectActivity {
	override suspend fun execute(project: Project) {
		project.messageBus.connect(project).subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
			override fun rootsChanged(event: ModuleRootEvent) = syncToolWindow(project)
		})
		syncToolWindow(project)
	}
}

/** Tool window operations must go through [ToolWindowManager.invokeLater], not the plain application one. */
private fun syncToolWindow(project: Project) {
	val manager = ToolWindowManager.getInstance(project)
	manager.invokeLater {
		if (project.isDisposed) return@invokeLater

		val existing = manager.getToolWindow(KORE_TOOL_WINDOW_ID)
		when {
			!project.service<KoreLibraryService>().isKoreProject -> existing?.remove()

			existing == null -> manager.registerToolWindow(KORE_TOOL_WINDOW_ID) {
				anchor = ToolWindowAnchor.RIGHT
				icon = KoreIcons.KORE
				stripeTitle = Supplier { KORE_TOOL_WINDOW_ID }
				contentFactory = KoreToolWindowFactory()
			}
		}
	}
}
