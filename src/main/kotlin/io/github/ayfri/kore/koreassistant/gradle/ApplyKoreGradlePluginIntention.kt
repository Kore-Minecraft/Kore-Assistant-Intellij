package io.github.ayfri.kore.koreassistant.gradle

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.parentOfType
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.isAncestor
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Offered on a `dataPack(...)` call in a Gradle module whose sync has not seen the Kore Gradle plugin: applies
 * `id("io.github.ayfri.kore") version "<Kore version>"` and a `kore { mainClass }` block to `build.gradle.kts`, after
 * showing the diff, then schedules the sync. Kotlin DSL only: Kore projects are Kotlin, and the Groovy DSL is rare there.
 */
class ApplyKoreGradlePluginIntention : PsiElementBaseIntentionAction() {
	override fun getFamilyName() = "Kore"
	override fun getText() = "Apply the Kore Gradle plugin"
	override fun startInWriteAction() = false

	override fun generatePreview(project: Project, editor: Editor, file: PsiFile) =
		IntentionPreviewInfo.Html("Adds the plugin and a <code>kore { }</code> block to <code>build.gradle.kts</code>, after showing the diff.")

	override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
		element.dataPackCall() ?: return false
		if (!project.service<KoreLibraryService>().isKoreProject) return false
		val gradleProjectPath = ModuleUtilCore.findModuleForPsiElement(element)?.gradleProjectPath() ?: return false
		if (project.hasKoreGradlePlugin(gradleProjectPath)) return false

		// Applied but not synced yet: the marker is one sync away, nothing to add.
		val script = buildScript(project, gradleProjectPath) ?: return false
		return !script.text.contains("\"$KORE_GRADLE_PLUGIN_ID\"")
	}

	override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
		val version = project.service<KoreLibraryService>().version ?: return
		val gradleProjectPath = ModuleUtilCore.findModuleForPsiElement(element)?.gradleProjectPath() ?: return
		val script = buildScript(project, gradleProjectPath) ?: return
		val document = FileDocumentManager.getInstance().getDocument(script.virtualFile) ?: return
		val mainClass = (element.containingFile as? KtFile)?.koreMainClass() ?: return
		val newText = script.withKoreGradlePlugin(version.toString(), mainClass)

		val contents = DiffContentFactory.getInstance()
		val request = SimpleDiffRequest(
			text,
			contents.create(project, document),
			contents.create(project, newText, script.virtualFile),
			"Current build.gradle.kts",
			"With the Kore Gradle plugin",
		)
		if (!ApplyDiffDialog(project, request).showAndGet()) return

		WriteCommandAction.runWriteCommandAction(project, text, null, { document.setText(newText) }, script)
		FileDocumentManager.getInstance().saveDocument(document)
		ExternalSystemProjectTracker.getInstance(project).scheduleProjectRefresh()
	}

	/** The `dataPack` call whose callee or argument list the caret sits in; the block body is not the call. */
	private fun PsiElement.dataPackCall(): KtCallExpression? =
		parentOfType<KtCallExpression>(withSelf = true)
			?.takeIf { it.calleeName() == KoreNames.DATA_PACK.shortName().asString() }
			?.takeIf { it.calleeExpression.isAncestor(this) || it.valueArgumentList.isAncestor(this) }

	private fun buildScript(project: Project, gradleProjectPath: String): KtFile? {
		val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(gradleProjectPath, GradleConstants.KOTLIN_DSL_SCRIPT_NAME)) ?: return null
		return PsiManager.getInstance(project).findFile(file) as? KtFile
	}
}

/** A modal diff of the proposed build script, OK meaning "write it": the build file is the user's, never edited blind. */
private class ApplyDiffDialog(project: Project, request: SimpleDiffRequest) : DialogWrapper(project) {
	private val panel = DiffManager.getInstance().createRequestPanel(project, disposable, window)

	init {
		title = "Apply the Kore Gradle Plugin"
		setOKButtonText("Apply")
		panel.setRequest(request)
		init()
	}

	override fun createCenterPanel(): JComponent = panel.component
	override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocusedComponent
}
