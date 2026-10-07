package io.github.ayfri.kore.koreassistant.completion

import com.intellij.openapi.util.TextRange
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

/** Same shapes as Kore: an argument type, its builder, and the generated registry enum implementing it. */
private val FAKE_ARGUMENTS = """
	package io.github.ayfri.kore.arguments.types.resources

	interface ItemArgument
	interface BlockArgument

	fun item(item: String, namespace: String = "minecraft"): ItemArgument = TODO()
	fun block(block: String, namespace: String = "minecraft"): BlockArgument = TODO()
""".trimIndent()

private val FAKE_GENERATED_ARGUMENTS = """
	package io.github.ayfri.kore.generated.arguments.types

	interface EntityTypeArgument {
		companion object {
			operator fun invoke(name: String, namespace: String = "minecraft"): EntityTypeArgument = TODO()
		}
	}
""".trimIndent()

private val FAKE_REGISTRIES = """
	package io.github.ayfri.kore.generated

	import io.github.ayfri.kore.arguments.types.resources.BlockArgument
	import io.github.ayfri.kore.arguments.types.resources.ItemArgument
	import io.github.ayfri.kore.generated.arguments.types.EntityTypeArgument

	enum class Items : ItemArgument { DIAMOND, DIAMOND_SWORD, STONE }
	enum class Blocks : BlockArgument { DIAMOND_BLOCK, STONE }
	enum class EntityTypes : EntityTypeArgument { ZOMBIE, ZOMBIE_VILLAGER }
""".trimIndent()

private val HEADER = """
	import io.github.ayfri.kore.arguments.types.resources.block
	import io.github.ayfri.kore.arguments.types.resources.item
	import io.github.ayfri.kore.commands.function
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.functionTag
	import io.github.ayfri.kore.generated.arguments.types.EntityTypeArgument

	fun main() {
		dataPack("pack") {
			function("helper") { }
			function("helper_two", directory = "utils") { }
			function("elsewhere", namespace = "other") { }
			functionTag("group") { }

			function("caller") {

""".trimIndent()

class KoreStringCompletionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", FAKE_FUNCTIONS)
		myFixture.addFileToProject("commands.kt", FAKE_COMMANDS)
		myFixture.addFileToProject("arguments.kt", FAKE_ARGUMENTS)
		myFixture.addFileToProject("generatedArguments.kt", FAKE_GENERATED_ARGUMENTS)
		myFixture.addFileToProject("registries.kt", FAKE_REGISTRIES)
	}

	/** The offered strings; a single candidate is inserted right away, so it is read back from the edited literal instead. */
	private fun lookups(statement: String): List<String> {
		myFixture.configureByText("Main.kt", "$HEADER\t\t\t$statement\n\t\t\t}\n\t\t}\n\t}\n")
		val elements = myFixture.completeBasic() ?: run {
			val document = myFixture.editor.document
			val line = document.getLineNumber(myFixture.caretOffset)
			val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
			return listOf(Regex("\"([^\"]*)\"").find(text)!!.groupValues[1])
		}
		return elements.map { it.lookupString }.sorted()
	}

	fun testCompletesFunctionsOfTheDatapackBareAndOthersNamespaced() =
		assertEquals(listOf("caller", "helper", "other:elsewhere", "utils/helper_two"), lookups("function(\"<caret>\")"))

	fun testFiltersOnTheWholeTypedValue() = assertEquals(listOf("utils/helper_two"), lookups("function(\"utils/<caret>\")"))

	fun testCompletesTagsForAGroupCall() = assertEquals(listOf("group"), lookups("function(\"<caret>\", true)"))

	fun testCompletesItemIds() = assertEquals(listOf("diamond", "diamond_sword"), lookups("item(\"dia<caret>\")"))

	fun testCompletesBlockIds() = assertEquals(listOf("diamond_block", "stone"), lookups("block(\"<caret>\")"))

	fun testCompletesIdsOfAnArgumentInvoke() = assertEquals(listOf("zombie", "zombie_villager"), lookups("EntityTypeArgument(\"<caret>\")"))

	// The platform's own file-name variants are still there, only this plugin's ids must be absent.
	fun testIgnoresAnotherNamespace() = assertFalse(lookups("item(\"<caret>\", \"mymod\")").any { it.startsWith("diamond") })

	fun testIgnoresUnrelatedStrings() = assertFalse(lookups("println(\"<caret>\")").any { it == "helper" || it.startsWith("diamond") })
}
