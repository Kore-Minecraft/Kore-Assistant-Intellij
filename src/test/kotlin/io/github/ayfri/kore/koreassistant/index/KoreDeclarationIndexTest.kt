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

			dialogs {
				notice("welcome", "Hello") { }
				serverLinks("links") { }
			}

			listOf("oak", "birch").forEach { leaf ->
				lootTable("blocks/${'$'}leaf") {
					namespace = "minecraft"
				}
			}

			configuredFeatures {
				ore("iron_ore", Blocks.IRON_ORE)
			}
			densityFunctions {
				noise("terrain_noise", Noises.CAVE_LAYER)
			}
			noise("worldgen_noise") { }
			testEnvironments {
				function("test_env") { }
			}
			recipesBuilder.blasting("iron_blast") { }
			with(structuresBuilder) {
				shipwreck("beached")
			}

			blockTag("logs", "minecraft") { }
			functionTag("tick_hooks") { }
		}
	}
""".trimIndent()

class KoreDeclarationIndexTest : BasePlatformTestCase() {
	private fun index(): Map<String, KoreDeclarationData> {
		myFixture.configureByText("main.kt", MAIN_KT)
		return KoreDeclarationIndex.findAll(GlobalSearchScope.allScope(project)).values.flatten().associateBy { it.name }
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
				"welcome",
				"links",
				"iron_ore",
				"terrain_noise",
				"worldgen_noise",
				"test_env",
				"iron_blast",
				"beached",
				"logs",
				"tick_hooks",
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

	/** Dialog builders sit inside a `dialogs { }` container rather than directly under `dataPack { }`. */
	fun testIndexesDialogsDeclaredInsideTheirContainer() {
		val indexed = index()

		assertEquals(KoreDeclarationKind.NOTICE, indexed.getValue("welcome").kind)
		assertEquals(KoreDeclarationKind.SERVER_LINKS, indexed.getValue("links").kind)
		assertEquals("rotten-flesh-to-leather", indexed.getValue("welcome").dataPackName)
	}

	/** Most scoped builders (`ore`, `single`, `abs`) default their block, so a bare call is a declaration too. */
	fun testResolvesScopedBuildersFromTheirEnclosingBlockOrReceiver() {
		val indexed = index()

		assertEquals(KoreDeclarationKind.ORE_FEATURE, indexed.getValue("iron_ore").kind)
		assertEquals(KoreDeclarationKind.FUNCTION_TEST_ENVIRONMENT, indexed.getValue("test_env").kind)
		assertEquals(KoreDeclarationKind.BLASTING, indexed.getValue("iron_blast").kind)
		assertEquals(KoreDeclarationKind.SHIPWRECK, indexed.getValue("beached").kind)
		assertEquals("rotten-flesh-to-leather", indexed.getValue("iron_ore").dataPackName)
	}

	/** `noise` is both `DataPack.noise` (worldgen/noise) and `DensityFunctionsScope.noise`: the scope tells them apart. */
	fun testTellsAmbiguousBuilderNamesApartByScope() {
		val indexed = index()

		assertEquals(KoreDeclarationKind.NOISE_DENSITY_FUNCTION, indexed.getValue("terrain_noise").kind)
		assertEquals(KoreDeclarationKind.NOISE, indexed.getValue("worldgen_noise").kind)
	}

	fun testIndexesTypedTagsWithTheirPositionalNamespace() {
		val indexed = index()

		assertEquals(KoreDeclarationKind.BLOCK_TAG, indexed.getValue("logs").kind)
		assertEquals("minecraft", indexed.getValue("logs").namespace)
		assertEquals(KoreDeclarationKind.FUNCTION_TAG, indexed.getValue("tick_hooks").kind)
		assertNull(indexed.getValue("tick_hooks").namespace)
	}

	fun testKeepsInterpolatedNamesAsTemplates() {
		val inLoop = index().getValue("blocks/\$leaf")

		assertEquals(KoreDeclarationKind.LOOT_TABLE, inLoop.kind)
		assertEquals("minecraft", inLoop.namespace)
		assertTrue("An interpolated name is only known at runtime", inLoop.isDynamic)
		assertEquals("rotten-flesh-to-leather", inLoop.dataPackName)
	}
}
