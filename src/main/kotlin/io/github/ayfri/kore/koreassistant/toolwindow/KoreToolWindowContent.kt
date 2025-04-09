package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import java.awt.Color
import java.util.*
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase

// Define sorting criteria
private enum class SortBy {
	NAME, FILE
}

private enum class SortOrder {
	ASCENDING, DESCENDING
}

class KoreToolWindowContent(private val project: Project) : DumbAware {
	private val listModel = CollectionListModel<KoreElement>()
	private val elementList = JBList(listModel)
	val contentPanel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true) // Vertical toolbar
	private var currentSortBy = SortBy.NAME
	private var currentSortOrder = SortOrder.ASCENDING
	private val foundElementsCache = mutableListOf<KoreElement>() // Cache found elements

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

		// Create Toolbar Actions
		val actionManager = ActionManager.getInstance()
		val mainActionGroup = DefaultActionGroup()

		// 1. Refresh Action (stays on main toolbar)
		val refreshAction = RefreshKoreElementsAction { refreshElements(true) }
		mainActionGroup.add(refreshAction)
		mainActionGroup.addSeparator() // Separator before the gear menu

		// 2. Sorting Actions (will go into the gear menu)
		val sortByNameAction = SortAction("Sort by Name", SortBy.NAME) {
			setSortCriteria(SortBy.NAME)
		}
		val sortByFileAction = SortAction("Sort by File", SortBy.FILE) {
			setSortCriteria(SortBy.FILE)
		}

		// 3. Create the group for the gear menu popup
		val gearActionGroup = DefaultActionGroup("View Options", true) // true makes it a popup
		val sortByGroup = DefaultActionGroup("Sort By", true) // Sub-popup for sort criteria
		sortByGroup.add(sortByNameAction)
		sortByGroup.add(sortByFileAction)
		gearActionGroup.add(sortByGroup)

		// 4. Create the Gear Action itself which shows the gearActionGroup as a popup
		// Using GearPlain icon and making it a popup group itself
		val gearMenuAction = object : DefaultActionGroup("View Options", true) {
			init {
				templatePresentation.icon = AllIcons.General.GearPlain // Set the gear icon
				val children = gearActionGroup.getChildren(null)
				addAll(*children) // Add sorting actions to this popup
			}
			// Ensure icon is always shown even if group is empty (though it won't be)
			override fun isDumbAware() = true
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = AllIcons.General.GearPlain
			}
		}
		gearMenuAction.isPopup = true // Explicitly set as popup

		// 5. Add the Gear menu action to the main toolbar
		mainActionGroup.add(gearMenuAction)

		val toolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, mainActionGroup, true) // Horizontal toolbar
		toolbar.targetComponent = contentPanel

		contentPanel.setToolbar(toolbar.component)
		contentPanel.setContent(scrollPane)

		// Initial load attempt when smart
		DumbService.getInstance(project).runWhenSmart {
			refreshElements(true)
		}
	}

	private fun setSortCriteria(sortBy: SortBy) {
		if (currentSortBy != sortBy) {
			currentSortBy = sortBy
			currentSortOrder = SortOrder.ASCENDING // Default to ascending when changing criteria
			refreshElements(false) // Re-sort and update UI only
		}
	}

	private fun toggleSortOrder() {
		currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
		refreshElements(false) // Re-sort and update UI only
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
	internal fun refreshElements(forceScan: Boolean) {
		if (forceScan) {
			listModel.removeAll() // Clear immediately for visual feedback if scanning
			foundElementsCache.clear()
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
						val found = findKoreElements(indicator)
						foundElementsCache.addAll(found) // Store results in cache
						updateUIOnEDT {
							sortAndDisplayElements() // Sort and update the list model
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
		} else {
			// Just re-sort and update the UI using the cached elements
			sortAndDisplayElements()
		}
	}

	private fun sortAndDisplayElements() {
		val sortedElements = sortElements(foundElementsCache)
		listModel.replaceAll(sortedElements)
		elementList.emptyText.text = if (sortedElements.isEmpty()) "No Kore elements found." else ""
		elementList.setPaintBusy(false) // Ensure busy indicator is off
	}

	private fun sortElements(elements: List<KoreElement>): List<KoreElement> {
		val comparator: Comparator<KoreElement> = when (currentSortBy) {
			SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
			SortBy.FILE -> compareBy<KoreElement> { it.fileName }
				.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name } // Secondary sort by name
		}
		return if (currentSortOrder == SortOrder.ASCENDING) {
			elements.sortedWith(comparator)
		} else {
			elements.sortedWith(comparator.reversed())
		}
	}

	private fun updateUIOnEDT(action: () -> Unit) {
		ApplicationManager.getApplication().invokeLater(action)
	}

	private fun findKoreElements(indicator: ProgressIndicator): List<KoreElement> {
		val elements = mutableListOf<KoreElement>()
		val declarationSearchScope = GlobalSearchScope.allScope(project)

		ReadAction.run<Throwable> {
			indicator.checkCanceled()

			val koreDeclarations = findKoreFunctionDeclarations(declarationSearchScope, indicator)
			if (koreDeclarations.isEmpty()) {
				LOGGER.warn("Could not find Kore function declarations via index search (using allScope). Ensure Kore library is a project dependency and indexed.")
				return@run
			}

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

				val containingFile = callExpression.containingKtFile
				val fileName = containingFile.name
				val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
				val lineNumber = document?.getLineNumber(callExpression.textOffset)?.plus(1) ?: -1 // 1-based line number

				fun extractNameArgument(defaultName: String): String {
					val nameArgument = callExpression.valueArguments.firstOrNull()?.getArgumentExpression()
					if (nameArgument is KtStringTemplateExpression) {
						return nameArgument.literalValue ?: nameArgument.text.take(50).trim('"')
					} else if (nameArgument != null) {
						val constValue = nameArgument.evaluate()?.render()?.trim('"')
						return constValue?.takeIf { it != "null" } ?: nameArgument.text.take(50)
					}
					return defaultName
				}

				when {
					callableId == KoreNames.KORE_DATAPACK_CLASS_ID && functionName == KoreNames.KORE_DATAPACK_NAME -> {
						val datapackName = extractNameArgument("datapack:${fileName.substringBeforeLast('.')}")
						val navigationElement = callExpression
						if (results.none { it is KoreDataPackElement && it.name == datapackName && it.element == navigationElement }) {
							results.add(KoreDataPackElement(datapackName, navigationElement, fileName, lineNumber))
						}
					}
					callableId == KoreNames.KORE_FUNCTION_CLASS_ID && functionName == KoreNames.KORE_FUNCTION_NAME -> {
						val functionElementName = extractNameArgument("unknown_function")
						val navigationElement = callExpression
						if (results.none { it is KoreFunctionElement && it.name == functionElementName && it.element == navigationElement }) {
							results.add(KoreFunctionElement(functionElementName, navigationElement, fileName, lineNumber))
						}
					}
				}
			}
		} catch (e: Exception) {
			if (e is ProcessCanceledException) throw e
			LOGGER.warn("Error analyzing potential Kore call: ${callExpression.text}", e)
		}
	}

	// Action to set sort criteria
	private inner class SortAction(
		text: String,
		private val sortBy: SortBy,
		private val onSelect: () -> Unit
	) : AnAction(text), DumbAware {
		override fun actionPerformed(e: AnActionEvent) {
			onSelect()
		}

		override fun update(e: AnActionEvent) {
			super.update(e)
			// Optionally visually indicate the current sort method (e.g., checkmark)
			e.presentation.icon = if (currentSortBy == sortBy) AllIcons.Actions.Checked else null
		}

		override fun getActionUpdateThread() = ActionUpdateThread.EDT
	}
}

