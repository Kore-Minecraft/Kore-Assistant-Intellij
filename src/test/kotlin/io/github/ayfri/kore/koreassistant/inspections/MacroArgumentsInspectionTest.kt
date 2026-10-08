package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_KORE = """
	package io.github.ayfri.kore

	class DataPack

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack()
""".trimIndent()

private val FAKE_FUNCTIONS = """
	package io.github.ayfri.kore.functions

	import io.github.ayfri.kore.DataPack
	import kotlin.reflect.KProperty

	interface FunctionOrTagArgument
	open class Function : FunctionOrTagArgument
	open class Macros
	class FunctionWithMacros<out T : Macros>(val macros: T) : Function()

	operator fun String.getValue(thisRef: Macros, property: KProperty<*>): String = this
	fun Function.macro(name: String) = name
	fun Function.eval(vararg macroNames: String) = Unit
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Function()
	fun <T : Macros> DataPack.function(name: String, macros: () -> T, namespace: String = "", directory: String = "", function: FunctionWithMacros<T>.() -> Unit) =
		FunctionWithMacros(macros())
""".trimIndent()

private val FAKE_UTILS = """
	package io.github.ayfri.kore.utils

	class NbtCompound
	class NbtCompoundBuilder {
		fun put(key: String, value: String) = Unit
	}

	fun nbt(block: NbtCompoundBuilder.() -> Unit) = NbtCompound()
	operator fun NbtCompoundBuilder.set(name: String, value: String) = Unit
""".trimIndent()

/** Same shapes as Kore's `commands/Function.kt`, so overload resolution picks the same one the real thing would. */
private val FAKE_COMMANDS = """
	package io.github.ayfri.kore.commands

	import io.github.ayfri.kore.functions.Function
	import io.github.ayfri.kore.functions.FunctionOrTagArgument
	import io.github.ayfri.kore.functions.FunctionWithMacros
	import io.github.ayfri.kore.functions.Macros
	import io.github.ayfri.kore.utils.NbtCompound
	import io.github.ayfri.kore.utils.NbtCompoundBuilder

	class DataArgument

	fun storage() = DataArgument()
	fun Function.say(message: String) = Unit
	fun Function.function(name: String, group: Boolean = false, arguments: NbtCompound? = null) = Unit
	fun Function.function(name: String, group: Boolean = false, arguments: DataArgument, path: String? = null) = Unit
	fun Function.function(function: FunctionOrTagArgument, arguments: NbtCompound? = null) = Unit
	inline fun <reified T : Macros> Function.function(function: FunctionWithMacros<T>, arguments: NbtCompound) = Unit
	inline fun <reified T : Macros> Function.function(function: FunctionWithMacros<T>, noinline builder: NbtCompoundBuilder.() -> Unit) = Unit
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.commands.function
	import io.github.ayfri.kore.commands.say
	import io.github.ayfri.kore.commands.storage
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.functions.Function
	import io.github.ayfri.kore.functions.Macros
	import io.github.ayfri.kore.functions.eval
	import io.github.ayfri.kore.functions.function
	import io.github.ayfri.kore.functions.getValue
	import io.github.ayfri.kore.functions.macro
	import io.github.ayfri.kore.utils.nbt
	import io.github.ayfri.kore.utils.set

	class TeleportMacros : Macros() {
		val player by "player"
		val spare by "spare"
	}

	fun Function.greet() {
		say(macro("greeting"))
	}

	fun someName() = "name"

	fun main() {
		dataPack("pack") {
			function("tp") {
				say("${'$'}{macro("player")} \$(x)")
				eval("y")
			}
			function("helper_user") {
				greet()
			}
			function("plain") { }
			function("dynamic") {
				say(macro(someName()))
			}
			val typed = function("typed", ::TeleportMacros) {
				say(macros.player)
			}
			val untyped = function("untyped") {
				say(macro("a"))
			}

			function("caller") {
				function("tp")
				function("tp", arguments = nbt { this["player"] = "p"; this["x"] = "1"; put("y", "2") })
				function("tp", arguments = nbt { this["playr"] = "p"; this["x"] = "1"; this["y"] = "2" })
				function("helper_user", arguments = nbt { this["greeting"] = "hi" })
				function("plain", arguments = nbt { this["stray"] = "b" })
				function("dynamic", arguments = nbt { this["stray"] = "b" })
				function("tp", arguments = storage())
				function("typed", arguments = nbt { this["player"] = "p" })
				function(typed, arguments = nbt { this["player"] = "p"; this["extra"] = "b" })
				function(typed) { this["player"] = "p" }
				function(untyped)
				function(typed) {
					this["spare"] = "s"
				}
			}
		}
	}
""".trimIndent()

class MacroArgumentsInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.enableInspections(MacroArgumentsInspection::class.java, UnusedMacroInspection::class.java)
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", FAKE_FUNCTIONS)
		myFixture.addFileToProject("utils.kt", FAKE_UTILS)
		myFixture.addFileToProject("commands.kt", FAKE_COMMANDS)
		myFixture.configureByText("Main.kt", MAIN_KT)
	}

	private fun problems(toolId: String) = myFixture.doHighlighting()
		.filter { it.inspectionToolId == toolId }
		.map { "${it.text}@${myFixture.editor.document.getLineNumber(it.startOffset) + 1} ${it.severity.name}: ${it.description}" }
		.toSet()

	fun testReportsMissingAndUnusedArguments() {
		val error = HighlightSeverity.ERROR.name
		val warning = HighlightSeverity.WARNING.name
		assertEquals(
			setOf(
				"\"tp\"@46 $error: Missing macro arguments 'player', 'x', 'y' for function 'tp'",
				"\"tp\"@48 $error: Missing macro argument 'player' for function 'tp'",
				"\"playr\"@48 $warning: Function 'tp' does not use macro 'playr'",
				"\"stray\"@50 $warning: Function 'plain' does not use macro 'stray'",
				"\"extra\"@54 $error: Function 'typed' does not use macro 'extra'",
				"untyped@56 $error: Missing macro argument 'a' for function 'untyped'",
				"typed@57 $error: Missing macro argument 'player' for function 'typed'",
				"\"spare\"@58 $error: Function 'typed' does not use macro 'spare'",
			),
			problems("KoreMacroArguments"),
		)
	}

	fun testReportsUnusedTypedMacros() {
		assertEquals(
			setOf("::TeleportMacros@38 ${HighlightSeverity.WARNING.name}: Macro 'spare' never used by this function"),
			problems("KoreUnusedMacro"),
		)
	}

	/** Runs the fix from inside the target argument of [call]'s first occurrence. */
	private fun addMissingArguments(call: String) {
		myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(call) + "function(".length + 1)
		myFixture.launchAction(myFixture.findSingleIntention("Add missing macro arguments"))
	}

	fun testAddsAnArgumentsCompoundAndItsImports() {
		myFixture.configureByText("Other.kt", "import io.github.ayfri.kore.commands.function\nimport io.github.ayfri.kore.functions.Function\n\nfun Function.call() {\n\tfunction(\"tp\")\n}\n")
		addMissingArguments("function(\"tp\")")
		myFixture.checkResult(
			"""
			import io.github.ayfri.kore.commands.function
			import io.github.ayfri.kore.functions.Function
			import io.github.ayfri.kore.utils.nbt
			import io.github.ayfri.kore.utils.set

			fun Function.call() {
				function("tp", arguments = nbt { this["player"] = ""; this["x"] = ""; this["y"] = "" })
			}

			""".trimIndent()
		)
	}

	fun testAppendsToAOneLineCompound() {
		addMissingArguments("function(\"tp\", arguments = nbt { this[\"playr\"]")
		assertTrue(myFixture.file.text, myFixture.file.text.contains("nbt { this[\"playr\"] = \"p\"; this[\"x\"] = \"1\"; this[\"y\"] = \"2\"; this[\"player\"] = \"\" }"))
	}

	fun testAppendsToAMultilineBuilder() {
		addMissingArguments("function(typed) {\n")
		// The light fixture's code style indents with spaces, only the line structure matters here.
		val lines = myFixture.file.text.lines().map(String::trim)
		val start = lines.indexOf("function(typed) {")
		assertEquals(listOf("function(typed) {", "this[\"spare\"] = \"s\"", "this[\"player\"] = \"\"", "}"), lines.subList(start, start + 4))
	}

	fun testRenamesAnUnusedArgumentToTheClosestMissingOne() {
		myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("playr"))
		myFixture.launchAction(myFixture.findSingleIntention("Change to 'player'"))
		assertTrue(myFixture.file.text.contains("nbt { this[\"player\"] = \"p\"; this[\"x\"] = \"1\"; this[\"y\"] = \"2\" }"))
	}
}
