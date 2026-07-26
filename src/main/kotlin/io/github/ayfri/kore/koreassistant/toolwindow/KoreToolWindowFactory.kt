package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService

class KoreToolWindowFactory : ToolWindowFactory {
	override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
		val toolWindowContent = KoreToolWindowContent(project)
		val content = ContentFactory.getInstance().createContent(toolWindowContent.contentPanel, "Kore Elements", false)
		toolWindow.contentManager.addContent(content)
	}

	override suspend fun isApplicableAsync(project: Project) = project.service<KoreLibraryService>().isKoreProject
}
