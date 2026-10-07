package io.github.ayfri.kore.koreassistant.console

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationIndex
import io.github.ayfri.kore.koreassistant.index.KoreDeclarationKind
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService

/** `ns:path` or `#ns:path` standing on its own, so a URL (`https://...`) or a Windows path (`C:\...`) never matches. */
private val RESOURCE_LOCATION = Regex("""(?<![\w:/.#-])(#?)([a-z0-9_.-]+):([a-z0-9_.-]+(?:/[a-z0-9_.-]+)*)""")

/** Adds [KoreLocationFilter] to every console of a Kore project. */
class KoreConsoleFilterProvider : ConsoleFilterProvider {
	override fun getDefaultFilters(project: Project): Array<Filter> =
		if (project.service<KoreLibraryService>().isKoreProject) arrayOf(KoreLocationFilter(project)) else Filter.EMPTY_ARRAY
}

/**
 * Links the resource locations Kore prints while generating (`pack:helper: 2 unreachable line(s)...` from the optimization
 * passes, ids in generation errors) back to their declaration. A location the project does not declare stays plain text,
 * so a vanilla `minecraft:stone` or a `[kore:prune-empty-functions]` prefix is never turned into a dead link.
 */
class KoreLocationFilter(private val project: Project) : Filter {
	override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
		if (DumbService.isDumb(project)) return null
		val lineStart = entireLength - line.length
		val scope = GlobalSearchScope.projectScope(project)

		val items = RESOURCE_LOCATION.findAll(line).mapNotNull { match ->
			val (hash, namespace, path) = match.destructured
			val isTag = hash.isNotEmpty()
			val kinds = { kind: KoreDeclarationKind -> kind != KoreDeclarationKind.DATA_PACK && kind.isTag == isTag }
			val (file, data) = runReadActionBlocking { KoreDeclarationIndex.findByLocation(namespace, path, scope, kinds).firstOrNull() }
				?: return@mapNotNull null
			Filter.ResultItem(lineStart + match.range.first, lineStart + match.range.last + 1, HyperlinkInfo { OpenFileDescriptor(it, file, data.offset).navigate(true) })
		}.toList()

		return if (items.isEmpty()) null else Filter.Result(items)
	}
}
