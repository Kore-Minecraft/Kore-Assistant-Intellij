package io.github.ayfri.kore.koreassistant.gradle

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory

private const val VERSION = "2.14.0-26.2"
private const val MAIN_CLASS = "com.example.MainKt"
private const val PLUGIN_LINE = "id(\"io.github.ayfri.kore\") version \"$VERSION\""
private const val KORE_BLOCK = "kore {\n\tmainClass = \"$MAIN_CLASS\"\n}\n"

class KoreGradleSetupTest : BasePlatformTestCase() {
	private fun script(text: String) = myFixture.configureByText("build.gradle.kts", text) as KtFile

	private fun ktFile(name: String, text: String) = myFixture.configureByText(name, text) as KtFile

	fun testAppendsToExistingPluginsBlockWithItsIndentation() {
		val result = script(
			"""
			plugins {
			    kotlin("jvm") version "2.4.20"
			}

			dependencies {
			    implementation("io.github.ayfri.kore:kore:$VERSION")
			}
			""".trimIndent()
		).withKoreGradlePlugin(VERSION, MAIN_CLASS)

		val expected = """
			plugins {
			    kotlin("jvm") version "2.4.20"
			    $PLUGIN_LINE
			}

			dependencies {
			    implementation("io.github.ayfri.kore:kore:$VERSION")
			}
		""".trimIndent()
		assertEquals("$expected\n\n$KORE_BLOCK", result)
	}

	fun testCreatesPluginsBlockAfterImports() {
		val result = script("import java.time.Duration\n\ndependencies {\n}\n").withKoreGradlePlugin(VERSION, MAIN_CLASS)

		assertEquals("import java.time.Duration\n\nplugins {\n\t$PLUGIN_LINE\n}\n\ndependencies {\n}\n\n$KORE_BLOCK", result)
	}

	fun testCreatesPluginsBlockAtTopWithoutImports() {
		assertEquals("plugins {\n\t$PLUGIN_LINE\n}\n\n$KORE_BLOCK", script("").withKoreGradlePlugin(VERSION, MAIN_CLASS))
	}

	fun testMainClassIsTheFacadeOfATopLevelMain() {
		assertEquals("com.example.MainKt", ktFile("Main.kt", "package com.example\n\nfun main() {}\n").koreMainClass())
		assertEquals("com.example.App", ktFile("Main.kt", "@file:JvmName(\"App\")\npackage com.example\n\nfun main(args: Array<String>) {}\n").koreMainClass())
	}

	fun testMainClassIsTheObjectDeclaringMain() {
		val file = ktFile("Pack.kt", "package com.example\n\nobject Pack {\n\t@JvmStatic fun main(args: Array<String>) {}\n}\n")
		assertEquals("com.example.Pack", file.koreMainClass())
	}

	fun testMainClassFallsBackToTheFacadeWithoutMain() {
		assertEquals("com.example.PackKt", ktFile("Pack.kt", "package com.example\n\nfun build() {}\n").koreMainClass())
	}

	fun testWorldsPositionsInsideKoreBlock() {
		fun literalAt(text: String) = myFixture.configureByText("build.gradle.kts", text).findElementAt(myFixture.caretOffset)!!
			.parent.parent as KtStringTemplateExpression

		assertTrue(literalAt("kore {\n\tworlds = listOf(\"My<caret> World\")\n}").isKoreWorldsValue())
		assertTrue(literalAt("kore {\n\tworlds.add(\"My<caret> World\")\n}").isKoreWorldsValue())
		assertTrue(literalAt("kore.worlds.set(listOf(\"My<caret> World\"))").isKoreWorldsValue())
		assertFalse(literalAt("kore {\n\tpackName = \"my<caret>_pack\"\n}").isKoreWorldsValue())
		assertFalse(literalAt("other {\n\tworlds = listOf(\"My<caret> World\")\n}").isKoreWorldsValue())
	}

	fun testWorldsAreSavesFoldersHoldingALevelDat() {
		val minecraft = createTempDirectory("kore-minecraft")
		minecraft.resolve("saves/Zeta").createDirectories().resolve("level.dat").createFile()
		minecraft.resolve("saves/Alpha World").createDirectories().resolve("level.dat").createFile()
		minecraft.resolve("saves/Empty").createDirectories()
		minecraft.resolve("saves/stray.txt").createFile()

		assertEquals(listOf("Alpha World", "Zeta"), MinecraftWorlds.list(minecraft))
		assertEquals(emptyList<String>(), MinecraftWorlds.list(minecraft.resolve("missing")))
	}
}
