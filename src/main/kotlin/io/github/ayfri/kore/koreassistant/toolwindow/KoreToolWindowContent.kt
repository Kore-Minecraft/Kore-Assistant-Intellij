package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.PlatformIcons
import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.calls.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.calls.symbol
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class KoreToolWindowContent(private val project: Project) : DumbAware {
	private val listModel = CollectionListModel<KoreElement>()
	private val elementList = JBList(listModel)
	val contentPanel: JPanel = JPanel(BorderLayout())

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

		val topPanel = JPanel(FlowLayout(FlowLayout.LEFT))
		val refreshButton = JButton("Refresh", PlatformIcons.SYNCHRONIZE_ICON)
		refreshButton.addActionListener { refreshElements() }
		topPanel.add(refreshButton)

		contentPanel.add(topPanel, BorderLayout.NORTH)
		contentPanel.add(scrollPane, BorderLayout.CENTER)

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

	private fun refreshElements() {
		listModel.removeAll()
		elementList.setPaintBusy(true)
		elementList.emptyText.text = "Finding Kore elements..."

		// Run the potentially slow finding logic on a background thread
		object : Task.Backgroundable(project, "Finding Kore Elements", true /* cancellable */), DumbAware {
			override fun run(indicator: ProgressIndicator) {
				indicator.isIndeterminate = true

				// Check for dumb mode at the start of the background task
				if (DumbService.getInstance(project).isDumb) {
					LOGGER.info("Project is indexing. Deferring Kore element search.")
					updateUIOnEDT {
						elementList.emptyText.text = "Waiting for indexing to finish..."
						elementList.setPaintBusy(true) // Keep busy indicator
					}
					// No results to process, the user can refresh manually later
					return // Exit the task run method
				}

				// Proceed with finding elements now that we are in smart mode and on a background thread
				try {
					val foundElements = findKoreElements(indicator)
					updateUIOnEDT {
						listModel.replaceAll(foundElements.sortedBy { it.name })
						if (foundElements.isEmpty()) {
							elementList.emptyText.text = "No Kore elements found in project."
						} else {
							elementList.emptyText.text = "" // Clear empty text
						}
					}
				} catch (e: ProcessCanceledException) {
					LOGGER.info("Kore element search canceled.")
					updateUIOnEDT { elementList.emptyText.text = "Search canceled." }
				} catch (e: IndexNotReadyException) {
					// Should be rare now due to the DumbService check, but handle defensively
					LOGGER.warn("Index became unavailable during search.", e)
					updateUIOnEDT { elementList.emptyText.text = "Indexing changed. Please refresh." }
				} catch (e: Exception) {
					LOGGER.error("Error finding Kore elements", e)
					updateUIOnEDT { elementList.emptyText.text = "Error finding elements. See logs." }
				} finally {
					updateUIOnEDT { elementList.setPaintBusy(false) }
				}
			}
		}.queue() // Queue the task for execution
	}

	// Helper to update UI components safely on the EDT
	private fun updateUIOnEDT(action: () -> Unit) {
		ApplicationManager.getApplication().invokeLater(action)
	}

	// This function now runs on a background thread but needs ReadAction for PSI access
	private fun findKoreElements(indicator: ProgressIndicator): List<KoreElement> {
		val elements = mutableListOf<KoreElement>()
		val projectScope = GlobalSearchScope.projectScope(project)

		ReadAction.run<Throwable> { // ReadAction is required for index/PSI access
			indicator.checkCanceled() // Check before starting potentially long operations

			// 1. Find declarations of the target Kore functions
			val koreDeclarations = findKoreFunctionDeclarations(projectScope, indicator)
			if (koreDeclarations.isEmpty()) {
				LOGGER.warn("Could not find Kore function declarations. Ensure Kore library is a project dependency.")
				return@run // Exit ReadAction if no declarations found
			}

			// 2. Search for references (usages) of these declarations
			searchForReferences(koreDeclarations, projectScope, indicator, elements)

		} // End ReadAction

		LOGGER.info("Finished Kore element search. Found ${elements.size} elements.")
		return elements
	}

	// Helper: Finds the KtNamedFunction declarations for Kore functions
	private fun findKoreFunctionDeclarations(
		scope: GlobalSearchScope,
		indicator: ProgressIndicator
	): List<KtNamedFunction> {
		val dataPackDeclarations = findDeclarationsByName(KoreNames.KORE_DATAPACK_NAME.asString(), KoreNames.KORE_DATAPACK_CLASS_ID.parent(), scope, indicator)
		val functionDeclarations = findDeclarationsByName(KoreNames.KORE_FUNCTION_NAME.asString(), KoreNames.KORE_FUNCTION_CLASS_ID.parent(), scope, indicator)

		LOGGER.info("Found ${dataPackDeclarations.size} dataPack declarations and ${functionDeclarations.size} function declarations.")
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
			.filter { it.containingKtFile.packageFqName == packageName }
	}


	// Helper: Searches for references and analyzes them
	@OptIn(KaIdeApi::class)
	private fun searchForReferences(
		declarationsToSearch: List<KtNamedFunction>,
		searchScope: GlobalSearchScope,
		indicator: ProgressIndicator,
		results: MutableList<KoreElement>
	) {
		for (declaration in declarationsToSearch) {
			indicator.checkCanceled()
			val query = ReferencesSearch.search(declaration, searchScope)
			// Process references, checking for cancellation frequently
			query.forEach { psiReference ->
				indicator.checkCanceled()
				processReference(psiReference, results)
				true // Continue processing
			}
		}
	}

	// Helper: Processes a single PSI reference to see if it's a Kore element call
	@OptIn(KaIdeApi::class)
	private fun processReference(psiReference: PsiReference, results: MutableList<KoreElement>) {
		val element = psiReference.element
		val callExpression = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java, false)?.takeIf {
			val callee = it.calleeExpression
			callee != null && PsiTreeUtil.isAncestor(callee, element, false)
		} ?: return // Not a direct call or element not in callee

		try {
			analyze(callExpression) {
				val functionCall = callExpression.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze
				val functionSymbol = functionCall.symbol
				val callableId = functionSymbol.callableId?.asSingleFqName() ?: return@analyze
				val functionName = functionSymbol.name

				when {
					// Check for Kore Datapack
					callableId == KoreNames.KORE_DATAPACK_CLASS_ID && functionName == KoreNames.KORE_DATAPACK_NAME -> {
						val containingFile = callExpression.containingKtFile
						val fileName = containingFile.name.substringBeforeLast('.')
						results.add(KoreDataPackElement("datapack:$fileName", containingFile)) // Navigate to file
						LOGGER.debug("Found Kore Datapack in: $fileName")
					}
					// Check for Kore Function
					callableId == KoreNames.KORE_FUNCTION_CLASS_ID && functionName == KoreNames.KORE_FUNCTION_NAME -> {
						val nameArgument = callExpression.valueArguments.firstOrNull()?.getArgumentExpression()
						var name = "unknown_function"
						if (nameArgument is KtStringTemplateExpression) {
							name = nameArgument.literalValue ?: nameArgument.text.take(50).trim('"') // Limit length
						} else if (nameArgument != null) {
							// Try evaluating constant; fallback to text representation
							val constValue = nameArgument.evaluate()?.render()
							name = constValue ?: nameArgument.text.take(50) // Limit length
						}
						// Navigate to the call expression itself, or the name argument if found
						val navigationElement = nameArgument ?: callExpression
						results.add(KoreFunctionElement(name, navigationElement))
						LOGGER.debug("Found Kore Function: $name")
					}
				}
			}
		} catch (e: Exception) {
			// Log analysis errors but continue searching other references
			if (e is ProcessCanceledException) throw e // Re-throw cancellation
			LOGGER.warn("Error analyzing potential Kore call: ${callExpression.text}", e)
		}
	}

} // End KoreToolWindowContent class


// Helper extension to get literal value safely from a simple string template (handles "" and """), returns null for templates with variables
private val KtStringTemplateExpression.literalValue: String?
	get() = if (entries.isEmpty()) this.text?.removeSurrounding("\"\"\"")?.removeSurrounding("\"") else null


// Custom Cell Renderer (assuming KoreElement definition is in another file now)
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
				is KoreDataPackElement -> KoreIcons.KORE // Use KoreIcons
				is KoreFunctionElement -> KoreIcons.FUNCTION // Use KoreIcons
			}
			component.toolTipText = value.element.containingFile?.virtualFile?.presentableUrl ?: "Unknown location"
		}
		return component
	}
}
