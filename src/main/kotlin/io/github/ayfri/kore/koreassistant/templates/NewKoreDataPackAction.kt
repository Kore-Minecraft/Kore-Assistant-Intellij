package io.github.ayfri.kore.koreassistant.templates

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService

private const val TEMPLATE_NAME = "Kore DataPack"
private const val PACK_NAME_PROPERTY = "PACK_NAME"
private val WORD_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

/** New > Kore DataPack, offered in Kore projects only: a `main` generating a datapack, ready for the Kore Gradle plugin. */
class NewKoreDataPackAction : CreateFileFromTemplateAction(TEMPLATE_NAME, "Create a Kotlin file generating a Kore datapack", KoreIcons.KORE), DumbAware {
	override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
		builder.setTitle("New Kore DataPack").addKind(TEMPLATE_NAME, KoreIcons.KORE, TEMPLATE_NAME)
	}

	override fun getActionName(directory: PsiDirectory, newName: String, templateName: String) = "Create Kore DataPack $newName"

	override fun isAvailable(dataContext: DataContext) =
		super.isAvailable(dataContext) && CommonDataKeys.PROJECT.getData(dataContext)?.service<KoreLibraryService>()?.isKoreProject == true

	/** The pack is named after the file in Minecraft's snake case: `MyPack.kt` declares `dataPack("my_pack")`. */
	override fun createFileFromTemplate(name: String, template: FileTemplate, dir: PsiDirectory): PsiFile? {
		val packName = name.substringAfterLast('/').replace(WORD_BOUNDARY, "_").lowercase()
		return createFileFromTemplate(name, template, dir, defaultTemplateProperty, true, emptyMap(), mapOf(PACK_NAME_PROPERTY to packName))
	}
}
