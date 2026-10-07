package io.github.ayfri.kore.koreassistant.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Application-wide plugin settings. The update check makes a network request, so it stays off until the user turns it on. */
@Service(Service.Level.APP)
@State(name = "KoreAssistantSettings", storages = [Storage("koreAssistant.xml")])
class KoreSettings : SimplePersistentStateComponent<KoreSettings.State>(State()) {
	class State : BaseState() {
		var checkKoreUpdates by property(false)

		/** Epoch millis of the last request to Maven Central, so a day of project openings costs one request. */
		var lastUpdateCheck by property(0L)

		/** The latest Kore version already announced, so each release is announced once. */
		var notifiedVersion by string()
	}

	companion object {
		val instance get() = service<KoreSettings>()
	}
}

/** Settings > Tools > Kore Assistant. */
class KoreSettingsConfigurable : BoundConfigurable("Kore Assistant") {
	override fun createPanel() = panel {
		val state = KoreSettings.instance.state
		row {
			checkBox("Notify me when a newer Kore version is released")
				.bindSelected(state::checkKoreUpdates)
				.comment("Asks Maven Central at most once a day, when a project using Kore opens.")
		}
	}
}