// Helper extension to get literal value safely from a simple string template
private val KtStringTemplateExpression.literalValue: String?
	get() {
		if (entries.isNotEmpty()) return null
		return text?.removeSurrounding("\"\"\"")?.removeSurrounding("\"")
	}

// Custom Cell Renderer with improved UI
private class KoreElementCellRenderer : DefaultListCellRenderer() {
	override fun getListCellRendererComponent(
		list: JList<*>?,
		value: Any?,
		index: Int,
		isSelected: Boolean,
		cellHasFocus: Boolean
	): Component {
		val panel = JPanel(BorderLayout(JBUI.scale(5), 0))
		panel.accessibleContext.accessibleName = "Kore Element Cell"
		panel.border = JBUI.Borders.empty(5)
		panel.isOpaque = true
		panel.background = if (isSelected) list?.selectionBackground else list?.background
		val foreground = (if (isSelected) list?.selectionForeground else list?.foreground) ?: JBColor.WHITE

		if (value is KoreElement) {
			val nameLabel = JLabel(value.name, when (value) {
				is KoreDataPackElement -> KoreIcons.KORE
				is KoreFunctionElement -> KoreIcons.FUNCTION
			}, LEADING)
			nameLabel.foreground = foreground
			nameLabel.isOpaque = false
			panel.add(nameLabel, BorderLayout.CENTER)

			val locationText = "${value.fileName}:${value.lineNumber}"
			val locationLabel = JLabel(locationText)
			locationLabel.foreground = if (isSelected) foreground.darker().darker() else JBColor.GRAY
			locationLabel.horizontalAlignment = RIGHT
			locationLabel.isOpaque = false
			panel.add(locationLabel, BorderLayout.EAST)

			panel.toolTipText = value.element.containingFile?.virtualFile?.presentableUrl ?: "Unknown location"
		} else {
			panel.add(JLabel(value?.toString() ?: ""), BorderLayout.CENTER)
		}

		return panel
	}
}
