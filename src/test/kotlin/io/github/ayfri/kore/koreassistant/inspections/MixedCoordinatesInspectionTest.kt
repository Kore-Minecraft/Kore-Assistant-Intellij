package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor

/** Kore's shapes, trimmed: a component type, the `.pos`-style extensions, `pos(...)` and the triple builders. */
private val FAKE_MATHS = """
	package io.github.ayfri.kore.arguments.numbers

	class PosNumber(val value: Double, val type: Type = Type.WORLD) {
		enum class Type { LOCAL, RELATIVE, WORLD }
		val local get() = PosNumber(value, Type.LOCAL)
		val relative get() = PosNumber(value, Type.RELATIVE)
	}

	val Number.pos get() = PosNumber(0.0)
	val Number.localPos get() = PosNumber(0.0, PosNumber.Type.LOCAL)
	val Number.relativePos get() = PosNumber(0.0, PosNumber.Type.RELATIVE)
	fun pos(value: Number = 0, type: PosNumber.Type = PosNumber.Type.RELATIVE) = PosNumber(0.0, type)
""".trimIndent()

private val FAKE_VEC3 = """
	package io.github.ayfri.kore.arguments.maths

	import io.github.ayfri.kore.arguments.numbers.PosNumber

	class Vec3
	fun vec3(x: Number, y: Number, z: Number) = Vec3()
	fun vec3(x: PosNumber, y: PosNumber, z: PosNumber) = Vec3()
	fun vec3(x: PosNumber.Type, y: PosNumber.Type, z: PosNumber.Type) = Vec3()
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.arguments.maths.vec3
	import io.github.ayfri.kore.arguments.numbers.PosNumber
	import io.github.ayfri.kore.arguments.numbers.localPos
	import io.github.ayfri.kore.arguments.numbers.pos
	import io.github.ayfri.kore.arguments.numbers.relativePos

	fun main(dynamic: PosNumber) {
		vec3(1.localPos, 2.pos, 0.relativePos)
		vec3(PosNumber.Type.LOCAL, PosNumber.Type.RELATIVE, PosNumber.Type.LOCAL)
		vec3(1.pos.local, pos(2), 3.localPos)
		vec3(0.localPos, 1.localPos, 2.pos.local)
		vec3(1, 2, 3)
		vec3(1.relativePos, 2.pos, pos(3))
		vec3(1.localPos, dynamic, 2.localPos)
	}
""".trimIndent()

class MixedCoordinatesInspectionTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	fun testReportsOnlyTriplesMixingLocalWithOtherTypes() {
		myFixture.enableInspections(MixedCoordinatesInspection::class.java)
		myFixture.addFileToProject("numbers.kt", FAKE_MATHS)
		myFixture.addFileToProject("vec3.kt", FAKE_VEC3)
		myFixture.configureByText("Main.kt", MAIN_KT)

		val lines = myFixture.doHighlighting()
			.filter { it.inspectionToolId == "KoreMixedCoordinates" }
			.map { myFixture.editor.document.getLineNumber(it.startOffset) + 1 }
		assertEquals(listOf(8, 9, 10), lines)
	}
}
