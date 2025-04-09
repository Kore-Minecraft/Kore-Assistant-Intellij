package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.GroupHeaderSeparator
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

// Define sorting criteria
private enum class SortBy {
	NAME, FILE
}

// Define sorting order
private enum class SortOrder {
	ASCENDING, DESCENDING
}

// Define grouping criteria
private enum class GroupBy {
	NONE, FILE
}

// Define list item types for grouping
private sealed class ListItem
private data class KoreElementItem(val element: KoreElement) : ListItem()
private data class GroupSeparatorItem(val name: String) : ListItem()


class KoreToolWindowContent(private val project: Project) : DumbAware {
	// Use ListItem to accommodate separators
	private val listModel = CollectionListModel<ListItem>()
	private val elementList = JBList(listModel)
	val contentPanel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true) // Vertical toolbar
	private var currentSortBy = SortBy.NAME
	private var currentSortOrder = SortOrder.ASCENDING
	private var currentGroupBy = GroupBy.NONE // Default grouping
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
					val selected = elementList.selectedValue
					if (selected is KoreElementItem) {
						navigateToElement(selected.element.element)
					}
				}
			}
		})

		val scrollPane = JBScrollPane(elementList)

		// Create Toolbar Actions
		val actionManager = ActionManager.getInstance()
		val mainActionGroup = DefaultActionGroup()

		// 1. Refresh Action
		val refreshAction = RefreshKoreElementsAction { refreshElements(true) }
		mainActionGroup.add(refreshAction)
		mainActionGroup.addSeparator()

		// 2. Sorting Actions
		val sortByNameAction = ToggleSortAction("Sort by Name", SortBy.NAME)
		val sortByFileAction = ToggleSortAction("Sort by File", SortBy.FILE)

		// 3. Grouping Actions
		val groupByNoneAction = GroupAction("No Grouping", GroupBy.NONE)
		val groupByFileAction = GroupAction("Group by File", GroupBy.FILE)

		// 4. Create the group for the gear menu popup
		val gearActionGroup = DefaultActionGroup("View Options", true) // true makes it a popup

		// Sort By Submenu
		val sortByGroup = DefaultActionGroup("Sort By", true)
		sortByGroup.add(sortByNameAction)
		sortByGroup.add(sortByFileAction)
		gearActionGroup.add(sortByGroup)
		gearActionGroup.addSeparator()

		// Group By Submenu
		val groupByGroup = DefaultActionGroup("Group By", true)
		groupByGroup.add(groupByNoneAction)
		groupByGroup.add(groupByFileAction)
		gearActionGroup.add(groupByGroup)

		// 5. Create the Gear Action
		val gearMenuAction = object : DefaultActionGroup("View Options", true) {
			init {
				templatePresentation.icon = AllIcons.General.GearPlain
				addAll(*gearActionGroup.getChildren(null))
			}
			override fun isDumbAware() = true
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = AllIcons.General.GearPlain
			}
		}
		gearMenuAction.isPopup = true

		// 6. Add the Gear menu action to the main toolbar
		mainActionGroup.add(gearMenuAction)

		val toolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, mainActionGroup, true)
		toolbar.targetComponent = contentPanel

		contentPanel.setToolbar(toolbar.component)
		contentPanel.setContent(scrollPane)

		DumbService.getInstance(project).runWhenSmart {
			refreshElements(true)
		}
	}

	private fun setSortCriteria(sortBy: SortBy) {
		val needsResort = if (currentSortBy != sortBy) {
			currentSortBy = sortBy
			currentSortOrder = SortOrder.ASCENDING // Reset order when changing criteria
			true
		} else {
			// If same criteria, toggle order
			currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
			true
		}
		if (needsResort) {
			groupAndSortAndDisplayElements() // Update UI
		}
	}

	private fun setGroupCriteria(groupBy: GroupBy) {
		if (currentGroupBy != groupBy) {
			currentGroupBy = groupBy
			groupAndSortAndDisplayElements() // Re-group, re-sort, and update UI
		}
	}

	private fun navigateToElement(element: PsiElement) {
		if (element is NavigatablePsiElement && element.canNavigate()) {
			ApplicationManager.getApplication().invokeLater {
				element.navigate(true)
				val file = element.containingFile?.virtualFile
				if (file != null) {
					FileEditorManager.getInstance(project).openFile(file, true)
				}
			}
		} else {
			LOGGER.warn("Cannot navigate to element: $element (Not Navigatable or cannot navigate)")
		}
	}

	internal fun refreshElements(forceScan: Boolean) {
		if (forceScan) {
			listModel.removeAll()
			foundElementsCache.clear()
			elementList.setPaintBusy(true)
			elementList.emptyText.text = "Finding Kore elements..."

			object : Task.Backgroundable(project, "Finding Kore Elements", true), DumbAware {
				override fun run(indicator: ProgressIndicator) {
					indicator.isIndeterminate = true

					if (DumbService.getInstance(project).isDumb) {
						LOGGER.info("Project is indexing. Deferring Kore element search.")
						updateUIOnEDT {
							elementList.emptyText.text = "Waiting for indexing to finish..."
							elementList.setPaintBusy(true)
						}
						return
					}

					try {
						val found = findKoreElements(indicator)
						foundElementsCache.clear() // Clear before adding new results
						foundElementsCache.addAll(found)
						updateUIOnEDT {
							groupAndSortAndDisplayElements() // Group, sort, and update list
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
			// Just re-group, re-sort and update the UI using the cached elements
			groupAndSortAndDisplayElements()
		}
	}

	private fun groupAndSortAndDisplayElements() {
		val sortedElements = sortElements(foundElementsCache)
		val listItems = mutableListOf<ListItem>()

		if (currentGroupBy == GroupBy.FILE) {
			var currentFileName = ""
			sortedElements.forEach { element ->
				if (element.fileName != currentFileName) {
					currentFileName = element.fileName
					listItems.add(GroupSeparatorItem(currentFileName))
				}
				listItems.add(KoreElementItem(element))
			}
		} else { // GroupBy.NONE
			sortedElements.forEach { listItems.add(KoreElementItem(it)) }
		}

		listModel.replaceAll(listItems)
		elementList.emptyText.text = if (listItems.isEmpty()) "No Kore elements found." else ""
		elementList.setPaintBusy(false)
	}

	private fun sortElements(elements: List<KoreElement>): List<KoreElement> {
		val comparator: Comparator<KoreElement> = when {
			// If grouping by file, file name is always the primary sort key
			currentGroupBy == GroupBy.FILE -> compareBy<KoreElement> { it.fileName }
				.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name } // Secondary sort by name

			// Otherwise, sort by the selected criteria
			currentSortBy == SortBy.NAME -> compareBy<KoreElement> { it.name }
				.thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName } // Secondary sort by file

			currentSortBy == SortBy.FILE -> compareBy<KoreElement> { it.fileName }
				.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name } // Secondary sort by name

			else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name } // Default fallback
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

	// Action to toggle sort criteria and order
	private inner class ToggleSortAction(
		text: String,
		private val sortBy: SortBy
	) : AnAction(text), DumbAware {
		override fun actionPerformed(e: AnActionEvent) {
			setSortCriteria(sortBy)
		}

		override fun update(e: AnActionEvent) {
			super.update(e)
			val presentation = e.presentation
			presentation.icon = if (currentSortBy == sortBy) {
				if (currentSortOrder == SortOrder.ASCENDING) AllIcons.RunConfigurations.Scroll_up
				else AllIcons.RunConfigurations.Scroll_down
			} else {
				null // No icon if not the active sort criteria
			}
			// Indicate active even without icon change for clarity
			Toggleable.setSelected(e.presentation, currentSortBy == sortBy)
		}

		override fun getActionUpdateThread() = ActionUpdateThread.EDT
	}

	// Action to set group criteria
	private inner class GroupAction(
		text: String,
		private val groupBy: GroupBy
	) : AnAction(text), DumbAware, Toggleable {
		override fun actionPerformed(e: AnActionEvent) {
			setGroupCriteria(groupBy)
		}

		override fun update(e: AnActionEvent) {
			super.update(e)
			Toggleable.setSelected(e.presentation, currentGroupBy == groupBy)
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

// Custom Cell Renderer to handle Kore Elements and Group Separators
private class KoreElementCellRenderer : ListCellRenderer<ListItem> {
	private val elementRenderer = KoreElementPanelRenderer()
	private val separatorRenderer = GroupSeparatorRenderer()

	override fun getListCellRendererComponent(
		list: JList<out ListItem>?,
		value: ListItem?,
		index: Int,
		isSelected: Boolean,
		cellHasFocus: Boolean
	): Component {
		return when (value) {
			is KoreElementItem -> elementRenderer.getListCellRendererComponent(list, value.element, index, isSelected, cellHasFocus)
			is GroupSeparatorItem -> separatorRenderer.getListCellRendererComponent(list as JList<out GroupSeparatorItem>, value, index, false, false) // Separators not selectable
			null -> // Should not happen with CollectionListModel, but handle defensively
				JLabel("").apply {
					isOpaque = true
					background = list?.background ?: JBColor.PanelBackground
					foreground = list?.foreground ?: JBColor.foreground()
				}
		}
	}
}

// Panel for rendering KoreElement
private class KoreElementPanelRenderer : DefaultListCellRenderer() {
	override fun getListCellRendererComponent(
		list: JList<*>?,
		value: Any?, // Receives KoreElement
		index: Int,
		isSelected: Boolean,
		cellHasFocus: Boolean
	): Component {
		val panel = JPanel(BorderLayout(JBUI.scale(5), 0))
		panel.accessibleContext.accessibleName = "Kore Element Cell"
		panel.border = JBUI.Borders.empty(2, 5) // Reduced vertical padding
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
			// Slightly dimmed foreground for location
			locationLabel.foreground = if (isSelected) foreground.darker() else JBColor.GRAY
			locationLabel.horizontalAlignment = RIGHT
			locationLabel.isOpaque = false
			panel.add(locationLabel, BorderLayout.EAST)

			panel.toolTipText = value.element.containingFile?.virtualFile?.presentableUrl ?: "Unknown location"
		} else {
			// Fallback for unexpected types
			panel.add(JLabel(value?.toString() ?: ""), BorderLayout.CENTER)
		}

		return panel
	}
}

// Renderer for GroupSeparatorItem
private class GroupSeparatorRenderer : ListCellRenderer<GroupSeparatorItem> {
	private val separator = GroupHeaderSeparator(JBUI.emptyInsets())

	init {
		separator.border = JBUI.Borders.empty(3, 5) // Adjust padding
	}
	override fun getListCellRendererComponent(
		list: JList<out GroupSeparatorItem>?,
		value: GroupSeparatorItem?,
		index: Int,
		isSelected: Boolean, // Ignored
		cellHasFocus: Boolean // Ignored
	): Component {
		separator.caption = value?.name ?: ""
		separator.background = list?.background ?: JBColor.PanelBackground // Match list background
		separator.foreground = list?.foreground ?: JBColor.foreground() // Match list foreground
		separator.setCaptionCentered(false)
		return separator
	}
}
