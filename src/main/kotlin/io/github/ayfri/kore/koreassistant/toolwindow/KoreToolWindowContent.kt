package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.ayfri.kore.koreassistant.Icons
import io.github.ayfri.kore.koreassistant.KoreNames
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class KoreToolWindowContent(private val project: Project) {
	private val listModel = CollectionListModel<KoreElement>()
	private val elementList = JBList(listModel)
	val contentPanel: JPanel = JPanel(BorderLayout())

	companion object {
		private val LOGGER = Logger.getInstance(KoreToolWindowContent::class.java)
	}

	init {
		elementList.cellRenderer = KoreElementCellRenderer()
		elementList.selectionMode = ListSelectionModel.SINGLE_SELECTION
		elementList.emptyText.text = "No Kore elements found or project indexing..."

		elementList.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount == 2) {
					val index = elementList.locationToIndex(e.point)
					if (index >= 0) {
						val element = listModel.getElementAt(index)
						navigateToElement(element.element)
					}
				}
			}
		})

		val scrollPane = JBScrollPane(elementList)
		contentPanel.add(scrollPane, BorderLayout.CENTER)

		// TODO: Add a refresh button maybe?
		refreshElements()
	}

	private fun navigateToElement(element: PsiElement) {
		if (element is NavigatablePsiElement && element.canNavigate()) {
			ApplicationManager.getApplication().invokeLater {
				FileEditorManager.getInstance(project).openFile(element.containingFile.virtualFile, true)
				element.navigate(true)
			}
		} else {
			LOGGER.warn("Cannot navigate to element: $element")
		}
	}

	private fun refreshElements() {
		listModel.removeAll()
		elementList.setPaintBusy(true)

		ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding Kore Elements", false) {
			override fun run(indicator: ProgressIndicator) {
				val foundElements = findKoreElements()
				ApplicationManager.getApplication().invokeLater {
					listModel.replaceAll(foundElements.sortedBy { it.name })
					elementList.setPaintBusy(false)
					if (foundElements.isEmpty()) {
						elementList.emptyText.text = "No Kore elements found in project."
					}
				}
			}
		})
	}


	@OptIn(KaIdeApi::class)
	private fun findKoreElements(): List<KoreElement> {
		val elements = mutableListOf<KoreElement>()
		val projectScope = GlobalSearchScope.projectScope(project)

		ReadAction.run<Throwable> {
			// Find declarations of the target functions first
			val dataPackDeclarations = KotlinFunctionShortNameIndex.get(KoreNames.KORE_DATAPACK_NAME.asString(), project, projectScope)
				.filter { it.fqName == KoreNames.KORE_DATAPACK_CLASS_ID }
			val functionDeclarations = KotlinFunctionShortNameIndex.get(KoreNames.KORE_FUNCTION_NAME.asString(), project, projectScope)
				.filter { it.fqName == KoreNames.KORE_DATAPACK_CLASS_ID}

			if (dataPackDeclarations.isEmpty() && functionDeclarations.isEmpty()) {
				LOGGER.warn("Could not find Kore function declarations in project scope.")
				return@run // Exit ReadAction
			}

			// Search for references (usages) of these functions
			val searchScope = GlobalSearchScope.projectScope(project)

			for (declaration in dataPackDeclarations) {
				ReferencesSearch.search(declaration, searchScope).forEach { psiReference ->
					val callExpression = psiReference.element.parent as? KtCallExpression ?: return@forEach
					try {
						analyze(callExpression) {
							val functionCall = callExpression.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze
							val functionSymbol = functionCall.symbol
							if (functionSymbol.callableId?.asSingleFqName() == KoreNames.KORE_DATAPACK_CLASS_ID && functionSymbol.name == KoreNames.KORE_DATAPACK_NAME) {
								// Extract name (assuming first arg is lambda, need context) - Placeholder name for now
								val name = callExpression.containingKtFile.name // Improve name extraction if possible
								elements.add(KoreDataPackElement("datapack:${name.substringBefore('.')}", callExpression))
							}
						}
					} catch (e: Exception) {
						LOGGER.warn("Error analyzing datapack call: ${callExpression.text}", e)
					}
				}
			}

			for (declaration in functionDeclarations) {
				ReferencesSearch.search(declaration, searchScope).forEach { psiReference ->
					val callExpression = psiReference.element.parent as? KtCallExpression ?: return@forEach
					try {
						analyze(callExpression) {
							val functionCall = callExpression.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze
							val functionSymbol = functionCall.symbol
							if (functionSymbol.callableId?.asSingleFqName() == KoreNames.KORE_FUNCTION_CLASS_ID && functionSymbol.name == KoreNames.KORE_FUNCTION_NAME) {
								// Extract name from the first argument if it's a string literal
								val nameArgument = callExpression.valueArguments.firstOrNull()?.getArgumentExpression()
								var name = "unknown_function"
								if (nameArgument is KtStringTemplateExpression && nameArgument.entries.isEmpty()) {
									name = nameArgument.text ?: name // Get simple string value
								} else if (nameArgument != null) {
									// Fallback or more complex extraction needed
									name = nameArgument.text.take(30) // Basic fallback
								}
								val navigationElement = nameArgument ?: callExpression // Navigate to name or whole call
								elements.add(KoreFunctionElement(name, navigationElement))
							}
						}
					} catch (e: Exception) {
						LOGGER.warn("Error analyzing function call: ${callExpression.text}", e)
					}
				}
			}
		} // End ReadAction

		return elements
	}
}


// Custom Cell Renderer for better display
private class KoreElementCellRenderer : DefaultListCellRenderer() {
	override fun getListCellRendererComponent(
		list: JList<*>?,
		value: Any?,
		index: Int,
		isSelected: Boolean,
		cellHasFocus: Boolean
	): Component {
		val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
		if (value is KoreElement && component is JLabel) {
			component.text = value.name
			component.icon = when (value) {
				is KoreDataPackElement -> Icons.KORE // Assuming Icons.ICON is your datapack icon
				is KoreFunctionElement -> Icons.FUNCTION // Assuming Icons.FUNCTION is your function icon
			}
		}
		return component
	}
}
