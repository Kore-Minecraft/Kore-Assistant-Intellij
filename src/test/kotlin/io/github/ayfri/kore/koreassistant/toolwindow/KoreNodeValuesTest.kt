package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind

private fun element(
	kind: KoreDeclarationKind,
	name: String,
	namespace: String = "ns",
	directory: String? = null,
	dataPackName: String = "my_pack",
) = KoreElement(
	kind = kind,
	name = name,
	namespace = namespace,
	dataPackName = dataPackName,
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

	fun testPrefixesTagLocationsWithAHashAndNestsTheirPath() {
		val tag = element(KoreDeclarationKind.FUNCTION_TAG, "hooks")

		assertEquals("#ns:hooks", tag.resourceLocation)
		assertEquals("/function #ns:hooks", tag.command)
		assertEquals("data/ns/tags/function/hooks.json", tag.outputPath)
		assertNull(element(KoreDeclarationKind.BLOCK_TAG, "logs").command)
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
		assertEquals("ns", copied["Namespace"])
		assertEquals("foo", copied["Name"])
		assertEquals("utils/foo", copied["Path"])
		assertEquals("function(\"ns:utils/foo\")", copied["Kotlin Call"])
		assertEquals("my_pack/data/ns/function/utils/foo.mcfunction", copied["Output Path with Datapack"])
		assertEquals("function", copied["Resource Folder"])
	}

	fun testDropsEntriesCopyingTheSameStringAsAnEarlierOne() {
		val copied = KoreElementNode(element(KoreDeclarationKind.FUNCTION, "foo")).copied()

		assertEquals("foo", copied["Name"])
		assertNull("a path equal to the name repeats it", copied["Path"])
		assertNull("an id equal to the resource location repeats it", copied["Namespaced ID"])

		listOf(
			KoreElementNode(element(KoreDeclarationKind.FUNCTION_TAG, "hooks")),
			KoreNamespaceNode("ns", "my_pack", listOf(element(KoreDeclarationKind.ADVANCEMENT, "bar"))),
		).forEach { node ->
			val values = node.copyValues().map(KoreCopyValue::value)
			assertEquals("$node offers the same value twice", values.distinct(), values)
		}
	}

	fun testSpellsATagWithAndWithoutItsHash() {
		val copied = KoreElementNode(element(KoreDeclarationKind.FUNCTION_TAG, "hooks")).copied()

		assertEquals("#ns:hooks", copied["Resource Location"])
		assertEquals("ns:hooks", copied["Namespaced ID"])
		assertEquals("function(\"ns:hooks\", group = true)", copied["Kotlin Call"])
		assertEquals("tags/function", copied["Resource Folder"])
	}

	fun testCopiesTheBuilderCallDeclaringOtherKinds() {
		val copied = KoreElementNode(element(KoreDeclarationKind.LOOT_TABLE, "chest")).copied()

		assertEquals("lootTable(\"chest\")", copied["Kotlin Declaration"])
		assertNull(copied["Kotlin Call"])
	}

	fun testGivesADataPackRowNoIdButItsDeclaration() {
		val copied = KoreElementNode(element(KoreDeclarationKind.DATA_PACK, "my_pack")).copied()

		assertNull(copied["Resource Location"])
		assertNull(copied["Namespace"])
		assertNull(copied["Resource Folder"])
		assertNull("pack.mcmeta already sits in the datapack folder", copied["Output Path with Datapack"])
		assertEquals("dataPack(\"my_pack\")", copied["Kotlin Declaration"])
		assertEquals("my_pack/pack.mcmeta", copied["Output Path"])
	}

	fun testSkipsTheDatapackFolderWhenTheDatapackIsUnknown() {
		val orphan = element(KoreDeclarationKind.ADVANCEMENT, "bar", dataPackName = UNKNOWN_DATA_PACK)

		assertNull(KoreElementNode(orphan).copied()["Output Path with Datapack"])
		assertNull(KoreDataPackNode(UNKNOWN_DATA_PACK, listOf(orphan)).copied()["Datapack Name"])
	}

	fun testCopiesEveryResourceLocationUnderAGroupingRow() {
		val children = listOf(
			element(KoreDeclarationKind.FUNCTION, "foo"),
			element(KoreDeclarationKind.ADVANCEMENT, "bar"),
		)
		val copied = KoreDataPackNode("my_pack", children).copied()

		assertEquals("my_pack", copied["Datapack Name"])
		assertEquals("ns", copied["Namespace"])
		assertEquals("ns:foo\nns:bar", copied["Resource Locations (2)"])
		assertEquals("/function ns:foo\n/advancement grant @s only ns:bar", copied["Commands (2)"])
		assertEquals("data/ns/function/foo.mcfunction\ndata/ns/advancement/bar.json", copied["Output Paths (2)"])
	}

	fun testListsDistinctNamespacesAndPreviewsASingleValue() {
		val children = listOf(
			element(KoreDeclarationKind.FUNCTION, "foo"),
			element(KoreDeclarationKind.FUNCTION, "foo", namespace = "other"),
			element(KoreDeclarationKind.FUNCTION, "bar", namespace = "other"),
		)
		val fileValues = KoreFileNode("Main.kt", children).copyValues()

		assertEquals("ns\nother", fileValues.single { it.title == "Namespaces (2)" }.value)
		assertFalse(fileValues.single { it.title == "Resource Locations (3)" }.previewed)

		val single = KoreFolderNode("advancement", "ns", listOf(element(KoreDeclarationKind.ADVANCEMENT, "bar"))).copyValues()
		val location = single.single { it.title == "Resource Location" }

		assertEquals("ns:bar", location.value)
		assertTrue(location.previewed)
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
