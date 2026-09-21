package io.github.ayfri.kore.koreassistant.gradle

import io.github.ayfri.kore.koreassistant.psi.calleeName
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

private const val PLUGINS_BLOCK = "plugins"
private const val MAIN_FUNCTION = "main"

/**
 * The `kore.mainClass` value for [this] file: its facade class (`com.example.MainKt`, honouring `@file:JvmName`) when it
 * holds a top-level `main`, else the first `object` declaring one, else the facade anyway so the user only has to add `main`.
 */
fun KtFile.koreMainClass(): String {
	val facade = JvmFileClassUtil.getFileClassInfoNoResolve(this).facadeClassFqName.asString()
	if (declarations.any { it.isMainFunction() }) return facade
	return declarations.filterIsInstance<KtObjectDeclaration>()
		.firstOrNull { obj -> obj.declarations.any { it.isMainFunction() } }
		?.fqName?.asString() ?: facade
}

private fun KtDeclaration.isMainFunction() =
	this is KtNamedFunction && name == MAIN_FUNCTION && receiverTypeReference == null && valueParameters.size <= 1

/**
 * The text of the build script with the Kore Gradle plugin applied and a `kore { mainClass }` block appended, computed
 * without touching the file so the intention can show the diff first. The plugin line lands at the end of the existing
 * `plugins { }` block, indented like its neighbours, or in a new block after the imports.
 */
fun KtFile.withKoreGradlePlugin(version: String, mainClass: String): String {
	val text = text
	val pluginLine = "id(\"$KORE_GRADLE_PLUGIN_ID\") version \"$version\""
	// A script wraps each top-level expression in a KtScriptInitializer.
	val pluginsLiteral = script?.blockExpression?.statements
		?.map { (it as? KtScriptInitializer)?.body ?: it }
		?.filterIsInstance<KtCallExpression>()
		?.firstOrNull { it.calleeName() == PLUGINS_BLOCK }
		?.lambdaArguments?.firstOrNull()?.getLambdaExpression()?.functionLiteral

	val withPlugin = if (pluginsLiteral != null) {
		val rBrace = pluginsLiteral.rBrace ?: return text
		val firstStatement = pluginsLiteral.bodyExpression?.statements?.firstOrNull()
		val indent =
			firstStatement?.let { text.substring(text.lastIndexOf('\n', it.startOffset) + 1, it.startOffset) } ?: "\t"
		text.take(rBrace.startOffset).trimEnd() + "\n$indent$pluginLine\n" + text.substring(rBrace.startOffset)
	} else {
		val importsEnd = importList?.takeIf { it.imports.isNotEmpty() }?.endOffset ?: 0
		val separator = if (importsEnd > 0) "\n\n" else ""
		text.take(importsEnd) + separator + "plugins {\n\t$pluginLine\n}\n\n" + text.substring(importsEnd).trimStart()
	}

	return withPlugin.trimEnd() + "\n\nkore {\n\tmainClass = \"$mainClass\"\n}\n"
}
