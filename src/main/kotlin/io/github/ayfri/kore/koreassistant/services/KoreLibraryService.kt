package io.github.ayfri.kore.koreassistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

private const val KORE_LIBRARY_MARKER = "io.github.ayfri.kore"

/**
 * Detects whether the current project depends on Kore, so every other extension point can bail out
 * instantly on unrelated Kotlin projects instead of taxing them. Cached on the project root modification
 * tracker so it only recomputes after a Gradle/Maven sync.
 */
@Service(Service.Level.PROJECT)
class KoreLibraryService(private val project: Project) {
	/** The Kore library entry's name, e.g. `Gradle: io.github.ayfri.kore:kore:2.8.0-26.1.2`, or `null` on a non-Kore project. */
	private val libraryName: String?
		get() = CachedValuesManager.getManager(project).getCachedValue(project) {
			CachedValueProvider.Result(findLibraryName(), ProjectRootManager.getInstance(project))
		}

	val isKoreProject get() = libraryName != null

	/** Kore's own version, or `null` if not a Kore project / unparsable. */
	val version get() = libraryName?.substringAfterLast(':', "")?.let(KoreVersion::parse)

	private fun findLibraryName() = ModuleManager.getInstance(project).modules.asSequence()
		.flatMap { ModuleRootManager.getInstance(it).orderEntries.asSequence() }
		.filterIsInstance<LibraryOrderEntry>()
		.firstNotNullOfOrNull { entry -> entry.libraryName?.takeIf { it.contains(KORE_LIBRARY_MARKER) } }
}
