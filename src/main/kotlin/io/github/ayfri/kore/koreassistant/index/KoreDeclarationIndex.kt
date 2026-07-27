package io.github.ayfri.kore.koreassistant.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import io.github.ayfri.kore.koreassistant.psi.LocalPropertyResolver
import io.github.ayfri.kore.koreassistant.psi.calleeName
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Syntactic file-based index of Kore declaration calls (`function("x") { }`, `advancement("y") { }`, ...),
 * keyed by declared name. Built once at indexing time and queried in microseconds, replacing the
 * `ReferencesSearch`-based lookup in `KoreToolWindowContent` (O(project), needs smart mode).
 *
 * No resolution happens here - [KoreDeclarationKind.byBuilderName] is a pure string lookup, so a user's own
 * unrelated `function("x")` can end up indexed too. Callers must confirm the match with `analyze { }` at
 * query time before trusting it (see `resolvesTo` in `psi/KoreCallUtils.kt`).
 *
 * Names are read with a [LocalPropertyResolver], which cannot leave the file being indexed, so a declaration
 * named from another file's constant is keyed by its source spelling. `KoreElementFinder` recomputes the
 * whole [KoreDeclarationData] once resolution is available - the index is a locator, not the source of truth.
 */
data object KoreDeclarationIndex : FileBasedIndexExtension<String, List<KoreDeclarationData>>() {
	private val NAME: ID<String, List<KoreDeclarationData>> =
		ID.create("io.github.ayfri.kore.koreassistant.declarations")

	override fun getName() = NAME

	override fun getIndexer() = DataIndexer<String, List<KoreDeclarationData>, FileContent> { content ->
		val file = content.psiFile as? KtFile ?: return@DataIndexer emptyMap()
		val result = HashMap<String, MutableList<KoreDeclarationData>>()
		val resolver = LocalPropertyResolver(file)

		file.accept(object : KtTreeVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				super.visitCallExpression(expression)
				val kind = expression.calleeName()?.let(KoreDeclarationKind::byBuilderName) ?: return
				val data = expression.koreDeclarationData(kind, resolver) ?: return
				result.getOrPut(data.name, ::mutableListOf) += data
			}
		})

		result
	}

	override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

	override fun getValueExternalizer() = KoreDeclarationDataExternalizer

	// Bump on ANY change to the indexer logic, KoreDeclarationKind, or KoreDeclarationDataExternalizer.
	override fun getVersion() = 3

	override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(KotlinFileType.INSTANCE)

	override fun dependsOnFileContent() = true

	fun find(project: Project, name: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)) =
		FileBasedIndex.getInstance().getValues(NAME, name, scope).flatten()

	/** Every declaration indexed in [scope], paired with the file it was found in. */
	fun findAll(project: Project, scope: GlobalSearchScope): List<Pair<VirtualFile, KoreDeclarationData>> {
		val index = FileBasedIndex.getInstance()
		val result = mutableListOf<Pair<VirtualFile, KoreDeclarationData>>()

		index.processAllKeys(NAME, Processor { key ->
			index.processValues(NAME, key, null, { file, declarations ->
				declarations.mapTo(result) { file to it }
				true
			}, scope)
			true
		}, scope, null)

		return result
	}
}
