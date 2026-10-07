package io.github.ayfri.kore.koreassistant.diagnostic

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.URLDecoder

private fun String.queryParams() = URI(this).rawQuery.split('&').associate {
	val (key, value) = it.split('=', limit = 2)
	key to URLDecoder.decode(value, Charsets.UTF_8)
}

/** The prefilled issue URL: field ids, title from the first trace line, and a trace cut to fit the URL limit. */
class BugIssueUrlTest : BasePlatformTestCase() {
	fun testPrefillsTheBugFormFields() {
		val trace = "java.lang.IllegalStateException: boom\n\tat a.b.C.d(C.kt:1)"
		val params = bugIssueUrl(trace, "0.0.6", "IntelliJ IDEA 2026.2", "It broke & stayed broken").queryParams()

		assertEquals("bug.yml", params["template"])
		assertEquals("[BUG] java.lang.IllegalStateException: boom", params["title"])
		assertEquals("0.0.6", params["plugin-version"])
		assertEquals("IntelliJ IDEA 2026.2", params["ide-version"])
		assertEquals("It broke & stayed broken", params["what-happened"])
		assertEquals(trace, params["stacktrace"])
	}

	fun testCutsLongTracesAtALineBoundaryUnderTheLimit() {
		val lines = listOf("java.lang.RuntimeException: deep") + List(2000) { "\tat io.github.ayfri.Frame$it.call(Frame.kt:$it)" }
		val url = bugIssueUrl(lines.joinToString("\n"), "0.0.6", "env", "")
		val kept = url.queryParams().getValue("stacktrace").lines()

		assertTrue(url.length <= MAX_ISSUE_URL_LENGTH)
		assertTrue(kept.size in 2..<lines.size)
		assertEquals(lines.take(kept.size), kept)
	}
}
