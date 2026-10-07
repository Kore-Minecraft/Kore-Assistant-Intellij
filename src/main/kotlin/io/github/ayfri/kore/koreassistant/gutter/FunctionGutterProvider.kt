package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.declarationNameArgument
import io.github.ayfri.kore.koreassistant.references.KoreFunctionTarget
import org.jetbrains.kotlin.psi.KtCallExpression

class FunctionGutterProvider : KoreCallGutterProvider(
	fqName = KoreNames.FUNCTION,
	icon = KoreIcons.FUNCTION,
	tooltipTitle = "Function Definition",
	tooltipText = "Kore function definition, click to see its function(...) calls",
) {
	/** The `function("x")` calls targeting this declaration, searched only once the icon is clicked. */
	override fun NavigationGutterIconBuilder<PsiElement>.withTargets(call: KtCallExpression, element: PsiElement): NavigationGutterIconBuilder<PsiElement> {
		val target = KoreFunctionTarget(call.declarationNameArgument() ?: return setTarget(element), isTag = false)
		return setTargets(NotNullLazyValue.lazy { ReferencesSearch.search(target).findAll().map { it.element } })
			.setPopupTitle("Calls of '${target.name}'")
			.setEmptyPopupText("No function(\"${target.name}\") call found")
	}
}
