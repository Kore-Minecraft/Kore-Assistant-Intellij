package io.github.ayfri.kore.koreassistant.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Mirrors the Kore Gradle plugin's `MinecraftLocator`, so the completed world names are the ones `koreLink` resolves. */
object MinecraftWorlds {
	private const val DIRECTORY_ENVIRONMENT_VARIABLE = "MINECRAFT_DIR"

	/** `MINECRAFT_DIR` first, then the standard install location of the current OS. */
	fun defaultDirectory(): Path {
		System.getenv(DIRECTORY_ENVIRONMENT_VARIABLE)?.takeIf(String::isNotBlank)?.let { return Path.of(it) }

		val home = Path.of(System.getProperty("user.home"))
		val os = System.getProperty("os.name").lowercase()
		return when {
			"win" in os -> Path.of(System.getenv("APPDATA")?.takeIf(String::isNotBlank) ?: home.toString(), ".minecraft")
			"mac" in os || "darwin" in os -> home.resolve("Library/Application Support/minecraft")
			else -> home.resolve(".minecraft")
		}
	}

	/** Names of the worlds under `<minecraftDirectory>/saves`, identified by their `level.dat`, sorted alphabetically. */
	fun list(minecraftDirectory: Path): List<String> {
		val saves = minecraftDirectory.resolve("saves")
		if (!Files.isDirectory(saves)) return emptyList()
		return saves.listDirectoryEntries().filter { it.isDirectory() && it.resolve("level.dat").isRegularFile() }.map { it.name }.sorted()
	}
}
