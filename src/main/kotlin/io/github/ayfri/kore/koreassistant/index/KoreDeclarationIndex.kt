package io.github.ayfri.kore.koreassistant.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import io.github.ayfri.kore.koreassistant.psi.calleeName
import io.github.ayfri.kore.koreassistant.psi.enclosingDataPackName
import io.github.ayfri.kore.koreassistant.psi.firstStringLiteralArgument
import io.github.ayfri.kore.koreassistant.psi.namedStringArgument
import io.github.ayfri.kore.koreassistant.psi.namespaceAssignmentInBlock
import io.github.ayfri.kore.koreassistant.psi.positionalStringArgument
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

private const val NAMESPACE_PARAMETER_NAME = "namespace"
private const val DIRECTORY_PARAMETER_NAME = "directory"

// `function(name, namespace, directory) { }` - the only family passing them as parameters rather than in the block.
private const val NAMESPACE_PARAMETER_INDEX = 1
private const val DIRECTORY_PARAMETER_INDEX = 2

/**
 * Syntactic file-based index of Kore declaration calls (`function("x") { }`, `advancement("y") { }`, ...),
 * keyed by declared name. Built once at indexing time and queried in microseconds, replacing the
 * `ReferencesSearch`-based lookup in `KoreToolWindowContent` (O(project), needs smart mode).
 *
 * No resolution happens here - [KoreDeclarationKind.byBuilderName] is a pure string lookup, so a user's own
 * unrelated `function("x")` can end up indexed too. Callers must confirm the match with `analyze { }` at
 * query time before trusting it (see `resolvesTo` in `psi/KoreCallUtils.kt`).
 */
data object KoreDeclarationIndex : FileBasedIndexExtension<String, List<KoreDeclarationData>>() {
	private val NAME: ID<String, List<KoreDeclarationData>> =
		ID.create("io.github.ayfri.kore.koreassistant.declarations")

	override fun getName() = NAME

	override fun getIndexer() = DataIndexer<String, List<KoreDeclarationData>, FileContent> { content ->
		val file = content.psiFile as? KtFile ?: return@DataIndexer emptyMap()
		val result = HashMap<String, MutableList<KoreDeclarationData>>()

		file.accept(object : KtTreeVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				super.visitCallExpression(expression)
				val kind = expression.calleeName()?.let(KoreDeclarationKind::byBuilderName) ?: return
				val name = expression.firstStringLiteralArgument() ?: return
				result.getOrPut(name, ::mutableListOf) += expression.toDeclarationData(kind, name)
			}
		})

		result
	}

	override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

	override fun getValueExternalizer() = KoreDeclarationDataExternalizer

	// Bump on ANY change to the indexer logic, KoreDeclarationKind, or KoreDeclarationDataExternalizer.
	override fun getVersion() = 2

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

private fun KtCallExpression.toDeclarationData(kind: KoreDeclarationKind, name: String): KoreDeclarationData {
	val namespaceParameter = namedStringArgument(NAMESPACE_PARAMETER_NAME)
		?: if (kind.isFunction) positionalStringArgument(NAMESPACE_PARAMETER_INDEX) else null

	val directoryParameter = if (!kind.isFunction) null else namedStringArgument(DIRECTORY_PARAMETER_NAME)
		?: positionalStringArgument(DIRECTORY_PARAMETER_INDEX)

	return KoreDeclarationData(
		kind = kind,
		name = name,
		namespace = namespaceParameter ?: namespaceAssignmentInBlock(),
		directory = directoryParameter,
		// A `dataPack("x") { }` is its own datapack; everything else inherits the enclosing one, if visible.
		dataPackName = if (kind == KoreDeclarationKind.DATA_PACK) name else enclosingDataPackName(),
		offset = textOffset,
	)
}
