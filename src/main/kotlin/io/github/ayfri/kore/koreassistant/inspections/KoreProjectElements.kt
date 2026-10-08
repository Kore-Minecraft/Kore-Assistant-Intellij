package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElement
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElementFinder

/**
 * Every confirmed Kore declaration of the project, as the tool window sees them, cached per PSI modification so the
 * inspections of every open file share one collection pass.
 */
@Service(Service.Level.PROJECT)
class KoreProjectElements(project: Project) {
	private val cached = CachedValuesManager.getManager(project).createCachedValue {
		CachedValueProvider.Result(
			KoreElementFinder.collect(project, GlobalSearchScope.projectScope(project)),
			PsiModificationTracker.MODIFICATION_COUNT,
		)
	}

	/** Must run inside a smart-mode read action off the EDT. */
	val elements: List<KoreElement> get() = cached.value

	/** The last collection if nothing changed since, without ever computing one: the only read the EDT may do. */
	val upToDateOrNull: List<KoreElement>? get() = cached.upToDateOrNull?.get()
}

fun Project.koreElements(): List<KoreElement> = service<KoreProjectElements>().elements
