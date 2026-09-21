package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val FAKE_KORE = """
	package io.github.ayfri.kore

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: () -> Unit) = Unit
	fun DataPack.predicate(fileName: String, block: () -> Unit) = Unit
	fun DataPack.advancement(name: String, block: () -> Unit) = Unit
""".trimIndent()

/** Kore's recommended layout: the datapack block only calls `DataPack.xxx()` extensions declared elsewhere. */
private val MAIN_KT = """
	import io.github.ayfri.kore.DataPack
	import io.github.ayfri.kore.advancement
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.predicate

	fun main() {
		dataPack("my_datapack") {
			function("my_function") { }
			myPredicate()
			setup()
		}
	}

	fun DataPack.myPredicate() = predicate("test") { }

	// One level of indirection: an extension that only calls other extensions.
	fun DataPack.setup() {
		myAdvancement()
	}

	fun DataPack.myAdvancement() = advancement("root") { }

	// Never called from the datapack block - only the "project has a single datapack" fallback can place it.
	fun DataPack.unreferenced() = advancement("stray") { }
""".trimIndent()

class KoreExtensionPatternTest : BasePlatformTestCase() {
	private fun find(): Map<String, KoreElement> {
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.configureByText("Main.kt", MAIN_KT)

		return ApplicationManager.getApplication()
			.executeOnPooledThread<List<KoreElement>> {
				runReadActionBlocking {
					KoreElementFinder.collect(project, GlobalSearchScope.allScope(project))
				}
			}
			.get()
			.associateBy(KoreElement::name)
	}

	fun testAttachesDeclarationsFromDataPackExtensionsToTheirDataPack() {
		val found = find()

		assertEquals("my_datapack", found.getValue("test").dataPackName)
		assertEquals("my_datapack", found.getValue("test").namespace)
	}

	fun testFollowsExtensionsCallingOtherExtensions() {
		assertEquals("my_datapack", find().getValue("root").dataPackName)
	}

	fun testFallsBackToTheProjectsOnlyDataPackForUnreferencedExtensions() {
		assertEquals("my_datapack", find().getValue("stray").dataPackName)
	}

	fun testKeepsNothingUnderTheUnknownDataPackNode() {
		val root = buildKoreTree(find().values.toList(), KoreGroupBy.OUTPUT, KoreSortBy.NAME, KoreSortOrder.ASCENDING)
		val dataPacks = (0 until root.childCount).map { root.getChildAt(it).toString() }

		assertEquals(1, root.childCount)
		assertFalse("Expected no unknown datapack node, got $dataPacks", dataPacks.any { it.contains(UNKNOWN_DATA_PACK) })
	}
}
