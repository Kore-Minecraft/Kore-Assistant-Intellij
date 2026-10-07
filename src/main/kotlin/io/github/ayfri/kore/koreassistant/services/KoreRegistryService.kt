package io.github.ayfri.kore.koreassistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.util.concurrent.ConcurrentHashMap

private const val GENERATED_PACKAGE = "io.github.ayfri.kore.generated"

/** `EntityItemComponentTypes` keeps the vanilla spelling in this property instead of deriving its id from the entry name. */
private const val ORIGINAL_NAME_PROPERTY = "originalName"

/**
 * Vanilla ids (items, blocks, entity types, ...) read straight from the user's own resolved Kore through PSI, so they
 * always match the Kore/Minecraft version the module depends on. Each registry is a generated enum implementing an
 * argument type (`Items : ItemArgument`) whose entries render as `minecraft:${name.lowercase()}`.
 *
 * Cached per module, invalidated on root changes (a Gradle/Maven sync), same pattern as [KoreLibraryService]. An empty
 * set means "unknown", never "empty registry": callers must skip their check rather than report every id.
 */
@Service(Service.Level.PROJECT)
class KoreRegistryService(private val project: Project) {
	private fun cache(): ConcurrentHashMap<String, Map<String, Set<String>>> =
		CachedValuesManager.getManager(project).getCachedValue(project) {
			CachedValueProvider.Result(ConcurrentHashMap(), ProjectRootManager.getInstance(project))
		}

	/** The ids of the registry enum implementing [argumentClass], a simple name such as `ItemArgument`. Needs a read action. */
	fun ids(module: Module, argumentClass: String): Set<String> =
		cache().getOrPut(module.name) { computeRegistries(module) }[argumentClass].orEmpty()

	private fun computeRegistries(module: Module): Map<String, Set<String>> {
		val scope = GlobalSearchScope.moduleRuntimeScope(module, false)
		val generated = JavaPsiFacade.getInstance(project).findPackage(GENERATED_PACKAGE) ?: return emptyMap()
		val registries = HashMap<String, MutableSet<String>>()

		for (registry in generated.getClasses(scope)) {
			if (!registry.isEnum || registry.findFieldByName(ORIGINAL_NAME_PROPERTY, false) != null) continue
			val ids = registry.fields.filterIsInstance<PsiEnumConstant>().map { it.name.lowercase() }
			// Two enums can share an argument type (biome presets and parameter lists), so their ids add up.
			registry.interfaces.mapNotNull { it.name }.forEach { registries.getOrPut(it, ::HashSet) += ids }
		}

		return registries
	}
}
