package io.github.ayfri.kore.koreassistant.services

import junit.framework.TestCase

class KoreVersionTest : TestCase() {
	fun testSplitsOnTheFirstDashSinceMinecraftVersionsHoldDashes() {
		assertEquals(KoreVersion("2.8.0", "26.1.2"), KoreVersion.parse("2.8.0-26.1.2"))
		assertEquals(KoreVersion("2.13.1", "26.2-pre-3"), KoreVersion.parse("2.13.1-26.2-pre-3"))
		assertEquals(KoreVersion("2.9.0", "26.2-snapshot-2"), KoreVersion.parse("2.9.0-26.2-snapshot-2"))
		assertEquals("2.13.1-26.2-pre-3", KoreVersion.parse("2.13.1-26.2-pre-3").toString())
	}

	fun testRejectsVersionsWithoutBothParts() {
		assertNull(KoreVersion.parse("2.8.0"))
		assertNull(KoreVersion.parse("2.8.0-"))
		assertNull(KoreVersion.parse("-26.2"))
	}

	fun testOrdersNumericallyOnTheKorePart() {
		val versions = listOf("2.15.0-26.2", "2.9.0-26.2-snapshot-2", "2.14.0-26.2", "2.10.1-26.1").map { KoreVersion.parse(it)!! }
		assertEquals(listOf("2.9.0", "2.10.1", "2.14.0", "2.15.0"), versions.sorted().map { it.kore })
		assertEquals(0, KoreVersion.parse("2.14.0-26.1")!!.compareTo(KoreVersion.parse("2.14.0-26.2")!!))
	}
}
