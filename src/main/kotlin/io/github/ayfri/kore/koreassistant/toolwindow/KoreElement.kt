package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.ayfri.kore.koreassistant.KoreIcons
import javax.swing.Icon

enum class KoreElementKind {
	DATA_PACK,
	FUNCTION;

	// Resolved lazily so loading this enum never forces icon loading.
	val icon: Icon get() = if (this == DATA_PACK) KoreIcons.KORE else KoreIcons.FUNCTION
}

/**
 * PSI-free descriptor of a Kore element, identified by VFS url + offset.
 *
 * Holding no PSI keeps the cache cheap, safe across reindexing, and serializable, so the tool window
 * can move to the frontend in split mode without dragging the PSI graph across the RPC boundary.
 */
data class KoreElement(
	val kind: KoreElementKind,
	val name: String,
	val fileUrl: String,
	val fileName: String,
	val offset: Int,
	val lineNumber: Int,
) {
	// Only the tooltip needs it, so it is never computed for rows that are merely listed.
	val presentablePath: String get() = FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(fileUrl))
}
