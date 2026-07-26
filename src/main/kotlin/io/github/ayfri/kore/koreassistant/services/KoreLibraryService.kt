package io.github.ayfri.kore.koreassistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.openapi.module.ModuleManager

private const val KORE_LIBRARY_MARKER = "io.github.ayfri.kore"

/**
 * Detects whether the current project depends on Kore, so every other extension point can bail out
 * instantly on unrelated Kotlin projects instead of taxing them. Cached on the project root modification
 * tracker so it only recomputes after a Gradle/Maven sync.
 */
@Service(Service.Level.PROJECT)
class KoreLibraryService(private val project: Project) {
	val isKoreProject: Boolean
		get() = CachedValuesManager.getManager(project).getCachedValue(project) {
			CachedValueProvider.Result(computeIsKoreProject(), ProjectRootManager.getInstance(project))
		}

	/** Kore's own version, resolved from the `kore` library entry, or `null` if not a Kore project / unparsable. */
	val version: KoreVersion?
		get() = CachedValuesManager.getManager(project).getCachedValue(project) {
			CachedValueProvider.Result(computeVersion(), ProjectRootManager.getInstance(project) as ModificationTracker)
		}

	private fun computeIsKoreProject(): Boolean =
		ModuleManager.getInstance(project).modules.any { module ->
			ModuleRootManager.getInstance(module).orderEntries.any { entry ->
				entry is LibraryOrderEntry && entry.libraryName?.contains(KORE_LIBRARY_MARKER) == true
			}
		}

	private fun computeVersion(): KoreVersion? {
		for (module in ModuleManager.getInstance(project).modules) {
			for (entry in ModuleRootManager.getInstance(module).orderEntries) {
				if (entry !is LibraryOrderEntry) continue
				val name = entry.libraryName ?: continue
				if (!name.contains(KORE_LIBRARY_MARKER)) continue

				val rawVersion = name.substringAfterLast(':', "")
				if (rawVersion.isEmpty()) continue
				return KoreVersion.parse(rawVersion) ?: continue
			}
		}
		return null
	}
}
