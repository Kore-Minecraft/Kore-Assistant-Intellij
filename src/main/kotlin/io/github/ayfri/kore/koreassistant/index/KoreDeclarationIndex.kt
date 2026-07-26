package io.github.ayfri.kore.koreassistant.index

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import io.github.ayfri.kore.koreassistant.psi.firstStringLiteralArgument
import io.github.ayfri.kore.koreassistant.psi.namespaceHint
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Syntactic file-based index of Kore declaration calls (`function("x") { }`, `advancement("y") { }`, ...),
 * keyed by declared name. Built once at indexing time and queried in microseconds, replacing the
 * `ReferencesSearch`-based lookup in `KoreToolWindowContent` (O(project), needs smart mode).
 *
 * No resolution happens here - [KoreDeclarationKind.byBuilderName] is a pure string lookup, so a user's own
 * unrelated `function("x")` can end up indexed too. Callers must confirm the match with `analyze { }` at
 * query time before trusting it (see `resolvesTo` in `psi/KoreCallUtils.kt`).
 */
data object KoreDeclarationIndex : FileBasedIndexExtension<String, KoreDeclarationData>() {
	private val NAME: ID<String, KoreDeclarationData> = ID.create("io.github.ayfri.kore.koreassistant.declarations")

	override fun getName() = NAME

	override fun getIndexer() = DataIndexer<String, KoreDeclarationData, FileContent> { content ->
		val file = content.psiFile as? KtFile ?: return@DataIndexer emptyMap()
		val result = HashMap<String, KoreDeclarationData>()

		file.accept(object : KtTreeVisitorVoid() {
			override fun visitCallExpression(expression: KtCallExpression) {
				super.visitCallExpression(expression)
				val calleeName =
					(expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
				val kind = KoreDeclarationKind.byBuilderName(calleeName) ?: return
				val name = expression.firstStringLiteralArgument() ?: return
				result[name] = KoreDeclarationData(kind, name, expression.textOffset, expression.namespaceHint())
			}
		})

		result
	}

	override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

	override fun getValueExternalizer() = KoreDeclarationDataExternalizer

	// Bump on ANY change to the indexer logic, KoreDeclarationKind, or KoreDeclarationDataExternalizer.
	override fun getVersion() = 1

	override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(KotlinFileType.INSTANCE)

	override fun dependsOnFileContent() = true

	fun find(project: Project, name: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)) =
		FileBasedIndex.getInstance().getValues(NAME, name, scope)
}
