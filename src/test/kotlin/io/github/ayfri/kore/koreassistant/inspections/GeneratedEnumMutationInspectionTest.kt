package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

private val FAKE_REGISTRIES = """
	package io.github.ayfri.kore.generated

	enum class Items {
		DIAMOND_SWORD;

		var components: String? = null
	}

	enum class Blocks {
		STONE;

		val states = mutableMapOf<String, String>()
	}
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.generated.Blocks
	import io.github.ayfri.kore.generated.Items
	import io.github.ayfri.kore.generated.Items.DIAMOND_SWORD

	enum class Mine { ENTRY; var value = 0 }

	fun main() {
		Items.DIAMOND_SWORD.components = "x"
		DIAMOND_SWORD.components += "y"
		Blocks.STONE.states["axis"] = "y"
		Mine.ENTRY.value = 1
		val copy = Items.DIAMOND_SWORD.components
	}
""".trimIndent()

class GeneratedEnumMutationInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	fun testReportsAssignmentsOnGeneratedEntriesOnly() {
		myFixture.enableInspections(GeneratedEnumMutationInspection::class.java)
		myFixture.addFileToProject("registries.kt", FAKE_REGISTRIES)
		myFixture.configureByText("Main.kt", MAIN_KT)

		val errors = myFixture.doHighlighting().filter { it.inspectionToolId == "KoreGeneratedEnumMutation" }.map { it.text }
		assertEquals(listOf("Items.DIAMOND_SWORD.components", "DIAMOND_SWORD.components", "Blocks.STONE.states"), errors)
	}
}
