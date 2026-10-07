package io.github.ayfri.kore.koreassistant.references

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_KORE = """
	package io.github.ayfri.kore

	import io.github.ayfri.kore.functions.Function

	class DataPack

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack()
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
	fun DataPack.functionTag(fileName: String, namespace: String = "", block: () -> Unit) = Unit
""".trimIndent()

private val FAKE_FUNCTIONS = """
	package io.github.ayfri.kore.functions

	class Function
""".trimIndent()

private val FAKE_COMMANDS = """
	package io.github.ayfri.kore.commands

	import io.github.ayfri.kore.functions.Function

	fun Function.function(name: String, group: Boolean = false) = Unit
	fun Function.function(namespace: String, name: String, group: Boolean = false) = Unit
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.commands.function
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.functionTag

	fun main() {
		dataPack("pack") {
			function("helper") { }
			function("helper", namespace = "other") { }
			function("nested", directory = "utils") { }
			functionTag("group") { }

			function("caller") {
				function("helper")
				function("other:helper")
				function("other", "helper")
				function("utils/nested")
				function("group", true)
				function("missing")
			}
		}
	}
""".trimIndent()

class KoreFunctionReferenceTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", FAKE_FUNCTIONS)
		myFixture.addFileToProject("commands.kt", FAKE_COMMANDS)
		myFixture.configureByText("Main.kt", MAIN_KT)
	}

	private fun offsetOf(text: String) = myFixture.file.text.indexOf(text).also { assertTrue("'$text' not found", it >= 0) } + 1

	/** The declarations a call literal resolves to, as `line: text` so a wrong same-name target is caught too. */
	/** Other plugins add file and property references to every string, so only this plugin's own are looked at. */
	private fun koreReferencesAt(text: String) = myFixture.file.findElementAt(offsetOf(text))!!.parent.parent.references
		.filter { it is KoreFunctionReference || it is KoreFunctionDeclarationReference }

	private fun targetsOf(call: String): List<String> {
		val reference = koreReferencesAt(call).single() as PsiPolyVariantReference
		val document = myFixture.editor.document
		return reference.multiResolve(false).map {
			val navigation = it.element!!.navigationElement
			"${document.getLineNumber(navigation.textOffset) + 1}: ${navigation.text}"
		}
	}

	fun testResolvesTheNameOnlyOverloadInTheDatapackNamespace() = assertEquals(listOf("8: \"helper\""), targetsOf("\"helper\")\n"))

	fun testResolvesANamespacedName() = assertEquals(listOf("9: \"helper\""), targetsOf("\"other:helper\""))

	fun testResolvesTheNamespaceFirstOverload() = assertEquals(listOf("9: \"helper\""), targetsOf("\"helper\")\n\t\t\tfunction(\"utils"))

	fun testResolvesAPathWithItsDirectory() = assertEquals(listOf("10: \"nested\""), targetsOf("\"utils/nested\""))

	fun testResolvesAGroupToTheFunctionTag() = assertEquals(listOf("11: \"group\""), targetsOf("\"group\", true"))

	fun testLeavesAnUndeclaredFunctionUnresolved() = assertEquals(emptyList<String>(), targetsOf("\"missing\""))

	fun testTheNamespaceArgumentIsNotAReference() = assertEmpty(koreReferencesAt("\"other\", \"helper\""))

	fun testFindsUsagesFromTheDeclaration() {
		val target = koreReferencesAt("\"helper\") { }").single().resolve()!!
		val usages = myFixture.findUsages(target).map { it.element!!.text }.sorted()
		assertEquals(listOf("\"helper\""), usages)
	}

	fun testFindsNamespacedUsagesOfAFunctionInAnotherNamespace() {
		val target = koreReferencesAt("\"helper\", namespace").single().resolve()!!
		val usages = myFixture.findUsages(target).map { it.element!!.text }.sorted()
		assertEquals(listOf("\"helper\"", "\"other:helper\""), usages)
	}

	fun testRenamesFromACallSiteKeepingNamespaceAndDirectory() {
		myFixture.editor.caretModel.moveToOffset(offsetOf("\"utils/nested\""))
		myFixture.renameElementAtCaret("deep")
		assertTrue(myFixture.file.text.contains("function(\"deep\", directory = \"utils\") { }"))
		assertTrue(myFixture.file.text.contains("function(\"utils/deep\")"))
	}

	fun testRenamesFromTheDeclarationOnlyTheMatchingNamespace() {
		myFixture.editor.caretModel.moveToOffset(offsetOf("\"helper\", namespace"))
		myFixture.renameElementAtCaret("renamed")
		val text = myFixture.file.text
		assertTrue(text.contains("function(\"renamed\", namespace = \"other\") { }"))
		assertTrue(text.contains("function(\"other:renamed\")"))
		assertTrue(text.contains("function(\"other\", \"renamed\")"))
		// The datapack's own `helper` and its call are another function.
		assertTrue(text.contains("function(\"helper\") { }"))
		assertTrue(text.contains("function(\"helper\")\n"))
	}
}
