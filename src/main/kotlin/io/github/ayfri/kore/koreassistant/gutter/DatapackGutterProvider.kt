package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class DataPackGutterProvider : LineMarkerProviderDescriptor() {
	override fun getName() = "DatapackGutterProvider"

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

		// Quick check: function name must be 'dataPack'
		if (nameReferenceExpr.getReferencedNameAsName() != KoreNames.KORE_DATAPACK_NAME) {
			return null
		}

		// Use the Analysis API to resolve the call
		analyze(callExpression) { // 'this' is the KaSession
			// Use resolveToCall and get the symbol
			val functionCall = callExpression.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
			// Ensure it's the correct symbol type
			val functionSymbol = functionCall.symbol

			LOGGER.info("Resolved function symbol: ${functionSymbol.callableId} ${functionSymbol.callableId?.asSingleFqName()} ${functionSymbol.callableId?.asFqNameForDebugInfo()} ${functionSymbol.name}")

			// Check if the resolved function matches our target FQN (ClassId and Name)
			if (functionSymbol.callableId?.asSingleFqName() == KoreNames.KORE_DATAPACK_CLASS_ID &&
			    functionSymbol.name == KoreNames.KORE_DATAPACK_NAME) {
				// Found the correct function, create the marker
				val iconBuilder = NavigationGutterIconBuilder.create(KoreIcons.KORE)
					.setAlignment(GutterIconRenderer.Alignment.CENTER)
					.setTooltipTitle("DataPack Definition")
					.setTooltipText("Kore dataPack definition")
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
		private val LOGGER = Logger.getInstance(DataPackGutterProvider::class.java)
	}
}
