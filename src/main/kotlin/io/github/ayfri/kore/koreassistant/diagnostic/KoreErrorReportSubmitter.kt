package io.github.ayfri.kore.koreassistant.diagnostic

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Consumer
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import java.awt.Component
import java.net.URLEncoder

private const val NEW_ISSUE_URL = "https://github.com/Kore-Minecraft/Kore-Assistant-Intellij/issues/new"
// Browsers and GitHub start rejecting issue URLs past ~8k characters, so the stack trace is cut to fit.
const val MAX_ISSUE_URL_LENGTH = 8000
private const val MAX_TITLE_LENGTH = 120

/** Opens the bug issue form prefilled through its field ids: no backend to host, and the user reviews it all before posting. */
class KoreErrorReportSubmitter : ErrorReportSubmitter() {
	override fun getReportActionText() = "Open a GitHub Issue"

	override fun getPrivacyNoticeText() =
		"Opens a prefilled GitHub issue in your browser. Nothing is sent until you submit it there."

	/** The OS of the machine the plugin runs on, the backend in remote development, is the one that matters for its errors. */
	@OptIn(LowLevelLocalMachineAccess::class)
	override fun submit(
		events: Array<out IdeaLoggingEvent>,
		additionalInfo: String?,
		parentComponent: Component,
		consumer: Consumer<in SubmittedReportInfo>,
	): Boolean {
		val event = events.firstOrNull() ?: return false
		val app = ApplicationInfo.getInstance()
		val environment = "${app.fullApplicationName} (${app.build.asString()}), ${OS.CURRENT.name} ${OS.CURRENT.version()}, JBR ${SystemInfo.JAVA_RUNTIME_VERSION}"

		BrowserUtil.browse(bugIssueUrl(event.throwableText, pluginDescriptor.version, environment, additionalInfo.orEmpty()))
		consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
		return true
	}
}

/** The `bug.yml` form URL, keys being the form's field ids; the trace keeps as many leading lines as [MAX_ISSUE_URL_LENGTH] allows. */
fun bugIssueUrl(trace: String, pluginVersion: String, environment: String, description: String): String {
	val url = "$NEW_ISSUE_URL?" + listOf(
		"template" to "bug.yml",
		"title" to "[BUG] ${trace.lineSequence().first().take(MAX_TITLE_LENGTH)}",
		"plugin-version" to pluginVersion,
		"ide-version" to environment,
		"what-happened" to description,
	).joinToString("&") { (key, value) -> "$key=${value.encoded()}" }

	var budget = MAX_ISSUE_URL_LENGTH - url.length - "&stacktrace=".length
	val keptTrace = trace.lines().takeWhile { line -> (budget - line.encoded().length - "%0A".length).also { budget = it } >= 0 }
	return "$url&stacktrace=${keptTrace.joinToString("\n").encoded()}"
}

private fun String.encoded() = URLEncoder.encode(this, Charsets.UTF_8)
