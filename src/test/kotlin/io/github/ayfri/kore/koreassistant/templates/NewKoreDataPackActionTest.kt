package io.github.ayfri.kore.koreassistant.templates

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NewKoreDataPackActionTest : BasePlatformTestCase() {
	fun testIsRegisteredInTheNewMenu() {
		assertInstanceOf(ActionManager.getInstance().getAction("Kore.NewDataPack"), NewKoreDataPackAction::class.java)
	}

	fun testTemplateRendersAGeneratingMain() {
		val template = FileTemplateManager.getInstance(project).getInternalTemplate("Kore DataPack")
		val directory = myFixture.psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("datapacks"))!!
		val file = WriteCommandAction.writeCommandAction(project).compute<com.intellij.psi.PsiFile, RuntimeException> {
			CreateFileFromTemplateAction.createFileFromTemplate("MyPack", template, directory, null, false, emptyMap(), mapOf("PACK_NAME" to "my_pack"))
		}!!

		assertEquals("MyPack.kt", file.name)
		val text = file.text
		assertTrue(text, text.startsWith("package datapacks\n"))
		assertTrue(text, text.contains("dataPack(\"my_pack\") {"))
		assertTrue(text, text.contains("path(System.getProperty(\"kore.output\") ?: \"out\")"))
		assertTrue(text, text.contains("}.generate()"))
		assertFalse(text, text.contains("\${"))
	}
}
