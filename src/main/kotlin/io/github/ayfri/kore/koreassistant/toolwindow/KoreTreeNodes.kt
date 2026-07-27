package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.icons.AllIcons
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode

/** How the flat element list is turned into a tree. [OUTPUT] mirrors the generated datapack layout. */
enum class KoreGroupBy(val displayName: String) {
	OUTPUT("Output Structure"),
	FILE("Source File"),
	NONE("Flat List"),
}

enum class KoreSortBy(val displayName: String) {
	NAME("Sort by Name"),
	KIND("Sort by Kind"),
	NAMESPACE("Sort by Namespace"),
	DECLARATION("Sort by Declaration Order"),
}

enum class KoreSortOrder { ASCENDING, DESCENDING }

/**
 * User object of every tree row. Only [KoreElementNode] carries something to navigate to, but every row
 * carries the [elements] under it, so grouping rows can answer the same hover and Copy questions a leaf does.
 */
sealed interface KoreTreeNode {
	val label: String
	val icon: Icon
	val secondaryText: String?
	val elements: List<KoreElement>

	/** What the row *is*, shown as the grey line under the hover's definition block. */
	val nodeKind: String
	val element: KoreElement? get() = null
}

data class KoreDataPackNode(override val label: String, override val elements: List<KoreElement>) : KoreTreeNode {
	override val icon get() = KoreDeclarationKind.DATA_PACK.icon
	override val secondaryText get() = elements.size.elementCount()
	override val nodeKind get() = "Datapack"
}

data class KoreNamespaceNode(
	override val label: String,
	val dataPackName: String,
	override val elements: List<KoreElement>,
) : KoreTreeNode {
	override val icon get() = AllIcons.Nodes.Package
	override val secondaryText get() = elements.size.elementCount()
	override val nodeKind get() = "Namespace"
}

data class KoreFolderNode(
	override val label: String,
	val namespace: String,
	override val elements: List<KoreElement>,
) : KoreTreeNode {
	override val icon get() = AllIcons.Nodes.Folder
	override val secondaryText get() = elements.size.toString()
	override val nodeKind get() = "Resource Folder"

	/** The output prefix every element under this folder shares. */
	val outputPath get() = "data/$namespace/$label"
}

data class KoreFileNode(override val label: String, override val elements: List<KoreElement>) : KoreTreeNode {
	override val icon get() = AllIcons.FileTypes.Any_type
	override val secondaryText get() = elements.size.toString()
	override val nodeKind get() = "Source File"
}

data class KoreElementNode(override val element: KoreElement) : KoreTreeNode {
	override val label get() = element.name
	override val icon get() = element.kind.icon
	override val secondaryText get() = "${element.fileName}:${element.lineNumber}"
	override val elements get() = listOf(element)
	override val nodeKind get() = element.kind.displayName
}

private fun Int.elementCount() = "$this element${if (this == 1) "" else "s"}"

/** Text matched against the filter field, so filtering finds an element by name, namespace or output path. */
fun KoreElement.matches(filter: String) = filter.isEmpty()
	|| name.contains(filter, ignoreCase = true)
	|| namespace.contains(filter, ignoreCase = true)
	|| outputPath.contains(filter, ignoreCase = true)

fun buildKoreTree(
	elements: List<KoreElement>,
	groupBy: KoreGroupBy,
	sortBy: KoreSortBy,
	sortOrder: KoreSortOrder,
): DefaultMutableTreeNode {
	val root = DefaultMutableTreeNode()
	val sorted = elements.sortedWith(elementComparator(sortBy, sortOrder))

	when (groupBy) {
		// `dataPack("x") { }` is the container, not a resource, so it becomes the root row of its own subtree.
		KoreGroupBy.OUTPUT -> {
			val resources = sorted.filter { it.kind != KoreDeclarationKind.DATA_PACK }
			val dataPackNames = sorted.map(KoreElement::dataPackName).distinct().sorted()

			for (dataPackName in dataPackNames) {
				val own = resources.filter { it.dataPackName == dataPackName }
				val dataPackNode = DefaultMutableTreeNode(KoreDataPackNode(dataPackName, own))

				for ((namespace, inNamespace) in own.groupBy(KoreElement::namespace).toSortedMap()) {
					val namespaceNode = DefaultMutableTreeNode(KoreNamespaceNode(namespace, dataPackName, inNamespace))

					for ((folder, inFolder) in inNamespace.groupBy { it.kind.resourceFolder.orEmpty() }.toSortedMap()) {
						val folderNode = DefaultMutableTreeNode(KoreFolderNode(folder, namespace, inFolder))
						inFolder.forEach { folderNode.add(DefaultMutableTreeNode(KoreElementNode(it))) }
						namespaceNode.add(folderNode)
					}

					dataPackNode.add(namespaceNode)
				}

				root.add(dataPackNode)
			}
		}

		KoreGroupBy.FILE -> for ((fileName, inFile) in sorted.groupBy(KoreElement::fileName).toSortedMap()) {
			val fileNode = DefaultMutableTreeNode(KoreFileNode(fileName, inFile))
			inFile.forEach { fileNode.add(DefaultMutableTreeNode(KoreElementNode(it))) }
			root.add(fileNode)
		}

		KoreGroupBy.NONE -> sorted.forEach { root.add(DefaultMutableTreeNode(KoreElementNode(it))) }
	}

	return root
}

private fun elementComparator(sortBy: KoreSortBy, sortOrder: KoreSortOrder): Comparator<KoreElement> {
	val byName = compareBy(String.CASE_INSENSITIVE_ORDER, KoreElement::name)
		.thenBy(String.CASE_INSENSITIVE_ORDER, KoreElement::fileName)

	val comparator = when (sortBy) {
		KoreSortBy.NAME -> byName

		KoreSortBy.KIND -> compareBy<KoreElement, String>(String.CASE_INSENSITIVE_ORDER) { it.kind.displayName }.then(byName)

		KoreSortBy.NAMESPACE -> compareBy(String.CASE_INSENSITIVE_ORDER, KoreElement::namespace).then(byName)

		// Offset rather than line number, so two declarations on the same line keep their source order.
		KoreSortBy.DECLARATION -> compareBy(String.CASE_INSENSITIVE_ORDER, KoreElement::fileName)
			.thenBy { it.offset }
	}

	return if (sortOrder == KoreSortOrder.ASCENDING) comparator else comparator.reversed()
}
