package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_ARGUMENTS = """
	package io.github.ayfri.kore.arguments.types.resources

	interface ItemArgument
	interface SoundArgument

	fun item(item: String, namespace: String = "minecraft"): ItemArgument = TODO()
	fun sound(sound: String, namespace: String = "minecraft"): SoundArgument = TODO()
""".trimIndent()

private val FAKE_REGISTRIES = """
	package io.github.ayfri.kore.generated

	import io.github.ayfri.kore.arguments.types.resources.ItemArgument

	enum class Items : ItemArgument { DIAMOND, DIAMOND_SWORD, STONE }
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.arguments.types.resources.item
	import io.github.ayfri.kore.arguments.types.resources.sound

	fun item(name: String) = Unit

	fun main() {
		item("diamond_sword")
		item("diamond_swrod")
		item("dirt")
		item("custom", "mymod")
		item("minecraft:stone")
		sound("whatever")
		item("")
	}

	fun local() {
		item(name = "not_kore")
	}
""".trimIndent()

class UnknownVanillaIdInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.enableInspections(UnknownVanillaIdInspection::class.java)
		myFixture.addFileToProject("arguments.kt", FAKE_ARGUMENTS)
		myFixture.addFileToProject("registries.kt", FAKE_REGISTRIES)
		myFixture.configureByText("Main.kt", MAIN_KT)
	}

	private fun warnings() = myFixture.doHighlighting()
		.filter { it.inspectionToolId == "KoreUnknownVanillaId" }
		.map { "${it.text}: ${it.description}" }

	/** No registry for sounds, another namespace, an inline namespace and a same-named local function are all left alone. */
	fun testReportsOnlyUnknownIdsOfAKnownRegistry() = assertEquals(
		listOf("\"diamond_swrod\": Unknown item 'diamond_swrod'", "\"dirt\": Unknown item 'dirt'"),
		warnings(),
	)

	fun testSuggestsTheClosestIds() {
		myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("diamond_swrod"))
		myFixture.launchAction(myFixture.findSingleIntention("Change to 'diamond_sword'"))
		assertTrue(myFixture.file.text.contains("item(\"diamond_sword\")\n\titem(\"diamond_sword\")"))
	}
}
