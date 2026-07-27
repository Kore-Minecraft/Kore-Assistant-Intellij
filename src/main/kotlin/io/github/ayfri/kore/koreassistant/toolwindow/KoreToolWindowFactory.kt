package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService

/** Only the opening state is decided here; [KoreToolWindowRegistrar] re-answers it whenever the roots change. */
class KoreToolWindowFactory : ToolWindowFactory {
	override fun shouldBeAvailable(project: Project) = project.service<KoreLibraryService>().isKoreProject

	override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
		val toolWindowContent = KoreToolWindowContent(project)
		val content = ContentFactory.getInstance().createContent(toolWindowContent.contentPanel, "Elements", false)
		content.isCloseable = false

		// The content owns a PSI listener and a merging queue, both of which must die with the tool window.
		Disposer.register(content, toolWindowContent)
		toolWindow.contentManager.addContent(content)
	}
}
