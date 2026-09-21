package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import io.github.ayfri.kore.koreassistant.psi.asCalleeOf
import io.github.ayfri.kore.koreassistant.psi.resolvesTo
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.name.FqName
import javax.swing.Icon

/**
 * Shared line marker logic for `dataPack(...)` and `function(...)` declarations. The syntactic gate
 * ([asCalleeOf]) and the cached Kore-project check run first so the Analysis API ([resolvesTo]) is only
 * invoked on plausible candidates in projects that actually depend on Kore.
 */
abstract class KoreCallGutterProvider(
	private val fqName: FqName,
	private val icon: Icon,
	private val tooltipTitle: String,
	private val tooltipText: String,
) : LineMarkerProviderDescriptor() {
	private val shortName = fqName.shortName()

	final override fun getName() = this::class.simpleName

	final override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
		val callExpression = element.asCalleeOf(shortName) ?: return null
		if (!element.project.service<KoreLibraryService>().isKoreProject) return null

		analyze(callExpression) {
			if (!resolvesTo(callExpression, fqName)) return null
		}

		return NavigationGutterIconBuilder.create(icon)
			.setAlignment(GutterIconRenderer.Alignment.CENTER)
			.setTooltipTitle(tooltipTitle)
			.setTooltipText(tooltipText)
			.setTarget(callExpression.valueArguments.firstOrNull()?.getArgumentExpression() ?: element)
			.createLineMarkerInfo(element)
	}
}
