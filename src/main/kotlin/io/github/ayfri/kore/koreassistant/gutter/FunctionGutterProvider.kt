package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class FunctionGutterProvider : LineMarkerProviderDescriptor() {
	override fun getName() = "FunctionGutterProvider"

	@OptIn(KaIdeApi::class)
	override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
		// We look for the function name identifier itself
		if (element !is LeafPsiElement || element.elementType != KtTokens.IDENTIFIER) {
			return null
		}

		// Check parents: Identifier -> KtNameReferenceExpression -> KtCallExpression
		val nameReferenceExpr = element.parent as? KtNameReferenceExpression ?: return null
		val callExpression = nameReferenceExpr.parent as? KtCallExpression ?: return null

		// Make sure the identifier is the function being called, not an argument or receiver
		if (callExpression.calleeExpression !== nameReferenceExpr) {
			return null
		}

		// Quick check: function name must be 'function'
		if (nameReferenceExpr.getReferencedNameAsName() != KoreNames.KORE_FUNCTION_NAME) {
			return null
		}

		// Use the Analysis API to resolve the call
		analyze(callExpression) { // 'this' is the KaSession
			// Use resolveToCall and get the symbol
			val functionCall = callExpression.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
			// Ensure it's the correct symbol type
			val functionSymbol = functionCall.symbol

			LOGGER.info("Resolved function symbol for 'function': ${functionSymbol.callableId}")

			// Check if the resolved function matches our target FQN (ClassId and Name)
			if (functionSymbol.callableId?.asSingleFqName() == KoreNames.KORE_FUNCTION_CLASS_ID &&
				functionSymbol.name == KoreNames.KORE_FUNCTION_NAME) {
				// Found the correct function, create the marker
				val iconBuilder = NavigationGutterIconBuilder.create(Icons.FUNCTION)
					.setAlignment(GutterIconRenderer.Alignment.CENTER)
					.setTooltipTitle("Function Definition")
					.setTooltipText("Kore function definition")
					// Target the first argument (the function name string literal) or fallback to the identifier element
					.setTarget(callExpression.valueArguments.firstOrNull()?.getArgumentExpression() ?: element)
					.createLineMarkerInfo(element)
				return iconBuilder
			}
		}

		return null
	}

	// Not needed for this provider, keep empty
	override fun collectSlowLineMarkers(
		elements: MutableList<out PsiElement>,
		result: MutableCollection<in LineMarkerInfo<*>>
	) {}

	companion object {
		// Consider using a dedicated logger for clarity if needed
		private val LOGGER = Logger.getInstance(FunctionGutterProvider::class.java)
	}
}
