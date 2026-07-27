package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind

private fun element(
	kind: KoreDeclarationKind,
	name: String,
	namespace: String = "ns",
	directory: String? = null,
) = KoreElement(
	kind = kind,
	name = name,
	namespace = namespace,
	dataPackName = "my_pack",
	directory = directory,
	isDynamic = false,
	fileUrl = "file:///project/src/Main.kt",
	fileName = "Main.kt",
	offset = 42,
	lineNumber = 7,
)

private fun KoreTreeNode.copied() = copyValues().associate { it.title to it.value }

/** The values the hover shows and the context menu copies, all derived from the DTOs alone. */
class KoreNodeValuesTest : BasePlatformTestCase() {
	fun testBuildsResourceLocationsIncludingTheFunctionDirectory() {
		assertEquals("ns:foo", element(KoreDeclarationKind.ADVANCEMENT, "foo").resourceLocation)
		assertEquals("ns:foo", element(KoreDeclarationKind.FUNCTION, "foo").resourceLocation)
		assertEquals(
			"ns:utils/foo",
			element(KoreDeclarationKind.FUNCTION, "foo", directory = "utils").resourceLocation,
		)
	}

	fun testGivesADataPackNeitherLocationNorCommand() {
		val dataPack = element(KoreDeclarationKind.DATA_PACK, "my_pack")

		assertNull(dataPack.resourceLocation)
		assertNull(dataPack.command)
		assertEquals("my_pack/pack.mcmeta", dataPack.outputPath)
	}

	fun testBuildsCommandsForTheKindsThatHaveOne() {
		assertEquals("/function ns:foo", element(KoreDeclarationKind.FUNCTION, "foo").command)
		assertEquals("/loot give @s loot ns:foo", element(KoreDeclarationKind.LOOT_TABLE, "foo").command)
		assertEquals("/recipe give @s ns:foo", element(KoreDeclarationKind.SMELTING, "foo").command)
		assertEquals(
			"/advancement grant @s only ns:foo",
			element(KoreDeclarationKind.ADVANCEMENT, "foo").command,
		)
		assertEquals("/dialog show @s ns:foo", element(KoreDeclarationKind.NOTICE, "foo").command)
	}

	fun testLeavesCommandUnsetForKindsNoCommandTakes() {
		assertNull(element(KoreDeclarationKind.BIOME, "foo").command)
		assertNull(element(KoreDeclarationKind.TRIM_PATTERN, "foo").command)
	}

	fun testWritesDialogsIntoTheDialogFolder() {
		assertEquals("data/ns/dialog/welcome.json", element(KoreDeclarationKind.NOTICE, "welcome").outputPath)
	}

	fun testSpellsTheSourceLocationTheWayTheIdeLinksItBack() {
		assertEquals("Main.kt:7", element(KoreDeclarationKind.FUNCTION, "foo").sourceLocation)
	}

	fun testOffersAnElementRowItsOwnValuesFirst() {
		val copied = KoreElementNode(element(KoreDeclarationKind.FUNCTION, "foo", directory = "utils")).copied()

		assertEquals("ns:utils/foo", copied["Resource Location"])
		assertEquals("/function ns:utils/foo", copied["Command"])
		assertEquals("data/ns/function/utils/foo.mcfunction", copied["Output Path"])
		assertEquals("Main.kt:7", copied["Source Location"])
	}

	fun testCopiesEveryResourceLocationUnderAGroupingRow() {
		val children = listOf(
			element(KoreDeclarationKind.FUNCTION, "foo"),
			element(KoreDeclarationKind.ADVANCEMENT, "bar"),
		)
		val copied = KoreDataPackNode("my_pack", children).copied()

		assertEquals("my_pack", copied["Datapack Name"])
		assertEquals("ns:foo\nns:bar", copied["Resource Locations (2)"])
		assertEquals("data/ns/function/foo.mcfunction\ndata/ns/advancement/bar.json", copied["Output Paths (2)"])
	}

	fun testGivesGroupingRowsTheOutputFolderTheirSubtreeSharesResourceLocations() {
		val children = listOf(element(KoreDeclarationKind.ADVANCEMENT, "bar"))

		assertEquals("data/ns", KoreNamespaceNode("ns", "my_pack", children).copied()["Output Folder"])
		assertEquals("data/ns/advancement", KoreFolderNode("advancement", "ns", children).copied()["Output Folder"])
	}

	/** Whatever the row, the hover must come out as one self-contained HTML card. */
	fun testRendersAHoverCardForEveryRowKind() {
		val leaf = element(KoreDeclarationKind.FUNCTION, "foo")
		val nodes = listOf(
			KoreElementNode(leaf),
			KoreDataPackNode("my_pack", listOf(leaf)),
			KoreNamespaceNode("ns", "my_pack", listOf(leaf)),
			KoreFolderNode("function", "ns", listOf(leaf)),
			KoreFileNode("Main.kt", listOf(leaf)),
		)

		nodes.forEach { node ->
			val tooltip = node.tooltip()

			assertTrue("$node produced no HTML card", tooltip.startsWith("<html>") && tooltip.endsWith("</html>"))
			assertTrue("$node did not name what the row is", tooltip.contains(node.nodeKind))
		}
	}
}
