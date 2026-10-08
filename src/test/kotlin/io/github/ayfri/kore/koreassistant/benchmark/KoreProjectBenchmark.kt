package io.github.ayfri.kore.koreassistant.benchmark

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ayfri.kore.koreassistant.KoreProjectDescriptor
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.inspections.DuplicateDeclarationInspection
import io.github.ayfri.kore.koreassistant.inspections.UnresolvedFunctionReferenceInspection
import io.github.ayfri.kore.koreassistant.inspections.koreElements
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElement
import io.github.ayfri.kore.koreassistant.toolwindow.KoreElementFinder
import java.io.File
import kotlin.math.sqrt

private val SOURCES = System.getenv("KORE_BENCHMARK_SOURCES")
private val LABEL = System.getenv("KORE_BENCHMARK_LABEL") ?: "run"
private val MODULES = listOf("kore/src", "helpers/src", "oop/src")
private const val SAMPLES = 12
private const val WARMUP = 3

/**
 * Times what the plugin does on Kore's own repository, the worst case it meets: set `KORE_BENCHMARK_SOURCES` to a Kore
 * checkout to run it, otherwise it does nothing. Results and the collected elements go to `build/benchmark/<label>.txt`.
 */
class KoreProjectBenchmark : BasePlatformTestCase() {
	override fun getProjectDescriptor() = KoreProjectDescriptor

	override fun getTestDataPath(): String = SOURCES.orEmpty()

	private val report = StringBuilder()

	fun testKoreRepository() {
		if (SOURCES == null) return
		for (module in MODULES) myFixture.copyDirectoryToProject(module, module)

		val (first, elements) = timed { collect() }
		log("elements: ${elements.size}, files: ${elements.map(KoreElement::fileUrl).distinct().size}, first collect: ${first.ms()} ms")
		samples("collect again, nothing changed") { collect() }

		// The edited file: the one holding the most declarations, edited inside its first function body.
		val fileUrl = elements.groupBy(KoreElement::fileUrl).maxBy { it.value.size }.key
		val file = myFixture.findFileInTempDir(fileUrl.substringAfter("temp:///src/"))
		myFixture.configureFromExistingVirtualFile(file)
		val function = elements.first { it.fileUrl == fileUrl && it.kind == KoreDeclarationKind.FUNCTION }
		val editOffset = myFixture.editor.document.text.indexOf('{', function.offset) + 1
		var inserted = false
		val edit = {
			WriteCommandAction.runWriteCommandAction(project) {
				val document = myFixture.editor.document
				if (inserted) document.deleteString(editOffset, editOffset + 1) else document.insertString(editOffset, " ")
				PsiDocumentManager.getInstance(project).commitDocument(document)
			}
			inserted = !inserted
		}
		log("edited: ${file.name}")

		samples("koreElements() after a one-character edit in a function body", before = edit) { onPooledThread { project.koreElements() } }

		myFixture.enableInspections(UnresolvedFunctionReferenceInspection(), DuplicateDeclarationInspection())
		samples("highlighting the edited file after a one-character edit", before = edit) { myFixture.doHighlighting() }

		val output = File("build/benchmark/$LABEL.txt").apply { parentFile.mkdirs() }
		output.writeText(report.toString() + "\n" + elements.map { it.copy(lineNumber = 0).toString() }.sorted().joinToString("\n"))
		println(report)
	}

	private fun collect() = onPooledThread { KoreElementFinder.collect(project) }

	private fun <T> onPooledThread(block: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(block) }.get()

	private fun samples(name: String, before: () -> Unit = {}, block: () -> Unit) {
		val times = (1..WARMUP + SAMPLES).map { before(); timed(block).first }.drop(WARMUP).map { it / 1e6 }
		val mean = times.average()
		val stdev = sqrt(times.sumOf { (it - mean) * (it - mean) } / times.size)
		log("$name: median ${times.sorted()[times.size / 2].fmt()} ms, mean ${mean.fmt()} ± ${stdev.fmt()} ms, min ${times.min().fmt()}, max ${times.max().fmt()} (n=${times.size})")
	}

	private fun <T> timed(block: () -> T): Pair<Long, T> {
		val start = System.nanoTime()
		val result = block()
		return System.nanoTime() - start to result
	}

	private fun log(line: String) {
		report.appendLine(line)
	}
}

private fun Long.ms() = this / 1_000_000
private fun Double.fmt() = "%.1f".format(this)
