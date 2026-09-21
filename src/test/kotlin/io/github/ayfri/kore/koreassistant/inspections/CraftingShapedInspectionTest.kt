package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_KORE = """
	package io.github.ayfri.kore.features.recipes.types

	class Recipes
	class CraftingShaped
	class Keys {
		infix fun String.to(item: String) = Unit
	}

	fun Recipes.craftingShaped(name: String, block: CraftingShaped.() -> Unit) = Unit
	fun CraftingShaped.pattern(vararg lines: String) = Unit
	fun CraftingShaped.patternLine(line: String) = Unit
	fun CraftingShaped.key(key: String, item: String) = Unit
	fun CraftingShaped.keys(block: Keys.() -> Unit) = Unit
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.features.recipes.types.Recipes
	import io.github.ayfri.kore.features.recipes.types.craftingShaped
	import io.github.ayfri.kore.features.recipes.types.key
	import io.github.ayfri.kore.features.recipes.types.keys
	import io.github.ayfri.kore.features.recipes.types.pattern
	import io.github.ayfri.kore.features.recipes.types.patternLine

	const val ROW = "GGG"

	fun Recipes.recipes(id: String) {
		craftingShaped("valid") {
			pattern(ROW, "GAG")
			patternLine(" G ")
			key("G", "gold")
			keys { "A" to "apple" }
		}
		craftingShaped("broken") {
			pattern("GGGG", "GG", "GAG", "GGG")
			pattern("")
			key("G", "gold")
			key("X", "unused")
			key("AB", "two")
			key(" ", "space")
		}
		craftingShaped("dynamic") {
			pattern(id)
			key("Z", "unknown")
		}
	}
""".trimIndent()

class CraftingShapedInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	private fun problems(): Set<Triple<String, HighlightSeverity, String?>> {
		myFixture.enableInspections(CraftingShapedInspection::class.java)
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.configureByText("Main.kt", MAIN_KT)

		return myFixture.doHighlighting()
			.filter { it.inspectionToolId == "KoreCraftingShaped" }
			.map { Triple(it.text, it.severity, it.description) }
			.toSet()
	}

	fun testReportsGridShapeAndKeyMismatches() {
		assertEquals(
			setOf(
				Triple("\"GGGG\"", HighlightSeverity.ERROR, "A pattern row has at most 3 characters"),
				Triple("\"GG\"", HighlightSeverity.ERROR, "Every row must be as wide as the first one (4)"),
				Triple("\"GAG\"", HighlightSeverity.ERROR, "Every row must be as wide as the first one (4)"),
				Triple("\"GGG\"", HighlightSeverity.ERROR, "A shaped recipe has at most 3 rows"),
				Triple("\"\"", HighlightSeverity.ERROR, "A shaped recipe has at most 3 rows"),
				Triple("\"GAG\"", HighlightSeverity.ERROR, "No key defined for 'A'"),
				Triple("\"X\"", HighlightSeverity.WEAK_WARNING, "Key 'X' is not used in the pattern"),
				Triple("\"AB\"", HighlightSeverity.ERROR, "A key is a single character"),
				Triple("\" \"", HighlightSeverity.ERROR, "A space is an empty slot and cannot be a key"),
			),
			problems(),
		)
	}
}
