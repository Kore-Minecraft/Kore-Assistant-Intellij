package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind

/** Shown instead of a namespace/datapack name when the declaration sits outside any visible `dataPack { }`. */
const val UNKNOWN_DATA_PACK = "<unknown datapack>"

/**
 * PSI-free descriptor of a Kore element, identified by VFS url + offset.
 *
 * Holding no PSI keeps the cache cheap, safe across reindexing, and serializable, so the tool window
 * can move to the frontend in split mode without dragging the PSI graph across the RPC boundary.
 */
data class KoreElement(
	val kind: KoreDeclarationKind,
	val name: String,
	val namespace: String,
	val dataPackName: String,
	val directory: String?,
	/** The name (or namespace, or directory) is built at runtime, so what is shown is a template, not the final path. */
	val isDynamic: Boolean,
	val fileUrl: String,
	val fileName: String,
	val offset: Int,
	val lineNumber: Int,
) {
	/**
	 * Where Kore will write this element, relative to the datapack folder. Mirrors `Generator.getPathFromDataDir`
	 * and `Function.getFinalPath`; a tag's extra `<type>` level is baked into its kind's `tags/<type>` folder.
	 */
	val outputPath: String
		get() = when {
			kind == KoreDeclarationKind.DATA_PACK -> "$name/pack.mcmeta"
			kind.isFunction -> "data/$namespace/function/${directory.orEmpty().withTrailingSlash()}$name.mcfunction"
			else -> "data/$namespace/${kind.resourceFolder}/$name.json"
		}

	/** The `namespace:path` id used in-game and in other Kore calls, `#`-prefixed for tags. A datapack is a container, so it has none. */
	val resourceLocation: String?
		get() = when {
			kind == KoreDeclarationKind.DATA_PACK -> null
			kind.isFunction -> "$namespace:${directory.orEmpty().withTrailingSlash()}$name"
			kind.isTag -> "#$namespace:$name"
			else -> "$namespace:$name"
		}

	/** The command that runs or grants this resource, for the kinds that have one. */
	val command: String?
		get() {
			val location = resourceLocation ?: return null

			return when {
				kind.isFunction -> "/function $location"
				else -> when (kind.resourceFolder) {
					"advancement" -> "/advancement grant @s only $location"
					"damage_type" -> "/damage @s 1 $location"
					"dialog" -> "/dialog show @s $location"
					"enchantment" -> "/enchant @s $location"
					"item_modifier" -> "/item modify entity @s weapon.mainhand $location"
					"loot_table" -> "/loot give @s loot $location"
					"predicate" -> "/execute if predicate $location run say matched"
					"recipe" -> "/recipe give @s $location"
					"tags/function" -> "/function $location"
					"worldgen/configured_feature" -> "/place feature $location"
					"worldgen/placed_feature" -> "/place feature $location"
					"worldgen/structure" -> "/place structure $location"
					"worldgen/template_pool" -> "/place template $location"
					else -> null
				}
			}
		}

	// Only the tooltip needs it, so it is never computed for rows that are merely listed.
	val presentablePath: String get() = FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(fileUrl))

	/** `File.kt:42`, the stack-trace spelling the IDE turns back into a link when pasted. */
	val sourceLocation: String get() = if (lineNumber > 0) "$fileName:$lineNumber" else fileName
}

private fun String.withTrailingSlash() = if (isEmpty() || endsWith('/')) this else "$this/"
