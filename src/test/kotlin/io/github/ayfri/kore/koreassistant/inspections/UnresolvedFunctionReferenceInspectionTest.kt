package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_KORE = """
	package io.github.ayfri.kore

	import io.github.ayfri.kore.functions.Function

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
	fun DataPack.functionTag(fileName: String, namespace: String = "", block: () -> Unit) = Unit
	fun <T> T.apply(block: T.() -> Unit): T = this
""".trimIndent()

private val FAKE_FUNCTIONS = """
	package io.github.ayfri.kore.functions

	class Function
""".trimIndent()

/** Same shapes as Kore's `commands/Function.kt`, so overload resolution picks the same one the real thing would. */
private val FAKE_COMMANDS = """
	package io.github.ayfri.kore.commands

	import io.github.ayfri.kore.functions.Function

	fun Function.function(name: String, group: Boolean = false) = Unit
	fun Function.function(namespace: String, name: String, group: Boolean = false) = Unit
""".trimIndent()

private const val HEADER = """
import io.github.ayfri.kore.commands.function
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.function
import io.github.ayfri.kore.functionTag

fun main() {
	dataPack("pack") {
		function("helper") { }
		function("in_other", namespace = "other") { }
		function("nested", directory = "utils") { }
		functionTag("group") { }
"""

private val MAIN_KT = HEADER + """
		function("caller") {
			function("helper")
			function("helpr")
			function("in_other")
			function("other", "in_other")
			function("in_other", "other")
			function("utils/nested")
			function("group", true)
			function("group")
			function("elsewhere", "another_pack")
		}
	}
}
""".trimStart('\n')

class UnresolvedFunctionReferenceInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.enableInspections(UnresolvedFunctionReferenceInspection::class.java)
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", FAKE_FUNCTIONS)
		myFixture.addFileToProject("commands.kt", FAKE_COMMANDS)
	}

	private fun errors(): Set<Pair<String, String?>> {
		myFixture.configureByText("Main.kt", MAIN_KT)
		return myFixture.doHighlighting()
			.filter { it.severity == HighlightSeverity.ERROR && it.inspectionToolId == "KoreUnresolvedFunctionReference" }
			.map { "${it.text}@${myFixture.editor.document.getLineNumber(it.startOffset) + 1}" to it.description }
			.toSet()
	}

	fun testReportsOnlyCallsWithNoDeclaredTarget() {
		assertEquals(
			setOf(
				"\"helpr\"@15" to "Function 'pack:helpr' is not declared in this project",
				"\"in_other\"@16" to "Function 'pack:in_other' is not declared in this project",
				"\"other\"@18" to "Function 'in_other:other' is not declared in this project",
				"\"group\"@21" to "Function 'pack:group' is not declared in this project",
			),
			errors(),
		)
	}

	private fun checkFix(call: String, fix: String, expectedCall: String) {
		myFixture.configureByText("Main.kt", "$HEADER\t\tfunction(\"caller\") {\n\t\t\t${call.replace("(", "(<caret>")}\n\t\t}\n\t}\n}\n")
		myFixture.launchAction(myFixture.findSingleIntention(fix))
		myFixture.checkResult("$HEADER\t\tfunction(\"caller\") {\n\t\t\t$expectedCall\n\t\t}\n\t}\n}\n")
	}

	fun testSuggestsTheClosestName() = checkFix("function(\"helpr\")", "Change to 'helper'", "function(\"helper\")")

	fun testSuggestsTheNamespaceTheFunctionLivesIn() = checkFix("function(\"in_other\")", "Call 'other:…' instead", "function(\"other\", \"in_other\")")

	fun testSwapsMisorderedArguments() = checkFix("function(\"in_other\", \"other\")", "Swap the namespace and name arguments", "function(\"other\", \"in_other\")")

	fun testCreatesTheMissingFunctionAfterTheCaller() {
		myFixture.configureByText("Main.kt", "$HEADER\t\tfunction(\"caller\") {\n\t\t\tfunction(<caret>\"utils/missing\")\n\t\t}\n\t}\n}\n")
		myFixture.launchAction(myFixture.findSingleIntention("Create function 'utils/missing'"))
		// The inserted call is reformatted with the project code style (spaces in tests), so indentation is not compared.
		val indentation = Regex("\n[ \t]+")
		assertEquals(
			"$HEADER\t\tfunction(\"caller\") {\n\t\t\tfunction(\"utils/missing\")\n\t\t}\n\n\t\tfunction(\"missing\", directory = \"utils\") {\n\t\t}\n\t}\n}\n".replace(indentation, "\n"),
			myFixture.file.text.replace(indentation, "\n"),
		)
	}
}
