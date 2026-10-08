package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.parents
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.psi.declarationNameArgument
import io.github.ayfri.kore.koreassistant.psi.koreCallAt
import io.github.ayfri.kore.koreassistant.references.KoreFunctionTarget
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.awt.datatransfer.StringSelection

/** Carries the row the context menu was opened on, so every action below reads its target the standard way. */
val KORE_NODE_KEY = DataKey.create<KoreTreeNode>("io.github.ayfri.kore.koreassistant.koreNode")

/** Longest preview shown next to a menu entry; longer values keep their head and tail around an ellipsis. */
private const val PREVIEW_LENGTH = 48
private const val PREVIEW_TAIL_LENGTH = 14

/** One entry of the Copy submenu: what it is called, and what it puts on the clipboard. */
data class KoreCopyValue(val title: String, val value: String, val previewed: Boolean = true)

/** The tree's right-click menu. Jump to Source and Find Usages need one declaration, so they stay on leaves. */
fun createKoreNodePopupGroup(navigate: () -> Unit): DefaultActionGroup = DefaultActionGroup().apply {
	add(JumpToSourceAction(navigate))
	add(ActionManager.getInstance().getAction("FindUsages"))
	addSeparator()
	add(CopyKoreValuesAction())
}

/** What a row puts on the clipboard, in menu order, skipping repeats: a function without directory has the same name and path. */
fun KoreTreeNode.copyValues(): List<KoreCopyValue> = rowValues().filter { it.value.isNotEmpty() }.distinctBy(KoreCopyValue::value)

private fun KoreTreeNode.rowValues(): List<KoreCopyValue> = when (this) {
	is KoreElementNode -> element.copyValues()

	is KoreDataPackNode -> listOfNotNull(label.takeIf { it != UNKNOWN_DATA_PACK }?.let { KoreCopyValue("Datapack Name", it) }) +
		subtreeValues()

	is KoreNamespaceNode -> listOf(
		KoreCopyValue("Namespace", label),
		KoreCopyValue("Output Folder", "data/$label"),
	) + subtreeValues()

	is KoreFolderNode -> listOf(
		KoreCopyValue("Folder Name", label),
		KoreCopyValue("Output Folder", outputPath),
	) + subtreeValues()

	is KoreFileNode -> listOf(
		KoreCopyValue("File Name", label),
		KoreCopyValue("Absolute File Path", elements.firstOrNull()?.presentablePath ?: label),
	) + subtreeValues()
}

private fun KoreElement.copyValues(): List<KoreCopyValue> {
	val location = resourceLocation
	val dataPackOutputPath = when {
		kind == KoreDeclarationKind.DATA_PACK || dataPackName == UNKNOWN_DATA_PACK -> null
		else -> "$dataPackName/$outputPath"
	}

	return listOf(
		"Resource Location" to location,
		"Namespaced ID" to location?.removePrefix("#"),
		"Namespace" to namespace.takeIf { location != null },
		"Name" to name,
		"Path" to location?.substringAfter(':'),
		"Command" to command,
		kotlinSnippet(),
		"Output Path" to outputPath,
		"Output Path with Datapack" to dataPackOutputPath,
		"Resource Folder" to kind.resourceFolder,
		"Source Location" to sourceLocation,
		"Absolute File Path" to presentablePath,
	).mapNotNull { (title, value) -> value?.let { KoreCopyValue(title, it) } }
}

/** The `function("ns:x")` command calling a function or function tag, else the builder call declaring the element. */
private fun KoreElement.kotlinSnippet(): Pair<String, String?> = when {
	kind.isFunction -> "Kotlin Call" to "function(\"$resourceLocation\")"
	kind == KoreDeclarationKind.FUNCTION_TAG -> "Kotlin Call" to "function(\"${resourceLocation?.removePrefix("#")}\", group = true)"
	else -> "Kotlin Declaration" to "${kind.builderName}(\"$name\")"
}

