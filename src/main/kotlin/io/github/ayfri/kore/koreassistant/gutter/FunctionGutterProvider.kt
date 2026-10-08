package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.asCalleeOf
import io.github.ayfri.kore.koreassistant.psi.declarationNameArgument
import io.github.ayfri.kore.koreassistant.psi.resolvesTo
import io.github.ayfri.kore.koreassistant.references.KoreFunctionTarget
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.Icon

class FunctionGutterProvider : KoreCallGutterProvider() {
	override fun declarationAt(element: PsiElement) = element.asCalleeOf(KoreNames.FUNCTION.shortName())?.let { it to KoreDeclarationKind.FUNCTION }

	override fun KaSession.confirms(call: KtCallExpression) = resolvesTo(call, KoreNames.FUNCTION)

	/** Clicking lists the `function("x")` calls targeting this declaration, searched only once the icon is clicked. */
	override fun createMarker(element: PsiElement, call: KtCallExpression, icon: Icon, accessibleName: String, tooltip: () -> String): LineMarkerInfo<*> {
		val target = KoreFunctionTarget(call.declarationNameArgument() ?: return super.createMarker(element, call, icon, accessibleName, tooltip), isTag = false)
		// The builder only takes a fixed tooltip string, so its click handler is reused under the lazily built hover card.
		val calls = NavigationGutterIconBuilder.create(icon)
			.setTargets(NotNullLazyValue.lazy { ReferencesSearch.search(target).findAll().map { it.element } })
			.setPopupTitle("Calls of '${target.name}'")
			.setEmptyPopupText("No function(\"${target.name}\") call found")
			.createLineMarkerInfo(element)

		return RelatedItemLineMarkerInfo(element, element.textRange, icon, { tooltip() }, calls.navigationHandler, GutterIconRenderer.Alignment.CENTER, calls::createGotoRelatedItems)
	}
}
