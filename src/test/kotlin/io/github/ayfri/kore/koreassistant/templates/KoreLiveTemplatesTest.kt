package io.github.ayfri.kore.koreassistant.templates

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

/** Every callable the templates spell, at its real Kore FqName, so expansion has to import exactly what Kore would need. */
private val FAKE_KORE = mapOf(
	"kore.kt" to """
		package io.github.ayfri.kore
		class DataPack { fun path(path: String) {}; fun generate() {} }
		fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack()
	""",
	"functions.kt" to """
		package io.github.ayfri.kore.functions
		import io.github.ayfri.kore.DataPack
		class Function
		fun DataPack.function(name: String, block: Function.() -> Unit) {}
		fun DataPack.load(block: Function.() -> Unit) {}
		fun DataPack.tick(block: Function.() -> Unit) {}
	""",
	"execute.kt" to """
		package io.github.ayfri.kore.commands.execute
		import io.github.ayfri.kore.functions.Function
		class Execute { fun asTarget(target: Int) {}; fun run(block: Function.() -> Unit) {} }
		fun Function.execute(block: Execute.() -> Unit) {}
	""",
	"selectors.kt" to "package io.github.ayfri.kore.arguments.types.literals\nfun allEntities() = 0",
	"loot.kt" to """
		package io.github.ayfri.kore.features.loottables
		import io.github.ayfri.kore.DataPack
		class LootTable
		class LootPool
		class LootEntries
		fun DataPack.lootTable(fileName: String, init: LootTable.() -> Unit) {}
		fun LootTable.pool(block: LootPool.() -> Unit) {}
		fun LootPool.entries(block: LootEntries.() -> Unit) {}
	""",
	"lootEntries.kt" to """
		package io.github.ayfri.kore.features.loottables.entries
		import io.github.ayfri.kore.features.loottables.LootEntries
		import io.github.ayfri.kore.generated.Items
		fun LootEntries.item(name: Items) {}
	""",
	"items.kt" to "package io.github.ayfri.kore.generated\nenum class Items { DIAMOND, DIAMOND_BLOCK }",
	"advancements.kt" to """
		package io.github.ayfri.kore.features.advancements
		import io.github.ayfri.kore.DataPack
		import io.github.ayfri.kore.generated.Items
		class Advancement
		fun DataPack.advancement(fileName: String, block: Advancement.() -> Unit) {}
		fun Advancement.display(icon: Items, title: String, description: String) {}
	""",
	"recipes.kt" to """
		package io.github.ayfri.kore.features.recipes
		import io.github.ayfri.kore.DataPack
		class Recipes
		fun DataPack.recipes(block: Recipes.() -> Unit) {}
	""",
	"recipeTypes.kt" to """
		package io.github.ayfri.kore.features.recipes.types
		import io.github.ayfri.kore.features.recipes.Recipes
		import io.github.ayfri.kore.generated.Items
		class CraftingShaped
		fun Recipes.craftingShaped(name: String, block: CraftingShaped.() -> Unit) {}
		fun CraftingShaped.pattern(vararg lines: String) {}
		fun CraftingShaped.key(key: String, vararg ingredients: Items) {}
		fun CraftingShaped.result(item: Items) {}
	""",
	"tags.kt" to """
		package io.github.ayfri.kore.features.tags
		import io.github.ayfri.kore.DataPack
		class Tag { operator fun plusAssign(value: String) {} }
		fun DataPack.functionTag(fileName: String, namespace: String = "", block: Tag.() -> Unit) {}
	""",
)

/** Every callable spelled by the templates; none of them may be left unresolved after expansion. */
private val TEMPLATE_CALLABLES = listOf(
	"dataPack", "path", "generate", "load", "tick", "execute", "asTarget", "run", "allEntities", "lootTable", "pool", "entries",
	"item", "Items", "advancement", "display", "recipes", "craftingShaped", "pattern", "key", "result", "functionTag",
)

class KoreLiveTemplatesTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun setUp() {
		super.setUp()
		FAKE_KORE.forEach { (name, source) -> myFixture.addFileToProject(name, source.trimIndent()) }
	}

	/** Expands [abbreviation] at `<caret>` of [code], accepting every default, and returns the resulting file text. */
	private fun expand(code: String, abbreviation: String): String {
		myFixture.configureByText("Main.kt", code.replace("<caret>", "$abbreviation<caret>"))
		myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
		WriteCommandAction.runWriteCommandAction(project) { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }

		val text = myFixture.file.text
		val unresolved = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
			.filter { error -> TEMPLATE_CALLABLES.any { error.contains("'$it'") } && error.contains("nresolved") }
		assertTrue("$unresolved in:\n$text", unresolved.isEmpty())
		assertTrue(text, text.lines().filter { "import " in it }.all { Regex("import [\\w.]+").matches(it) })
		return text
	}

	private val inDataPack = "import io.github.ayfri.kore.dataPack\n\nfun main() {\n\tdataPack(\"p\") {\n\t\t<caret>\n\t}\n}\n"

	fun testDpMain() {
		val text = expand("<caret>\n", "dpmain")
		assertTrue(text, text.contains("import io.github.ayfri.kore.dataPack"))
		assertTrue(text, text.contains("dataPack(\"my_pack\") {"))
		assertTrue(text, text.contains("path(System.getProperty(\"kore.output\") ?: \"out\")"))
		assertTrue(text, text.contains("}.generate()"))
	}

	fun testLoadAndTick() {
		assertTrue(expand(inDataPack, "load").contains("import io.github.ayfri.kore.functions.load"))
		assertTrue(expand(inDataPack, "tick").contains("import io.github.ayfri.kore.functions.tick"))
	}

	fun testExec() {
		val inFunction = "import io.github.ayfri.kore.dataPack\nimport io.github.ayfri.kore.functions.function\n\nfun main() {\n\tdataPack(\"p\") {\n\t\tfunction(\"f\") {\n\t\t\t<caret>\n\t\t}\n\t}\n}\n"
		val text = expand(inFunction, "exec")
		assertTrue(text, text.contains("import io.github.ayfri.kore.commands.execute.execute"))
		assertTrue(text, text.contains("asTarget(allEntities())"))
	}

	fun testLootTable() {
		val text = expand(inDataPack, "lt")
		assertTrue(text, text.contains("item(Items.DIAMOND)"))
		assertTrue(text, text.contains("import io.github.ayfri.kore.features.loottables.entries.item"))
	}

	fun testAdvancement() = assertTrue(expand(inDataPack, "adv").contains("display(Items.DIAMOND, \"Title\", \"Description\")"))

	fun testShapedRecipe() {
		val text = expand(inDataPack, "shaped")
		assertTrue(text, text.contains("key(\"#\", Items.DIAMOND)"))
		assertTrue(text, text.contains("result(Items.DIAMOND_BLOCK)"))
	}

	fun testFunctionTag() = assertTrue(expand(inDataPack, "ftag").contains("functionTag(\"load\", namespace = \"minecraft\") {"))
}
