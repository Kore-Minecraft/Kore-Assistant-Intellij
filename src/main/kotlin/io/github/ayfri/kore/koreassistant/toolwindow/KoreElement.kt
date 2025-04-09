package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.psi.PsiElement

// Data Classes for List Elements
sealed interface KoreElement {
	val name: String
	val element: PsiElement // The PSI element to navigate to
	val fileName: String
	val lineNumber: Int
}

data class KoreDataPackElement(
	override val name: String,
	override val element: PsiElement,
	override val fileName: String,
	override val lineNumber: Int,
) : KoreElement

data class KoreFunctionElement(
	override val name: String,
	override val element: PsiElement,
	override val fileName: String,
	override val lineNumber: Int,
) : KoreElement
