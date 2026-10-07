package io.github.ayfri.kore.koreassistant.services

/**
 * Kore's Maven version is `"$koreVersion-$mcVersion"` (e.g. `2.8.0-26.1.2`), not plain semver. The Kore part never holds a
 * dash while the Minecraft part often does (`2.13.1-26.2-pre-3`, `2.9.0-26.2-snapshot-2`), so it splits on the *first* dash.
 */
data class KoreVersion(val kore: String, val minecraft: String) : Comparable<KoreVersion> {
	private val koreParts = kore.split('.').map { it.toIntOrNull() ?: 0 }

	/** Orders by the Kore part only: the Minecraft part of two builds of one Kore release is not an upgrade path. */
	override fun compareTo(other: KoreVersion) = (0..<maxOf(koreParts.size, other.koreParts.size))
		.map { koreParts.getOrElse(it) { 0 }.compareTo(other.koreParts.getOrElse(it) { 0 }) }
		.firstOrNull { it != 0 } ?: 0

	/** The Maven form, also the Gradle plugin's version since both are released together. */
	override fun toString() = "$kore-$minecraft"

	companion object {
		fun parse(rawVersion: String): KoreVersion? {
			val separatorIndex = rawVersion.indexOf('-')
			if (separatorIndex <= 0 || separatorIndex >= rawVersion.lastIndex) return null
			return KoreVersion(rawVersion.substring(0, separatorIndex), rawVersion.substring(separatorIndex + 1))
		}
	}
}
