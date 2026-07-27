package io.github.ayfri.kore.koreassistant.index

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val MAIN_KT = """
	import io.github.ayfri.kore.dataPack

	const val PREFIX = "generated/"

	fun main() {
		dataPack("rotten-flesh-to-leather") {
			recipes {
				smelting("browndye_smelt") {
					experience = 0.1
				}
			}

			function("helper", namespace = "other", directory = "utils") { }
			function("positional", "ns2", "dir2") { }
			function(directory = "reordered_dir", name = "reordered") { }

			advancement("root") {
				namespace = "adv_ns"
			}

			predicate(fileName = PREFIX + "concatenated") { }

			listOf("oak", "birch").forEach { leaf ->
				lootTable("blocks/${'$'}leaf") {
					namespace = "minecraft"
				}
			}
		}
	}
""".trimIndent()

class KoreDeclarationIndexTest : BasePlatformTestCase() {
	private fun index(): Map<String, KoreDeclarationData> {
		myFixture.configureByText("main.kt", MAIN_KT)
		return KoreDeclarationIndex.findAll(project, GlobalSearchScope.allScope(project))
			.associate { (_, data) -> data.name to data }
	}

	fun testIndexesEveryDeclarationKind() {
		val indexed = index()

		assertEquals(
			setOf(
				"rotten-flesh-to-leather",
				"browndye_smelt",
				"helper",
				"positional",
				"reordered",
				"root",
				"generated/concatenated",
				"blocks/\$leaf",
			),
			indexed.keys,
		)
		assertEquals(KoreDeclarationKind.DATA_PACK, indexed.getValue("rotten-flesh-to-leather").kind)
		assertEquals(KoreDeclarationKind.SMELTING, indexed.getValue("browndye_smelt").kind)
		assertEquals(KoreDeclarationKind.FUNCTION, indexed.getValue("helper").kind)
		assertEquals(KoreDeclarationKind.ADVANCEMENT, indexed.getValue("root").kind)
	}

	fun testAttachesEveryDeclarationToItsDataPack() {
		val indexed = index()

		assertEquals("rotten-flesh-to-leather", indexed.getValue("rotten-flesh-to-leather").dataPackName)
		assertEquals("rotten-flesh-to-leather", indexed.getValue("browndye_smelt").dataPackName)
		assertEquals("rotten-flesh-to-leather", indexed.getValue("root").dataPackName)
	}

	fun testReadsNamespaceAndDirectoryFromNamedArguments() {
		val helper = index().getValue("helper")

		assertEquals("other", helper.namespace)
		assertEquals("utils", helper.directory)
	}

	fun testReadsNamespaceAndDirectoryFromPositionalArguments() {
		val positional = index().getValue("positional")

		assertEquals("ns2", positional.namespace)
		assertEquals("dir2", positional.directory)
	}

	fun testReadsNamespaceAssignedInTheBuilderBlock() {
		assertEquals("adv_ns", index().getValue("root").namespace)
	}

	fun testLeavesNamespaceUnsetWhenTheDeclarationDoesNotSpellItOut() {
		assertNull(index().getValue("browndye_smelt").namespace)
	}

	fun testReadsTheNameFromAReorderedNamedArgument() {
		val reordered = index().getValue("reordered")

		assertEquals(KoreDeclarationKind.FUNCTION, reordered.kind)
		assertEquals("reordered_dir", reordered.directory)
	}

	fun testFollowsConstantsAndConcatenationDeclaredInTheSameFile() {
		val concatenated = index().getValue("generated/concatenated")

		assertEquals(KoreDeclarationKind.PREDICATE, concatenated.kind)
		assertFalse("A fully resolved name must not be marked dynamic", concatenated.isDynamic)
	}

	fun testKeepsInterpolatedNamesAsTemplates() {
		val inLoop = index().getValue("blocks/\$leaf")

		assertEquals(KoreDeclarationKind.LOOT_TABLE, inLoop.kind)
		assertEquals("minecraft", inLoop.namespace)
		assertTrue("An interpolated name is only known at runtime", inLoop.isDynamic)
		assertEquals("rotten-flesh-to-leather", inLoop.dataPackName)
	}
}
