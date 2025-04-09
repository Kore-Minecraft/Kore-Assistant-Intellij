package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames
import io.github.ayfri.kore.koreassistant.actions.RefreshKoreElementsAction
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class KoreToolWindowContent(private val project: Project) : DumbAware {
	private val listModel = CollectionListModel<KoreElement>()
	private val elementList = JBList(listModel)
	val contentPanel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true) // Vertical toolbar

	companion object {
		private val LOGGER = Logger.getInstance(KoreToolWindowContent::class.java)
	}

	init {
		elementList.cellRenderer = KoreElementCellRenderer()
		elementList.selectionMode = ListSelectionModel.SINGLE_SELECTION
		elementList.emptyText.text = "Press Refresh or wait for indexing..."

		elementList.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount == 2) {
					elementList.selectedValue?.let { navigateToElement(it.element) }
				}
			}
		})

		val scrollPane = JBScrollPane(elementList)

		// Create Refresh Action and Toolbar
		val actionManager = ActionManager.getInstance()
		val actionGroup = DefaultActionGroup()
		// Register or get the refresh action (assuming it exists or you create it)
		// Let's assume a simple AnAction for now, you might need to adapt this
		val refreshAction = RefreshKoreElementsAction { refreshElements() } // Pass the refresh function

		actionGroup.add(refreshAction)

		val toolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actionGroup, true) // Horizontal toolbar
		toolbar.setTargetComponent(contentPanel) // Important for context

		contentPanel.setToolbar(toolbar.component)
		contentPanel.setContent(scrollPane)

		// Initial load attempt when smart
		DumbService.getInstance(project).runWhenSmart {
			refreshElements()
		}
	}

	private fun navigateToElement(element: PsiElement) {
		if (element is NavigatablePsiElement && element.canNavigate()) {
			ApplicationManager.getApplication().invokeLater {
				element.navigate(true)
				// Optionally focus editor
				val file = element.containingFile?.virtualFile
				if (file != null) {
					FileEditorManager.getInstance(project).openFile(file, true)
				}
			}
		} else {
			LOGGER.warn("Cannot navigate to element: $element (Not Navigatable or cannot navigate)")
		}
	}

	// Made public or internal to be called by the action
	internal fun refreshElements() {
		listModel.removeAll()
		elementList.setPaintBusy(true)
		elementList.emptyText.text = "Finding Kore elements..."

		// Run the potentially slow finding logic on a background thread
		object : Task.Backgroundable(project, "Finding Kore Elements", true /* cancellable */), DumbAware {
			override fun run(indicator: ProgressIndicator) {
				indicator.isIndeterminate = true

				if (DumbService.getInstance(project).isDumb) {
					LOGGER.info("Project is indexing. Deferring Kore element search.")
					updateUIOnEDT {
						elementList.emptyText.text = "Waiting for indexing to finish..."
						elementList.setPaintBusy(true) // Keep busy indicator
					}
					return
				}

				try {
					val foundElements = findKoreElements(indicator)
					updateUIOnEDT {
						listModel.replaceAll(foundElements.sortedBy { it.name })
						elementList.emptyText.text = if (foundElements.isEmpty()) "No Kore elements found in project." else ""
					}
				} catch (e: ProcessCanceledException) {
					LOGGER.info("Kore element search canceled.")
					updateUIOnEDT { elementList.emptyText.text = "Search canceled." }
				} catch (e: IndexNotReadyException) {
					LOGGER.warn("Index became unavailable during search.", e)
					updateUIOnEDT { elementList.emptyText.text = "Indexing changed. Please refresh." }
				} catch (e: Exception) {
					LOGGER.error("Error finding Kore elements", e)
					updateUIOnEDT { elementList.emptyText.text = "Error finding elements. See logs." }
				} finally {
					updateUIOnEDT { elementList.setPaintBusy(false) }
				}
			}
		}.queue()
	}

	private fun updateUIOnEDT(action: () -> Unit) {
		ApplicationManager.getApplication().invokeLater(action)
	}

	private fun findKoreElements(indicator: ProgressIndicator): List<KoreElement> {
		val elements = mutableListOf<KoreElement>()
		// Use a very broad scope to find declarations in dependencies
		val declarationSearchScope = GlobalSearchScope.allScope(project) // Alternative broad scope

		ReadAction.run<Throwable> {
			indicator.checkCanceled()

			val koreDeclarations = findKoreFunctionDeclarations(declarationSearchScope, indicator)
			if (koreDeclarations.isEmpty()) {
				LOGGER.warn("Could not find Kore function declarations via index search (using allScope). Ensure Kore library is a project dependency and indexed.")
				return@run
			}

			// Search for references only within the project files
			val usageSearchScope = GlobalSearchScope.projectScope(project)
			searchForReferences(koreDeclarations, usageSearchScope, indicator, elements)

		} // End ReadAction

		return elements
	}

	private fun findKoreFunctionDeclarations(
		scope: GlobalSearchScope,
		indicator: ProgressIndicator
	): List<KtNamedFunction> {
		val dataPackPackageFqn = KoreNames.KORE_DATAPACK_CLASS_ID.parent()
		val dataPackDeclarations = findDeclarationsByName(KoreNames.KORE_DATAPACK_NAME.asString(), dataPackPackageFqn, scope, indicator)

		val functionPackageFqn = KoreNames.KORE_FUNCTION_CLASS_ID.parent()
		val functionDeclarations = findDeclarationsByName(KoreNames.KORE_FUNCTION_NAME.asString(), functionPackageFqn, scope, indicator)

		return dataPackDeclarations + functionDeclarations
	}

	private fun findDeclarationsByName(
		name: String,
		packageName: org.jetbrains.kotlin.name.FqName,
		scope: GlobalSearchScope,
		indicator: ProgressIndicator
	): List<KtNamedFunction> {
		indicator.checkCanceled()
		return KotlinFunctionShortNameIndex.get(name, project, scope)
			.filter { declaration ->
				declaration.containingKtFile.packageFqName == packageName
			}
	}

	@OptIn(KaIdeApi::class)
	private fun searchForReferences(
		declarationsToSearch: List<KtNamedFunction>,
		searchScope: GlobalSearchScope, // For usages
		indicator: ProgressIndicator,
		results: MutableList<KoreElement>
	) {
		for (declaration in declarationsToSearch) {
			indicator.checkCanceled()
			val query = ReferencesSearch.search(declaration, searchScope)
			query.forEach { psiReference ->
				indicator.checkCanceled()
				processReference(psiReference, results)
				true // Continue processing
			}
		}
	}

	@OptIn(KaIdeApi::class)
	private fun processReference(psiReference: PsiReference, results: MutableList<KoreElement>) {
		val element = psiReference.element
		val callExpression = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java, false)?.takeIf {
			val callee = it.calleeExpression
			callee != null && PsiTreeUtil.isAncestor(callee, element, false)
		} ?: return

		try {
			analyze(callExpression) {
				val functionCall = callExpression.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze
				val functionSymbol = functionCall.symbol
				val callableId = functionSymbol.callableId?.asSingleFqName()
				val functionName = functionSymbol.name

				when {
					// Check for Kore Datapack
					callableId == KoreNames.KORE_DATAPACK_CLASS_ID && functionName == KoreNames.KORE_DATAPACK_NAME -> {
						val containingFile = callExpression.containingKtFile
						val fileName = containingFile.name.substringBeforeLast('.')
						// Avoid duplicates
						if (results.none { it is KoreDataPackElement && it.element == containingFile }) {
							results.add(KoreDataPackElement("datapack:$fileName", containingFile))
						}
					}
					// Check for Kore Function
					callableId == KoreNames.KORE_FUNCTION_CLASS_ID && functionName == KoreNames.KORE_FUNCTION_NAME -> {
						val nameArgument = callExpression.valueArguments.firstOrNull()?.getArgumentExpression()
						var name = "unknown_function"
						if (nameArgument is KtStringTemplateExpression) {
							name = nameArgument.literalValue ?: nameArgument.text.take(50).trim('"')
						} else if (nameArgument != null) {
							val constValue = nameArgument.evaluate()?.render()?.trim('"')
							name = constValue?.takeIf { it != "null" } ?: nameArgument.text.take(50)
						}
						val navigationElement = callExpression
						// Avoid duplicates
						if (results.none { it is KoreFunctionElement && it.name == name && it.element == navigationElement }) {
							results.add(KoreFunctionElement(name, navigationElement))
						}
					}
				}
			}
		} catch (e: Exception) {
			if (e is ProcessCanceledException) throw e
			LOGGER.warn("Error analyzing potential Kore call: ${callExpression.text}", e)
		}
	}
}

// Helper extension to get literal value safely from a simple string template
private val KtStringTemplateExpression.literalValue: String?
	get() {
		if (entries.isNotEmpty()) return null
		return text?.removeSurrounding("\"\"\"")?.removeSurrounding("\"")
	}

// Custom Cell Renderer
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
				is KoreDataPackElement -> KoreIcons.KORE
				is KoreFunctionElement -> KoreIcons.FUNCTION
			}
			component.toolTipText = value.element.containingFile?.virtualFile?.presentableUrl ?: "Unknown location"
		}
		return component
	}
}
