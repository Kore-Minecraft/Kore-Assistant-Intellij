package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor
import io.github.ayfri.kore.koreassistant.inspections.koreElements

private val FAKE_KORE = """
	package io.github.ayfri.kore

	import io.github.ayfri.kore.functions.Function

	class DataPack

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack()
	fun DataPack.lootTable(fileName: String, block: DataPack.() -> Unit) = Unit
	fun DataPack.predicate(fileName: String, block: DataPack.() -> Unit) = Unit
""".trimIndent()

private val FAKE_FUNCTIONS = """
	package io.github.ayfri.kore.functions

	import io.github.ayfri.kore.DataPack

	class Function

	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
	fun DataPack.load(name: String? = null, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
""".trimIndent()

private val FAKE_COMMANDS = """
	package io.github.ayfri.kore.commands

	import io.github.ayfri.kore.functions.Function

	fun Function.function(name: String) = Unit
""".trimIndent()

private val EXTRAS_KT = """
	import io.github.ayfri.kore.DataPack
	import io.github.ayfri.kore.predicate

	fun DataPack.extras() {
		predicate("is_day") { }
	}
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.commands.function
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.functions.function
	import io.github.ayfri.kore.functions.load
	import io.github.ayfri.kore.lootTable

	fun main() {
		dataPack("pack") {
			function("helper") { function("other") }
			lootTable("chest") { }
			load { }
			extras()
		}
	}
""".trimIndent()

class KoreGutterProviderTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", FAKE_FUNCTIONS)
		myFixture.addFileToProject("commands.kt", FAKE_COMMANDS)
		myFixture.addFileToProject("Extras.kt", EXTRAS_KT)
	}

	/** The hover of the marker whose callee starts [text], computed now, like a real hover. */
	private fun hover(text: String) = myFixture.findAllGutters().single { offsetOf(it) == myFixture.file.text.indexOf(text) }.tooltipText.orEmpty()

	private fun offsetOf(mark: GutterMark) = (mark as LineMarkerGutterIconRenderer<*>).lineMarkerInfo.startOffset

	fun testMarksEveryDeclarationButNotCommands() {
		myFixture.configureByText("Main.kt", MAIN_KT)
		val kore = setOf(KoreIcons.KORE, KoreIcons.FUNCTION, AllIcons.FileTypes.Json)
		val marked = myFixture.findAllGutters().filter { it.icon in kore }.map { myFixture.file.text.substring(offsetOf(it)).takeWhile(Char::isLetter) }
		assertEquals(listOf("dataPack", "function", "load", "lootTable"), marked.sorted())
	}

	fun testHoverShowsTheDeclarationCard() {
		myFixture.configureByText("Main.kt", MAIN_KT)
		val card = hover("lootTable(")
		assertTrue(card, "pack:chest" in card)
		assertTrue(card, "data/pack/loot_table/chest.json" in card)
		assertTrue(card, "Main.kt:" in card)
	}

	fun testHoverAttributesExtensionsToTheirDataPack() {
		myFixture.configureByText("Main.kt", MAIN_KT)
		myFixture.configureFromTempProjectFile("Extras.kt")
		// The hover only reads an up-to-date collection, which resolves through the Analysis API, refused on the EDT.
		ApplicationManager.getApplication().executeOnPooledThread { runReadActionBlocking { project.koreElements() } }.get()

		val card = hover("predicate(")
		assertTrue(card, "pack:is_day" in card)
	}
}
