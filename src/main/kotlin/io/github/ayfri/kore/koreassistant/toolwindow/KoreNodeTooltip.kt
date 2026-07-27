package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.UIUtil
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind

// Long value lists turn a hover into a wall, so they are cut at this many entries plus a "+N more" tail.
private const val MAX_LISTED = 8

/**
 * The hover card: a monospace definition block, a grey line naming the row, then a sections table.
 *
 * Hand-rolled HTML rather than `DocumentationMarkup`, whose classes need the documentation component's
 * stylesheet that a Swing tooltip never loads. Colors come from the LAF per call, so themes switch cleanly.
 */
fun KoreTreeNode.tooltip(): String {
	val grey = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
	val codeBackground = ColorUtil.toHtmlColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)

	return buildString {
		append("<html><body>")

		append("<table width='100%' cellpadding='6' cellspacing='0'><tr><td bgcolor='")
		append(codeBackground).append("'><code>").append(definition()).append("</code></td></tr></table>")

		append("<table cellpadding='2' cellspacing='0'>")
		append("<tr><td colspan='2'><font color='").append(grey).append("'>").append(subtitle().escaped())
		append("</font></td></tr>")
		sections().forEach { (label, value) ->
			append("<tr><td valign='top'><font color='").append(grey).append("'>").append(label)
			append(":</font></td><td valign='top'>&nbsp;").append(value).append("</td></tr>")
		}
		append("</table>")

		append("</body></html>")
	}
}

/** The monospace first line: the row spelled the way it is written in Kotlin, or as a path. */
private fun KoreTreeNode.definition() = when (this) {
	is KoreElementNode -> "${element.kind.builderName}(<b>${element.name.escaped()}</b>)"
	is KoreDataPackNode -> "dataPack(<b>${label.escaped()}</b>)"
	is KoreNamespaceNode -> "namespace = <b>${label.escaped()}</b>"
	is KoreFolderNode -> "${outputPath.substringBeforeLast('/').escaped()}/<b>${label.escaped()}</b>"
	is KoreFileNode -> "<b>${label.escaped()}</b>"
}

private fun KoreTreeNode.subtitle() = when {
	this is KoreElementNode && element.isDynamic -> "$nodeKind, name built at runtime"
	else -> nodeKind
}

private fun KoreTreeNode.sections(): List<Pair<String, String>> = when (this) {
	is KoreElementNode -> buildList {
		element.resourceLocation?.let { add("Location" to it.code()) }
		add("Output" to element.outputPath.code())
		element.command?.let { add("Command" to it.code()) }
		add("Datapack" to element.dataPackName.escaped())
		if (element.kind != KoreDeclarationKind.DATA_PACK) add("Namespace" to element.namespace.escaped())
		add("Declared in" to element.sourceLocation.escaped())
	}

	is KoreDataPackNode -> listOf(
		"Output" to "$label/".code(),
		"Elements" to elements.size.toString(),
		"Namespaces" to elements.map(KoreElement::namespace).listed(),
		"Kinds" to elements.map { it.kind.displayName }.listed(),
		"Files" to elements.map(KoreElement::fileName).listed(),
	)

	is KoreNamespaceNode -> listOf(
		"Output" to "data/$label/".code(),
		"Datapack" to dataPackName.escaped(),
		"Elements" to elements.size.toString(),
		"Folders" to elements.mapNotNull { it.kind.resourceFolder }.listed(),
	)

	is KoreFolderNode -> listOf(
		"Output" to "$outputPath/".code(),
		"Namespace" to namespace.escaped(),
		"Elements" to elements.size.toString(),
		"Kinds" to elements.map { it.kind.displayName }.listed(),
	)

	is KoreFileNode -> listOf(
		"Path" to (elements.firstOrNull()?.presentablePath?.escaped() ?: label.escaped()),
		"Elements" to elements.size.toString(),
		"Datapacks" to elements.map(KoreElement::dataPackName).listed(),
	)
}

/** Distinct values, cut short so one crowded namespace cannot stretch the card off-screen. */
private fun List<String>.listed(): String {
	val distinct = distinct().sorted()
	val shown = distinct.take(MAX_LISTED).joinToString(", ") { it.escaped() }

	return if (distinct.size > MAX_LISTED) "$shown, +${distinct.size - MAX_LISTED} more" else shown
}

private fun String.code() = "<code>${escaped()}</code>"

private fun String.escaped(): String = StringUtil.escapeXmlEntities(this)
