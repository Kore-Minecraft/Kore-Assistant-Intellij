package io.github.ayfri.kore.koreassistant.settings

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.io.HttpRequests
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import io.github.ayfri.kore.koreassistant.services.KoreVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.days

private const val METADATA_URL = "https://repo1.maven.org/maven2/io/github/ayfri/kore/kore/maven-metadata.xml"
private const val RELEASES_URL = "https://github.com/Ayfri/Kore/releases"
private const val NOTIFICATION_GROUP = "Kore Assistant"
private val RELEASE_TAG = Regex("<release>([^<]+)</release>")
private val CHECK_INTERVAL = 1.days

private val LOGGER = Logger.getInstance(KoreUpdateChecker::class.java)

/**
 * Tells the user when Kore has a newer release than the one their project depends on. Kore releases with every Minecraft
 * version and nothing in the IDE says so. Opt-in ([KoreSettings.State.checkKoreUpdates]), at most one request a day,
 * each release announced once.
 */
class KoreUpdateChecker : ProjectActivity {
	override suspend fun execute(project: Project) {
		val settings = KoreSettings.instance.state
		if (!settings.checkKoreUpdates) return
		val current = project.service<KoreLibraryService>().version ?: return
		val now = System.currentTimeMillis()
		if (now - settings.lastUpdateCheck < CHECK_INTERVAL.inWholeMilliseconds) return
		settings.lastUpdateCheck = now

		val latest = withContext(Dispatchers.IO) {
			try {
				RELEASE_TAG.find(HttpRequests.request(METADATA_URL).readString())?.groupValues?.get(1)?.let(KoreVersion::parse)
			} catch (e: IOException) {
				LOGGER.info("Could not check for Kore updates", e)
				null
			}
		} ?: return
		if (latest <= current || latest.toString() == settings.notifiedVersion) return
		settings.notifiedVersion = latest.toString()

		NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
			.createNotification(
				"Kore ${latest.kore} is available",
				"This project uses Kore ${current.kore} (Minecraft ${current.minecraft}), the latest is ${latest.kore} (Minecraft ${latest.minecraft}).",
				NotificationType.INFORMATION,
			)
			.addAction(NotificationAction.createSimpleExpiring("Release notes") { BrowserUtil.browse(RELEASES_URL) })
			.addAction(NotificationAction.createSimpleExpiring("Stop checking") { settings.checkKoreUpdates = false })
			.notify(project)
	}
}
