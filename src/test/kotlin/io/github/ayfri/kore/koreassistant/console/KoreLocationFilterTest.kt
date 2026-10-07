package io.github.ayfri.kore.koreassistant.console

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val MAIN_KT = """
	fun main() {
		dataPack("pack") {
			function("helper", directory = "utils") { }
			functionTag("group") { }
			lootTable("chests/loot") { }
		}
	}
""".trimIndent()

class KoreLocationFilterTest : BasePlatformTestCase() {
	override fun setUp() {
		super.setUp()
		myFixture.configureByText("Main.kt", MAIN_KT)
	}

	/** The linked substrings of [line], printed after 10 characters of earlier output to check offsets are shifted. */
	private fun links(line: String): List<String> {
		val result = KoreLocationFilter(project).applyFilter(line, 10 + line.length) ?: return emptyList()
		return result.resultItems.map { line.substring(it.highlightStartOffset - 10, it.highlightEndOffset - 10) }
	}

	fun testLinksDeclaredLocationsOnly() = assertEquals(
		listOf("pack:utils/helper", "pack:chests/loot"),
		links("[kore:warn-unreachable-code] pack:utils/helper: 1 unreachable line(s), see pack:chests/loot and minecraft:stone"),
	)

	fun testLinksTagsWithTheirHash() = assertEquals(listOf("#pack:group"), links("function #pack:group and pack:group"))

	fun testIgnoresUrls() = assertEmpty(links("see https://kore.ayfri.com/docs and C:\\pack:utils"))

	fun testNavigatesToTheDeclaration() {
		val line = "pack:utils/helper"
		val item = KoreLocationFilter(project).applyFilter(line, line.length)!!.resultItems.single()
		item.hyperlinkInfo!!.navigate(project)
		val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
		assertEquals(myFixture.file.text.indexOf("function(\"helper\""), editor.caretModel.offset)
	}
}
