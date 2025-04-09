package io.github.ayfri.kore.koreassistant.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Simple action to trigger the refresh logic in the KoreToolWindowContent.
 */
class RefreshKoreElementsAction(private val refreshCallback: () -> Unit) :
	AnAction("Refresh", "Reload Kore elements list", AllIcons.Actions.Refresh) {

	override fun actionPerformed(e: AnActionEvent) {
		refreshCallback()
	}
}
