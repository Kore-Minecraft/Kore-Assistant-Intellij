package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Stands in for the real `io.github.ayfri.kore` dependency: the finder only checks that the resolved callable
 * lives under that package, so a handful of stubs is enough to exercise the whole confirmation path.
 */
private val FAKE_KORE = """
	package io.github.ayfri.kore

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: () -> Unit) = Unit
	fun DataPack.advancement(name: String, block: DataPack.() -> Unit) = Unit
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.advancement
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function

	// Same name as a real Kore builder, but resolves elsewhere - must not show up.
	fun advancement(name: String, block: () -> Unit) = Unit

	fun main() {
		dataPack("mypack") {
			function("helper", directory = "utils") { }
			advancement("root") { }
		}

		advancement("impostor") { }
	}
""".trimIndent()

class KoreElementFinderTest : BasePlatformTestCase() {
	private fun find(): Map<String, KoreElement> {
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.configureByText("main.kt", MAIN_KT)

		// The Analysis API refuses to resolve on the EDT, where platform tests run, so mirror what production does.
		return ApplicationManager.getApplication()
			.executeOnPooledThread<List<KoreElement>> {
				runReadAction {
					KoreElementFinder.collect(project, GlobalSearchScope.allScope(project), EmptyProgressIndicator())
				}
			}
			.get()
			.associateBy(KoreElement::name)
	}

	fun testKeepsOnlyCallsResolvingIntoKore() {
		assertEquals(setOf("mypack", "helper", "root"), find().keys)
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
