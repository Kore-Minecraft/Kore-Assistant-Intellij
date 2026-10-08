package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.psi.PsiElement
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.asCallee
import io.github.ayfri.kore.koreassistant.psi.hasFunctionBody
import io.github.ayfri.kore.koreassistant.psi.koreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.resolvesToKoreDeclaration
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtCallExpression

/** Every declaration the two dedicated providers leave out: advancements, loot tables, tags, recipes, `load { }`, `tick { }`... */
class DeclarationGutterProvider : KoreCallGutterProvider() {
	override fun declarationAt(element: PsiElement): Pair<KtCallExpression, KoreDeclarationKind>? {
		val call = element.asCallee() ?: return null
		val kind = call.koreDeclarationKind()?.takeUnless { it == KoreDeclarationKind.DATA_PACK || it == KoreDeclarationKind.FUNCTION } ?: return null
		// Same rule as the indexer: a body-less call of the function family is a command, not a declaration.
		if (kind.isFunction && !call.hasFunctionBody()) return null
		return call to kind
	}

	override fun KaSession.confirms(call: KtCallExpression) = resolvesToKoreDeclaration(call)
}
