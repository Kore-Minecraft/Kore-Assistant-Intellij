package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.psi.PsiElement
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.asCalleeOf
import io.github.ayfri.kore.koreassistant.psi.resolvesTo
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtCallExpression

class DataPackGutterProvider : KoreCallGutterProvider() {
	override fun declarationAt(element: PsiElement) = element.asCalleeOf(KoreNames.DATA_PACK.shortName())?.let { it to KoreDeclarationKind.DATA_PACK }

	override fun KaSession.confirms(call: KtCallExpression, kind: KoreDeclarationKind) = resolvesTo(call, KoreNames.DATA_PACK)
}
