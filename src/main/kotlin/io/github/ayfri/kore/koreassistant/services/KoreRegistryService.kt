package io.github.ayfri.kore.koreassistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.util.concurrent.ConcurrentHashMap

private const val GENERATED_PACKAGE = "io.github.ayfri.kore.generated"

/**
 * Resolves vanilla id registries (items, blocks, gamerules, ...) straight from the user's own resolved
 * `kore` jar via PSI, instead of a bundled snapshot - so it always matches whatever Kore/Minecraft version
 * the user actually depends on. Cached per module, invalidated on the project root modification tracker
 * (i.e. after a Gradle/Maven sync), same pattern as [KoreLibraryService].
 */
@Service(Service.Level.PROJECT)
class KoreRegistryService(private val project: Project) {
	private fun cache(): ConcurrentHashMap<String, Set<String>> =
		CachedValuesManager.getManager(project).getCachedValue(project) {
			CachedValueProvider.Result(ConcurrentHashMap(), ProjectRootManager.getInstance(project))
		}

	/** Entry names declared on `io.github.ayfri.kore.generated.$registryClass` (e.g. `"Items"`, `"Gamerules"`). */
	fun ids(module: Module, registryClass: String): Set<String> =
		cache().getOrPut("${module.name}:$registryClass") { computeIds(module, registryClass) }

	private fun computeIds(module: Module, registryClass: String): Set<String> {
		val scope = GlobalSearchScope.moduleRuntimeScope(module, false)
		val psiClass = JavaPsiFacade.getInstance(project).findClass("$GENERATED_PACKAGE.$registryClass", scope)
			?: return emptySet()

		return psiClass.fields
			.filter { it.hasModifierProperty(PsiModifier.STATIC) }
			.mapNotNull { it.name }
			.toSet()
	}
}
