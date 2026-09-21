package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElement
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElementFinder

/**
 * Every confirmed Kore declaration of the project, as the tool window sees them, cached per PSI modification so the
 * inspections of every open file share one collection pass. Must run inside a smart-mode read action.
 */
fun Project.koreElements(): List<KoreElement> = CachedValuesManager.getManager(this).getCachedValue(this) {
	CachedValueProvider.Result(
		KoreElementFinder.collect(this, GlobalSearchScope.projectScope(this)),
		PsiModificationTracker.MODIFICATION_COUNT,
	)
}
