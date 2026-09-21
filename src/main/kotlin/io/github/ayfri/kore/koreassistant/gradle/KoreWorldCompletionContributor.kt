package io.github.ayfri.kore.koreassistant.gradle

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.psi.calleeName
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.plugins.gradle.util.GradleConstants

private const val KORE_EXTENSION = "kore"
private const val WORLDS_PROPERTY = "worlds"
private const val QUALIFIED_WORLDS_PROPERTY = "$KORE_EXTENSION.$WORLDS_PROPERTY"

/** Completes the world names `koreLink` would find, inside `worlds = listOf("...")` / `worlds.add("...")` of a `kore { }` block. */
class KoreWorldCompletionContributor : CompletionContributor() {
	override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
		if (!parameters.originalFile.name.endsWith(GradleConstants.KOTLIN_DSL_SCRIPT_EXTENSION)) return
		val position = parameters.position
		val string = position.parent?.parent as? KtStringTemplateExpression ?: return
		if (!string.isKoreWorldsValue()) return

		// World names hold spaces, so the prefix is the whole literal before the caret, not the alphanumeric tail.
		val prefix = position.text.substring(0, parameters.offset - position.textRange.startOffset)
		val worlds = result.withPrefixMatcher(prefix)
		MinecraftWorlds.list(MinecraftWorlds.defaultDirectory()).forEach {
			worlds.addElement(LookupElementBuilder.create(it).withIcon(KoreIcons.KORE).withTypeText("Minecraft world"))
		}
		worlds.stopHere()
	}
}

/** Whether this literal is a value of `worlds` inside `kore { }`, or of `kore.worlds` anywhere in the script. */
fun KtStringTemplateExpression.isKoreWorldsValue(): Boolean {
	var owner: String? = null
	var current: PsiElement? = parent
	while (current != null) {
		when (current) {
			is KtBinaryExpression -> owner = current.left?.text
			is KtDotQualifiedExpression -> owner = current.receiverExpression.text
			is KtCallExpression -> if (current.calleeName() == KORE_EXTENSION) return owner == WORLDS_PROPERTY
		}
		if (owner == QUALIFIED_WORLDS_PROPERTY) return true
		current = current.parent
	}
	return false
}
