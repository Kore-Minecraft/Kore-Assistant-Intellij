package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val FAKE_KORE = """
	package io.github.ayfri.kore

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun dataPack(name: String) = DataPack()
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: () -> Unit) = Unit

	// The light test project has no stdlib, so the scope function the layout under test relies on is declared here.
	fun <T> T.apply(block: T.() -> Unit): T = this
""".trimIndent()

/**
 * Two datapacks on purpose: with a single one the "project has only one datapack" fallback would attach
 * everything anyway and none of these layouts would actually be under test.
 */
private val MAIN_KT = """
	import io.github.ayfri.kore.DataPack
	import io.github.ayfri.kore.apply
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function

	fun main() {
		dataPack("first") {
			function("inline") { }
			fromParameter(this)
		}

		dataPack("second").apply {
			function("in_apply") { }
		}
	}

	fun fromParameter(pack: DataPack) = pack.function("param_based") { }
""".trimIndent()

class KoreDataPackLayoutTest : BasePlatformTestCase() {
	private fun find(): Map<String, KoreElement> {
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.configureByText("Main.kt", MAIN_KT)

		return ApplicationManager.getApplication()
			.executeOnPooledThread<List<KoreElement>> {
				runReadAction {
					KoreElementFinder.collect(project, GlobalSearchScope.allScope(project), EmptyProgressIndicator())
				}
			}
			.get()
			.associateBy(KoreElement::name)
	}

	fun testAttachesDeclarationsNestedInTheBuilderBlock() {
		assertEquals("first", find().getValue("inline").dataPackName)
	}

	fun testAttachesDeclarationsWrittenInsideAScopeFunction() {
		assertEquals("second", find().getValue("in_apply").dataPackName)
	}

	fun testFollowsHelpersTakingTheDataPackAsAParameter() {
		assertEquals("first", find().getValue("param_based").dataPackName)
	}
}
