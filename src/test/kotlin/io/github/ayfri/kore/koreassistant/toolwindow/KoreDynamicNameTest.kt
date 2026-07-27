package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val FAKE_KORE = """
	package io.github.ayfri.kore

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: () -> Unit) = Unit
	fun DataPack.lootTable(fileName: String, block: DataPack.() -> Unit) = Unit
""".trimIndent()

private val CONSTANTS_KT = """
	const val NAMESPACE = "lifesteal"
	const val HEARTS_DIRECTORY = "hearts"
""".trimIndent()

/** The two layouts the tool window used to miss: a name coming from another file, and one built in a loop. */
private val MAIN_KT = """
	import io.github.ayfri.kore.DataPack
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.lootTable

	fun main() {
		dataPack(NAMESPACE) {
			setupMechanics()

			listOf("oak", "birch").forEach { leaf ->
				lootTable("blocks/${'$'}leaf") {
					namespace = "minecraft"
				}
			}
		}
	}

	fun DataPack.setupMechanics() {
		function(name = "apply_health", directory = HEARTS_DIRECTORY) { }
	}
""".trimIndent()

class KoreDynamicNameTest : BasePlatformTestCase() {
	private fun find(): Map<String, KoreElement> {
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("Constants.kt", CONSTANTS_KT)
		myFixture.configureByText("Main.kt", MAIN_KT)

		return ApplicationManager.getApplication()
			.executeOnPooledThread<List<KoreElement>> {
				runReadAction {
					KoreElementFinder.collect(project, GlobalSearchScope.allScope(project), EmptyProgressIndicator())
				}
			}
			.get()
			.associateBy(KoreElement::name)
	}

	fun testResolvesADataPackNamedFromAnotherFilesConstant() {
		val found = find()

		assertTrue("Expected the datapack to be named after NAMESPACE, got ${found.keys}", "lifesteal" in found)
		assertFalse(found.getValue("lifesteal").isDynamic)
	}

	fun testAttachesDeclarationsToTheConstantNamedDataPack() {
		assertEquals("lifesteal", find().getValue("apply_health").dataPackName)
	}

	fun testResolvesADirectoryPassedAsAConstant() {
		assertEquals(
			"data/lifesteal/function/hearts/apply_health.mcfunction",
			find().getValue("apply_health").outputPath,
		)
	}

	fun testListsALoopBuiltDeclarationAsATemplate() {
		val inLoop = find().getValue("blocks/\$leaf")

		assertTrue("A name built from a loop variable can only be a template", inLoop.isDynamic)
		assertEquals("data/minecraft/loot_table/blocks/\$leaf.json", inLoop.outputPath)
	}
}
