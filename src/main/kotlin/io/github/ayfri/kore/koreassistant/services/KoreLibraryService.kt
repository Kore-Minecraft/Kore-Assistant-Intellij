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
private const val KORE_SOURCE_MARKER = "io/github/ayfri/kore/DataPack.kt"

/**
 * Detects whether the current project depends on Kore, so every other extension point can bail out
 * instantly on unrelated Kotlin projects instead of taxing them. Cached on the project root modification
 * tracker so it only recomputes after a Gradle/Maven sync.
 */
@Service(Service.Level.PROJECT)
class KoreLibraryService(private val project: Project) {
	/** [libraryName] is the Kore library entry, e.g. `Gradle: io.github.ayfri.kore:kore:2.8.0-26.1.2`; [fromSources] means Kore's own repo. */
	private class Detection(val libraryName: String?, val fromSources: Boolean)

	private val detection: Detection
		get() = CachedValuesManager.getManager(project).getCachedValue(project) {
			CachedValueProvider.Result(Detection(findLibraryName(), hasKoreSources()), ProjectRootManager.getInstance(project))
		}

	val isKoreProject get() = detection.libraryName != null || detection.fromSources

	/** Kore's own version, or `null` if not a Kore project, built from Kore's sources, or unparsable. */
	val version get() = detection.libraryName?.substringAfterLast(':', "")?.let(KoreVersion::parse)

	private val modules get() = ModuleManager.getInstance(project).modules.asSequence().map(ModuleRootManager::getInstance)

	private fun findLibraryName() = modules
		.flatMap { it.orderEntries.asSequence() }
		.filterIsInstance<LibraryOrderEntry>()
		.firstNotNullOfOrNull { entry -> entry.libraryName?.takeIf { it.contains(KORE_LIBRARY_MARKER) } }

	/** Kore's own repository has no Kore library: `:kore` is a source module, so look for its entry point in the source roots. */
	private fun hasKoreSources() = modules
		.flatMap { it.sourceRoots.asSequence() }
		.any { it.findFileByRelativePath(KORE_SOURCE_MARKER) != null }
}
