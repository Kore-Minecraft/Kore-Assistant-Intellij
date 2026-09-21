package io.github.ayfri.kore.koreassistant.services

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/** The plugin's project-lifetime [CoroutineScope], injected and cancelled by the platform when the project closes or the plugin unloads. */
@Service(Service.Level.PROJECT)
class KoreScopeService(val scope: CoroutineScope)
