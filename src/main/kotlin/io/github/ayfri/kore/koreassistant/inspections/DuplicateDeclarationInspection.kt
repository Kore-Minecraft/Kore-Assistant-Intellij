package io.github.ayfri.kore.koreassistant.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import io.github.ayfri.kore.koreassistant.psi.declarationNameArgument
import io.github.ayfri.kore.koreassistant.psi.koreCallAt
import io.github.ayfri.kore.koreassistant.psi.koreDeclarationKind
import io.github.ayfri.kore.koreassistant.services.KoreLibraryService
import io.github.ayfri.kore.koreassistant.toolwindow.UNKNOWN_DATA_PACK
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType

/**
 * Two declarations writing the same file (`function("foo")` twice in one datapack, the same `predicate` in two
 * `DataPack.xxx()` extensions): Kore writes both and the last one generated silently wins.
 *
 * Reuses the tool window's element collection, cached per PSI modification so every file's check shares one pass,
 * and compares elements on their datapack + output path. Dynamic names are templates, and declarations whose
 * datapack could not be attributed may live in different packs, so neither is ever reported.
 */
class DuplicateDeclarationInspection : LocalInspectionTool() {
	override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
		if (file !is KtFile || !file.project.service<KoreLibraryService>().isKoreProject) return emptyArray()
		if (!file.anyDescendantOfType<KtCallExpression> { it.koreDeclarationKind() != null }) return emptyArray()

		val fileUrl = file.virtualFile?.url ?: return emptyArray()
		val duplicates = file.project.koreElements()
			.filter { !it.isDynamic && it.dataPackName != UNKNOWN_DATA_PACK }
			.groupBy { it.dataPackName to it.outputPath }
			.values
			.filter { it.size > 1 }

		return duplicates.flatMap { group ->
			group.filter { it.fileUrl == fileUrl }.mapNotNull { element ->
				val call = file.koreCallAt(element.offset, element.kind.builderName) ?: return@mapNotNull null
				val others = group.filter { it !== element }.joinToString { it.sourceLocation }
				manager.createProblemDescriptor(
					call.declarationNameArgument() ?: call,
					"'${element.resourceLocation ?: element.outputPath}' is also declared at $others, the last one generated overwrites the others",
					isOnTheFly,
					emptyArray(),
					ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
				)
			}
		}.toTypedArray()
	}
}
