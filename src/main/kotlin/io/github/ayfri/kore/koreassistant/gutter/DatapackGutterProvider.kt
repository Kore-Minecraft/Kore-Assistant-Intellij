package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

data object KoreNames {
	val KORE_DATAPACK_CLASS_ID = FqName("io.github.ayfri.kore.dataPack")
	val KORE_DATAPACK_NAME = Name.identifier("dataPack")
}

data object Icons {
	// Use java.classLoader to ensure compatibility across different environments
	val ICON = IconLoader.getIcon("/images/kore-white.svg", DataPackGutterProvider::class.java.classLoader)
}

class DataPackGutterProvider : LineMarkerProvider {

	companion object {

		private val LOGGER = Logger.getInstance(DataPackGutterProvider::class.java)
	}

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
			LOGGER.info("Resolving function call: ${callExpression.resolveToCall()?.successfulFunctionCallOrNull()?.symbol}")
			LOGGER.info("Resolving function call: ${callExpression.mainReference.resolveToSymbol()} ${callExpression.mainReference.resolveToSymbol()?.importableFqName}")
			val functionCall = callExpression.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
			// Ensure it's the correct symbol type
			val functionSymbol = functionCall.symbol

			LOGGER.info("Resolved function symbol: ${functionSymbol.callableId} ${functionSymbol.callableId?.asSingleFqName()} ${functionSymbol.callableId?.asFqNameForDebugInfo()} ${functionSymbol.name}")

			// Check if the resolved function matches our target FQN (ClassId and Name)
			if (functionSymbol.callableId?.asSingleFqName() == KoreNames.KORE_DATAPACK_CLASS_ID &&
			    functionSymbol.name == KoreNames.KORE_DATAPACK_NAME) {
				// Found the correct function, create the marker
				return LineMarkerInfo(
					element, // Anchor to the identifier PsiElement
					element.textRange,
					Icons.ICON,
					{ "Kore dataPack definition" }, // Tooltip text
					null, // Navigation handler (none for now)
					GutterIconRenderer.Alignment.CENTER,
					{ "Kore dataPack" } // Accessibility name
				)
			}
		}

		return null
	}

	// Not needed for this provider, keep empty
	override fun collectSlowLineMarkers(
		elements: MutableList<out PsiElement>,
		result: MutableCollection<in LineMarkerInfo<*>>
	) {}
}
