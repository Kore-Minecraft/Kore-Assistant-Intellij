package io.github.ayfri.kore.koreassistant.hints

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor
import org.jetbrains.kotlin.psi.KtElement

private val FAKE_KORE = """
	package io.github.ayfri.kore

	import io.github.ayfri.kore.functions.Function

	class DataPack

	fun dataPack(name: String, block: DataPack.() -> Unit) = DataPack()
	fun DataPack.function(name: String, namespace: String = "", directory: String = "", block: Function.() -> Unit) = Unit
	fun DataPack.lootTable(fileName: String, block: DataPack.() -> Unit) = Unit
""".trimIndent()

private val MAIN_KT = """
	import io.github.ayfri.kore.dataPack
	import io.github.ayfri.kore.function
	import io.github.ayfri.kore.lootTable

	fun main() {
		dataPack("pack") {
			function("helper", directory = "utils") { }
			lootTable("chest") { }
		}
	}
""".trimIndent()

/** Records `text@offset` for every hint, each option enabled only when listed. */
private class RecordingSink(private val enabled: Set<String>) : InlayTreeSink {
	val hints = mutableListOf<String>()

	override fun addPresentation(position: InlayPosition, payloads: List<InlayPayload>?, tooltip: String?, hintFormat: HintFormat, builder: PresentationTreeBuilder.() -> Unit) {
		val texts = mutableListOf<String>()
		object : PresentationTreeBuilder {
			override fun list(builder: PresentationTreeBuilder.() -> Unit) = error("unused")
			override fun collapsibleList(state: CollapseState, expandedState: CollapsiblePresentationTreeBuilder.() -> Unit, collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit) = error("unused")
			override fun text(text: String, actionData: InlayActionData?) { texts += text }
			override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) = error("unused")
		}.builder()
		hints += "${texts.joinToString("")}@${(position as InlineInlayPosition).offset}"
	}

	override fun whenOptionEnabled(optionId: String, block: () -> Unit) {
		if (optionId in enabled) block()
	}
}

class KoreLocationInlayHintsProviderTest : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	private fun hints(vararg options: String): List<String> {
		myFixture.addFileToProject("kore.kt", FAKE_KORE)
		myFixture.addFileToProject("functions.kt", "package io.github.ayfri.kore.functions\n\nclass Function")
		myFixture.configureByText("Main.kt", MAIN_KT)
		val sink = RecordingSink(options.toSet())

		// The collector reads the confirmed elements, which resolve through the Analysis API: not allowed on the EDT.
		ApplicationManager.getApplication().executeOnPooledThread {
			runReadActionBlocking {
				val collector = KoreLocationInlayHintsProvider().createCollector(myFixture.file, myFixture.editor) as SharedBypassCollector
				PsiTreeUtil.findChildrenOfType(myFixture.file, KtElement::class.java).forEach { collector.collectFromElement(it, sink) }
			}
		}.get()

		return sink.hints.map { hint ->
			val offset = hint.substringAfterLast('@').toInt()
			"${hint.substringBeforeLast('@')} after '${myFixture.file.text.substring(offset - 7, offset)}'"
		}
	}

	fun testShowsResourceLocationsAfterTheArguments() = assertEquals(
		listOf("pack:utils/helper after 'utils\")'", "pack:chest after 'chest\")'"),
		hints(LOCATION_OPTION),
	)

	fun testShowsOutputPathsWhenAsked() = assertEquals(
		listOf("data/pack/function/utils/helper.mcfunction after 'utils\")'", "data/pack/loot_table/chest.json after 'chest\")'"),
		hints(PATH_OPTION),
	)
}
