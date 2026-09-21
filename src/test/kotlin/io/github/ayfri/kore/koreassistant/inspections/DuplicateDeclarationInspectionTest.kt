package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_KORE = """
	package io.github.ayfri.kore

	class DataPack {
		var namespace: String? = null
	}

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack().apply(block)
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: () -> Unit) = Unit
	fun DataPack.predicate(fileName: String, block: DataPack.() -> Unit) = Unit
	fun <T> T.apply(block: T.() -> Unit): T = this
""".trimIndent()

private val OTHER_KT = """
	import io.github.ayfri.kore.DataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.predicate

	fun DataPack.extras(id: String) {
		predicate("shared") { }
		function("dynamic_${'$'}id") { }
		function("dynamic_${'$'}id") { }
	}
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.predicate

	fun main() {
		dataPack("pack") {
			function("twice") { }
			function("twice") { }
			function("twice", namespace = "other") { }
			function("twice", directory = "sub") { }
			predicate("shared") { }
			extras("x")
		}
	}
""".trimIndent()

private const val OVERWRITE = "the last one generated overwrites the others"

/** The light project has no stdlib, so `String` alone is an error; only this inspection's warnings are asserted on. */
class DuplicateDeclarationInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	private fun warnings(): Set<Pair<String, String?>> {
		myFixture.enableInspections(DuplicateDeclarationInspection::class.java)
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("Other.kt", OTHER_KT)
		myFixture.configureByText("Main.kt", MAIN_KT)

		return myFixture.doHighlighting()
			.filter { it.severity == HighlightSeverity.WARNING && it.inspectionToolId == "KoreDuplicateDeclaration" }
			.map { "${it.text}@${myFixture.editor.document.getLineNumber(it.startOffset) + 1}" to it.description }
			.toSet()
	}

	fun testReportsEveryDeclarationSharingAnOutputPathAndNothingElse() {
		assertEquals(
			setOf(
				"\"twice\"@7" to "'pack:twice' is also declared at Main.kt:8, $OVERWRITE",
				"\"twice\"@8" to "'pack:twice' is also declared at Main.kt:7, $OVERWRITE",
				"\"shared\"@11" to "'pack:shared' is also declared at Other.kt:6, $OVERWRITE",
			),
			warnings(),
		)
	}
}
