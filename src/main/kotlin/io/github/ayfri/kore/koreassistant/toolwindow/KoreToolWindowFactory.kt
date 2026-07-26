package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Applicability is not decided here: [KoreToolWindowRegistrar] only registers this factory on Kore projects. */
class KoreToolWindowFactory : ToolWindowFactory {
	override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
		val toolWindowContent = KoreToolWindowContent(project)
		val content =
			ContentFactory.getInstance().createContent(toolWindowContent.contentPanel, KORE_TOOL_WINDOW_ID, false)
		toolWindow.contentManager.addContent(content)
	}
}
