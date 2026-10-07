package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_MACROS = """
	package io.github.ayfri.kore.functions

	import kotlin.reflect.KProperty

	class Function
	open class Macros

	operator fun String.getValue(thisRef: Macros, property: KProperty<*>): String = this
	fun Function.macro(name: String) = name
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.functions.Function
	import io.github.ayfri.kore.functions.Macros
	import io.github.ayfri.kore.functions.getValue
	import io.github.ayfri.kore.functions.macro

	class ItemMacros : Macros() {
		val id by "item_id"
		val bad by "item-id"
	}

	fun Function.body() {
		macro("count")
		macro("my count")
	}
""".trimIndent()

class MacroNameInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.enableInspections(MacroNameInspection::class.java)
		myFixture.addFileToProject("macros.kt", FAKE_MACROS)
		myFixture.configureByText("Main.kt", MAIN_KT)
	}

	fun testReportsInvalidNamesInBothForms() {
		val errors = myFixture.doHighlighting().filter { it.inspectionToolId == "KoreMacroName" }.map { it.text }
		assertEquals(listOf("\"item-id\"", "\"my count\""), errors)
	}

	fun testReplacesInvalidCharacters() {
		myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("my count"))
		myFixture.launchAction(myFixture.findSingleIntention("Change to 'my_count'"))
		assertTrue(myFixture.file.text.contains("macro(\"my_count\")"))
	}
}
