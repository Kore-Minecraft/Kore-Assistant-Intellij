package io.github.ayfri.kore.koreassistant.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.RenameableFakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.IncorrectOperationException
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.declarationNameArgument
import io.github.ayfri.kore.koreassistant.psi.koreCallAt
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * A declared Kore function or function tag, standing for the `function("name") { }` call that declares it. A call is not a
 * named declaration, so this façade is what gives the platform something to navigate to, find usages of and rename.
 * Two façades over the same name argument are equal, which is how `isReferenceTo` matches them.
 */
class KoreFunctionTarget(private val nameExpression: KtExpression, val isTag: Boolean) : RenameableFakePsiElement(nameExpression) {
	private val literal get() = nameExpression as? KtStringTemplateExpression

	/** The last `/` segment, the part a rename changes: a directory is a separate argument of the declaration. */
	override fun getName(): String = (literal?.let { ElementManipulators.getValueText(it) } ?: nameExpression.text).substringAfterLast('/')

	override fun getTypeName() = if (isTag) "Kore function tag" else "Kore function"

	override fun getIcon() = KoreIcons.FUNCTION

	override fun getNavigationElement() = nameExpression

	override fun getTextOffset() = nameExpression.textOffset

	override fun getTextRange() = nameExpression.textRange

	override fun isValid() = nameExpression.isValid

	/** A name read from a constant navigates but does not rename: the text to change belongs to the constant, not this call. */
	override fun isWritable() = literal?.hasInterpolation() == false && nameExpression.isWritable

	override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)

	override fun setName(name: String): PsiElement {
		val literal = literal?.takeIf { isWritable } ?: throw IncorrectOperationException("Only a function named by a string literal can be renamed")
		val valueRange = ElementManipulators.getValueTextRange(literal)
		val nameStart = valueRange.startOffset + valueRange.substring(literal.text).lastIndexOf('/') + 1
		ElementManipulators.handleContentChange(literal, TextRange(nameStart, valueRange.endOffset), name)
		return this
	}

	override fun equals(other: Any?) = other is KoreFunctionTarget && other.nameExpression == nameExpression

	override fun hashCode() = nameExpression.hashCode()

	companion object {
		/** The functions (or function tags, for [group]) declared as `namespace:path`, see [KoreDeclarationIndex.findByLocation]. */
		fun find(project: Project, namespace: String?, path: String, group: Boolean): List<KoreFunctionTarget> {
			val psiManager = PsiManager.getInstance(project)
			val kinds = { kind: KoreDeclarationKind -> if (group) kind == KoreDeclarationKind.FUNCTION_TAG else kind.isFunction }

			return KoreDeclarationIndex.findByLocation(namespace, path, GlobalSearchScope.projectScope(project), kinds).mapNotNull { (file, data) ->
				val ktFile = psiManager.findFile(file) as? KtFile ?: return@mapNotNull null
				ktFile.koreCallAt(data.offset, data.kind.builderName)?.declarationNameArgument()?.let { KoreFunctionTarget(it, group) }
			}.distinct()
		}
	}
}
