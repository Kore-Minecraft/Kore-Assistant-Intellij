package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.references.KoreFunctionTarget
import org.jetbrains.kotlin.psi.KtNamedFunction
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Stands in for the real `io.github.ayfri.kore` dependency: the finder only checks that the resolved callable
 * lives under that package, so a handful of stubs is enough to exercise the whole confirmation path.
 */
private val FAKE_KORE = """
	package io.github.ayfri.kore

	import io.github.ayfri.kore.functions.Function

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
	fun DataPack.advancement(name: String, block: DataPack.() -> Unit) = Unit
	fun DataPack.load(name: String? = null, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
""".trimIndent()

private val FAKE_FUNCTIONS = """
	package io.github.ayfri.kore.functions

	class Function
""".trimIndent()

/** The `/function` command shares its callee name with the declaration; it lives in `commands`, which is what tells them apart. */
private val FAKE_COMMANDS = """
	package io.github.ayfri.kore.commands

	import io.github.ayfri.kore.functions.Function

	fun Function.function(name: String) = Unit
""".trimIndent()

/** Kore builders sharing a name with the function family without declaring a function: an advancement trigger and a LanternLoad block. */
private val FAKE_NAMESAKES = """
	package io.github.ayfri.kore.features.advancements.triggers

	class AdvancementCriteria
	class LanternLoad {
		fun load(block: () -> Unit) = Unit
	}

	fun AdvancementCriteria.tick(name: String, block: () -> Unit = {}) = Unit
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.advancement
	import io.github.ayfri.kore.commands.function
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.load
	import io.github.ayfri.kore.features.advancements.triggers.AdvancementCriteria
	import io.github.ayfri.kore.features.advancements.triggers.LanternLoad
	import io.github.ayfri.kore.features.advancements.triggers.tick

	// Same name as a real Kore builder, but resolves elsewhere - must not show up.
	fun advancement(name: String, block: () -> Unit) = Unit

	fun main() {
		dataPack("mypack") {
			function("helper", directory = "utils") {
				function("called_not_declared")
			}
			advancement("root") { }
			load { }
		}

		advancement("impostor") { }
		AdvancementCriteria().tick("criterion") { }
		LanternLoad().load { }
	}
""".trimIndent()

class KoreElementFinderTest : BasePlatformTestCase() {
	private fun collect(): List<KoreElement> {
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", FAKE_FUNCTIONS)
		myFixture.addFileToProject("commands.kt", FAKE_COMMANDS)
		myFixture.addFileToProject("namesakes.kt", FAKE_NAMESAKES)
		myFixture.configureByText("main.kt", MAIN_KT)

		// The Analysis API refuses to resolve on the EDT, where platform tests run, so mirror what production does.
		return ApplicationManager.getApplication()
			.executeOnPooledThread<List<KoreElement>> { runReadActionBlocking { KoreElementFinder.collect(project) } }
			.get()
	}

	private fun find() = collect().associateBy(KoreElement::name)

	fun testKeepsOnlyDeclarationsResolvingIntoKoreNotCommandsOrNamesakes() {
		assertEquals(listOf("helper", "load_<hash>", "mypack", "root"), collect().map(KoreElement::name).sorted())
	}

	fun testListsAnUnnamedLoadUnderItsHashedGeneratedName() {
		val load = find().getValue("load_<hash>")

		assertTrue(load.isDynamic)
		assertEquals("data/mypack/function/generated_scopes/load_<hash>.mcfunction", load.outputPath)
	}

	/** A function row finds its `function("x")` calls, any other row the usages of the Kotlin function declaring it. */
	fun testFindsUsagesOfAFunctionRowThroughItsKoreTarget() {
		val found = find()

		assertInstanceOf(found.getValue("helper").findDeclaration(project), KoreFunctionTarget::class.java)
		assertEquals("main", (found.getValue("root").findDeclaration(project) as KtNamedFunction).name)
	}

	fun testInheritsTheNamespaceFromTheEnclosingDataPack() {
		val found = find()

		assertEquals("mypack", found.getValue("root").namespace)
		assertEquals("mypack", found.getValue("helper").namespace)
		assertEquals("mypack", found.getValue("root").dataPackName)
	}

	fun testComputesTheGeneratedOutputPath() {
		val found = find()

		assertEquals("data/mypack/function/utils/helper.mcfunction", found.getValue("helper").outputPath)
		assertEquals("data/mypack/advancement/root.json", found.getValue("root").outputPath)
		assertEquals("mypack/pack.mcmeta", found.getValue("mypack").outputPath)
	}

	fun testReportsTheDeclarationKindAndLocation() {
		val helper = find().getValue("helper")

		assertEquals(KoreDeclarationKind.FUNCTION, helper.kind)
		assertEquals("main.kt", helper.fileName)
		assertTrue("Expected a 1-based line number, got ${helper.lineNumber}", helper.lineNumber > 0)
	}

	fun testBuildsTheOutputTreeGroupedByDataPackNamespaceAndFolder() {
		val root = buildKoreTree(find().values.toList(), KoreGroupBy.OUTPUT, KoreSortBy.NAME, KoreSortOrder.ASCENDING)

		assertEquals(1, root.childCount)
		val dataPack = root.firstChild as DefaultMutableTreeNode
		assertEquals("mypack", (dataPack.userObject as KoreTreeNode).label)

		val namespace = dataPack.firstChild as DefaultMutableTreeNode
		assertEquals("mypack", (namespace.userObject as KoreTreeNode).label)

		val folders = (0 until namespace.childCount).map {
			((namespace.getChildAt(it) as DefaultMutableTreeNode).userObject as KoreTreeNode).label
		}
		assertEquals(listOf("advancement", "function"), folders)
	}
}
