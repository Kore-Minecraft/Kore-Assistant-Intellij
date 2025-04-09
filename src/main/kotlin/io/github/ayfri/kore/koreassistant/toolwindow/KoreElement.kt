package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.psi.PsiElement

sealed class KoreElement(
	open val name: String,
	open val element: PsiElement
)

data class KoreDataPackElement(
	override val name: String, // Usually namespace:name
	override val element: PsiElement // The call expression or a relevant argument
) : KoreElement(name, element)

data class KoreFunctionElement(
	override val name: String, // Usually namespace:path/to/function
	override val element: PsiElement // The call expression or the name argument
) : KoreElement(name, element)
