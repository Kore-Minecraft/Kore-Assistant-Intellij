package io.github.ayfri.kore.koreassistant.services

/**
 * Kore's Maven version is `"$koreVersion-$mcVersion"` (e.g. `2.8.0-26.1.2`), not plain semver -
 * always split on the *last* dash, never compare the raw string as a semver.
 */
data class KoreVersion(val kore: String, val minecraft: String) {
	companion object {
		fun parse(rawVersion: String): KoreVersion? {
			val separatorIndex = rawVersion.lastIndexOf('-')
			if (separatorIndex <= 0 || separatorIndex >= rawVersion.lastIndex) return null
			return KoreVersion(rawVersion.substring(0, separatorIndex), rawVersion.substring(separatorIndex + 1))
		}
	}
}