/** The bulk entries over every element underneath, deduplicated. */
private fun KoreTreeNode.subtreeValues(): List<KoreCopyValue> = listOfNotNull(
	aggregate("Namespace", "Namespaces", elements.filter { it.resourceLocation != null }.map(KoreElement::namespace)),
	aggregate("Resource Location", "Resource Locations", elements.mapNotNull(KoreElement::resourceLocation)),
	aggregate("Command", "Commands", elements.mapNotNull(KoreElement::command)),
	aggregate("Output Path", "Output Paths", elements.map(KoreElement::outputPath)),
)

/** One line per distinct value, counted in the title; a single value reads like a leaf's entry and keeps its preview. */
private fun aggregate(singular: String, plural: String, values: List<String>): KoreCopyValue? {
	val distinct = values.distinct()

	return when (distinct.size) {
		0 -> null
		1 -> KoreCopyValue(singular, distinct.single())
		else -> KoreCopyValue("$plural (${distinct.size})", distinct.joinToString("\n"), previewed = false)
	}
}

/**
 * What Find Usages targets: a function's `function("x")` calls, else the Kotlin declaration around the call, since the call
 * itself has no usages to find. A lambda is a `KtNamedDeclaration` too, but never has any.
 */
fun KoreElement.findDeclaration(project: Project): PsiElement? {
	val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl)?.takeIf { it.isValid } ?: return null
	val ktFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return null
	val call = ktFile.koreCallAt(offset, kind.builderName) ?: return null

	if (kind.isFunction || kind == KoreDeclarationKind.FUNCTION_TAG) {
		call.declarationNameArgument()?.let { return KoreFunctionTarget(it, kind == KoreDeclarationKind.FUNCTION_TAG) }
	}
	return call.parents(false).filterIsInstance<KtNamedDeclaration>().firstOrNull { it !is KtFunctionLiteral }
}

private class JumpToSourceAction(private val navigate: () -> Unit) :
	AnAction("Jump to Source", "Open the declaration in the editor", AllIcons.Actions.EditSource), DumbAware {
	override fun actionPerformed(e: AnActionEvent) = navigate()

	override fun update(e: AnActionEvent) {
		e.presentation.isEnabledAndVisible = e.getData(KORE_NODE_KEY)?.element != null
	}

	override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/**
 * A plain action rather than a submenu, because the greyed previews come from `SECONDARY_TEXT`, which only the
 * list-popup renderer honours - a Swing submenu would drop them. Same move as the IDE's Copy Path/Reference
 * entry. Entries are built here, since which ones make sense depends on the row the menu was opened on.
 */
private class CopyKoreValuesAction :
	AnAction("Copy", "Copy this row's ids, paths and Kotlin snippets", AllIcons.Actions.Copy), DumbAware {
	override fun actionPerformed(e: AnActionEvent) {
		val node = e.getData(KORE_NODE_KEY) ?: return
		val group = DefaultActionGroup(node.copyValues().map(::CopyKoreValueAction))

		JBPopupFactory.getInstance()
			.createActionGroupPopup("Copy", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
			.showInBestPositionFor(e.dataContext)
	}

	override fun update(e: AnActionEvent) {
		e.presentation.isEnabledAndVisible = e.getData(KORE_NODE_KEY) != null
	}

	override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class CopyKoreValueAction(private val target: KoreCopyValue) : AnAction(), DumbAware {
	init {
		// Names and paths are full of underscores, which the menu would otherwise eat as mnemonics.
		templatePresentation.setText(target.title, false)
		templatePresentation.description = "Copy ${target.title.lowercase()} to the clipboard"
		templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, preview())
	}

	override fun actionPerformed(e: AnActionEvent) =
		CopyPasteManager.getInstance().setContents(StringSelection(target.value))

	/** What would land on the clipboard, shown greyed at the end of the row. */
	private fun preview() = when {
		target.previewed -> StringUtil.shortenTextWithEllipsis(target.value, PREVIEW_LENGTH, PREVIEW_TAIL_LENGTH)
		else -> null
	}

	override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
