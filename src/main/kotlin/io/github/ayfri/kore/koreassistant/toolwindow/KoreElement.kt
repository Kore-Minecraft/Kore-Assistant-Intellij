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
	val fileUrl: String,
	val fileName: String,
	val offset: Int,
	val lineNumber: Int,
) {
	/**
	 * Where Kore will write this element, relative to the datapack folder. Mirrors `Generator.getPathFromDataDir`
	 * and `Function.getFinalPath`; tags are not indexed yet, so their extra `<type>` level never shows up here.
	 */
	val outputPath: String
		get() = when {
			kind == KoreDeclarationKind.DATA_PACK -> "$name/pack.mcmeta"
			kind.isFunction -> "data/$namespace/function/${directory.orEmpty().withTrailingSlash()}$name.mcfunction"
			else -> "data/$namespace/${kind.resourceFolder}/$name.json"
		}

	// Only the tooltip needs it, so it is never computed for rows that are merely listed.
	val presentablePath: String get() = FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(fileUrl))
}

private fun String.withTrailingSlash() = if (isEmpty() || endsWith('/')) this else "$this/"
