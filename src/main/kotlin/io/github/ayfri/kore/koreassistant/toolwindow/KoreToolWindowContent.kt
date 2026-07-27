package io.github.ayfri.kore.koreassistant.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.PsiManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import io.github.ayfri.kore.koreassistant.actions.RefreshKoreElementsAction
import org.jetbrains.kotlin.psi.KtFile
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeModel
import javax.swing.tree.TreeSelectionModel

// Deep enough to show every namespace's folders without exploding a datapack with thousands of elements.
private const val DEFAULT_EXPANSION_DEPTH = 3
private const val REFRESH_MERGE_DELAY_MS = 300

class KoreToolWindowContent(private val project: Project) : Disposable {
	private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
	private val tree = KoreTree(treeModel)
	private val filterField = SearchTextField(false)
	val contentPanel = SimpleToolWindowPanel(true, true)

	private var groupBy = KoreGroupBy.OUTPUT
	private var sortBy = KoreSortBy.NAME
	private var sortOrder = KoreSortOrder.ASCENDING
	private var elements = emptyList<KoreElement>()
	private var waitingForSmartMode = false

	// Kotlin edits arrive per keystroke; the queue collapses a typing burst into a single index re-query.
	private val refreshQueue =
		MergingUpdateQueue("KoreElements", REFRESH_MERGE_DELAY_MS, true, MergingUpdateQueue.ANY_COMPONENT, this)

	companion object {
		private val LOGGER = Logger.getInstance(KoreToolWindowContent::class.java)
	}

	init {
		tree.isRootVisible = false
		tree.showsRootHandles = true
		tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
		tree.cellRenderer = KoreTreeCellRenderer()
		tree.emptyText.text = "Waiting for indexing to finish..."
		TreeSpeedSearch.installOn(tree, true) { path ->
			(path.lastPathComponent as? DefaultMutableTreeNode)?.koreNode?.label
		}

		object : DoubleClickListener() {
			override fun onDoubleClick(event: MouseEvent) = navigateToSelection()
		}.installOn(tree)

		// Selects the row under the cursor before showing the menu, so right-click acts on what was clicked.
		PopupHandler.installFollowingSelectionTreePopup(
			tree,
			createKoreNodePopupGroup { navigateToSelection() },
			ActionPlaces.TOOLWINDOW_POPUP,
		)

		object : AnAction(), DumbAware {
			override fun actionPerformed(e: AnActionEvent) {
				navigateToSelection()
			}

			override fun getActionUpdateThread() = ActionUpdateThread.EDT
		}.registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this)

		filterField.textEditor.emptyText.text = "Filter by name, namespace or path"
		filterField.addDocumentListener(object : DocumentAdapter() {
			override fun textChanged(e: DocumentEvent) = rebuildTree()
		})

		contentPanel.toolbar = createToolbar()
		contentPanel.setContent(JPanel(BorderLayout()).apply {
			add(filterField, BorderLayout.NORTH)
			add(JBScrollPane(tree), BorderLayout.CENTER)
		})

		PsiManager.getInstance(project).addPsiTreeChangeListener(KotlinChangeListener(), this)
		refreshElements()
	}

	override fun dispose() = Unit

	private fun createToolbar(): JComponent {
		val sortGroup = DefaultActionGroup("Sort By", true).apply {
			KoreSortBy.entries.forEach { add(SortAction(it)) }
		}
		val groupGroup = DefaultActionGroup("Group By", true).apply {
			KoreGroupBy.entries.forEach { add(GroupAction(it)) }
		}

		val viewOptions = object : DefaultActionGroup("View Options", true), DumbAware {
			init {
				templatePresentation.icon = AllIcons.General.GearPlain
				add(sortGroup)
				addSeparator()
				add(groupGroup)
			}
		}.apply { isPopup = true }

		val actions = DefaultActionGroup().apply {
			add(RefreshKoreElementsAction(::refreshElements))
			add(ExpandAllAction())
			add(CollapseAllAction())
			addSeparator()
			add(viewOptions)
		}

		return ActionManager.getInstance()
			.createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
			.also { it.targetComponent = contentPanel }
			.component
	}

	internal fun refreshElements() {
		if (DumbService.getInstance(project).isDumb) {
			retryWhenSmart()
			return
		}

		tree.setPaintBusy(true)
		tree.emptyText.text = "Finding Kore elements..."

		object : Task.Backgroundable(project, "Finding Kore Elements", true), DumbAware {
			override fun run(indicator: ProgressIndicator) {
				indicator.isIndeterminate = true

				try {
					// Indexing can restart between the check above and here, so the index may still refuse to answer.
					if (DumbService.getInstance(project).isDumb) return onEdt(::retryWhenSmart)

					val found = KoreElementFinder.findAll(project, indicator)
					onEdt {
						elements = found
						rebuildTree()
					}
				} catch (e: IndexNotReadyException) {
					LOGGER.warn("Index became unavailable during search, retrying once indexing settles.", e)
					onEdt(::retryWhenSmart)
				} catch (e: Exception) {
					LOGGER.error("Error finding Kore elements", e)
					onEdt { tree.emptyText.text = "Error finding elements. See logs." }
				} finally {
					onEdt { tree.setPaintBusy(false) }
				}
			}
		}.queue()
	}

	/**
	 * `runWhenSmart` fires once, and indexing arrives in waves while a project opens, so a single callback
	 * usually lands back in dumb mode. Re-arming until it actually sticks is what keeps the tree from staying
	 * empty until the user happens to edit a file. The flag keeps only one callback pending at a time.
	 */
	private fun retryWhenSmart() {
		tree.emptyText.text = "Waiting for indexing to finish..."
		if (waitingForSmartMode) return

		waitingForSmartMode = true
		DumbService.getInstance(project).runWhenSmart {
			waitingForSmartMode = false
			refreshElements()
		}
	}

	private fun rebuildTree() {
		val filter = filterField.text.trim()
		val visible = elements.filter { it.matches(filter) }

		treeModel.setRoot(buildKoreTree(visible, groupBy, sortBy, sortOrder))
		TreeUtil.expand(tree, DEFAULT_EXPANSION_DEPTH)

		tree.emptyText.text = when {
			elements.isEmpty() -> "No Kore elements found."
			visible.isEmpty() -> "No element matches '$filter'."
			else -> ""
		}
	}

	private fun selectedNode() = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.koreNode

	// Resolves the url lazily so a stale snapshot cannot leak a dead PSI reference.
	private fun navigateToSelection(): Boolean {
		val element = selectedNode()?.element ?: return false

		val file = VirtualFileManager.getInstance().findFileByUrl(element.fileUrl)
		if (file == null || !file.isValid) {
			LOGGER.warn("Cannot navigate, file is gone: ${element.fileUrl}")
			refreshElements()
			return false
		}

		OpenFileDescriptor(project, file, element.offset).navigate(true)
		return true
	}

	private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action)

	/** Only Kotlin files feed the index, so anything else is dropped before it reaches the queue. */
	private inner class KotlinChangeListener : PsiTreeChangeAdapter() {
		override fun childrenChanged(event: PsiTreeChangeEvent) = scheduleRefresh(event)
		override fun childAdded(event: PsiTreeChangeEvent) = scheduleRefresh(event)
		override fun childRemoved(event: PsiTreeChangeEvent) = scheduleRefresh(event)
		override fun childReplaced(event: PsiTreeChangeEvent) = scheduleRefresh(event)

		private fun scheduleRefresh(event: PsiTreeChangeEvent) {
			if (event.file !is KtFile) return
			refreshQueue.queue(Update.create(this@KoreToolWindowContent) { refreshElements() })
		}
	}

	private inner class SortAction(private val target: KoreSortBy) :
		AnAction(target.displayName), DumbAware, Toggleable {
		override fun actionPerformed(e: AnActionEvent) {
			// Re-picking the active criteria flips the direction, the usual IDE list behaviour.
			sortOrder = when {
				sortBy != target -> KoreSortOrder.ASCENDING
				sortOrder == KoreSortOrder.ASCENDING -> KoreSortOrder.DESCENDING
				else -> KoreSortOrder.ASCENDING
			}
			sortBy = target
			rebuildTree()
		}

		override fun update(e: AnActionEvent) {
			Toggleable.setSelected(e.presentation, sortBy == target)
			e.presentation.icon = when {
				sortBy != target -> null
				sortOrder == KoreSortOrder.ASCENDING -> AllIcons.RunConfigurations.Scroll_up
				else -> AllIcons.RunConfigurations.Scroll_down
			}
		}

		override fun getActionUpdateThread() = ActionUpdateThread.EDT
	}

	private inner class GroupAction(private val target: KoreGroupBy) :
		AnAction(target.displayName), DumbAware, Toggleable {
		override fun actionPerformed(e: AnActionEvent) {
			if (groupBy == target) return
			groupBy = target
			rebuildTree()
		}

		override fun update(e: AnActionEvent) = Toggleable.setSelected(e.presentation, groupBy == target)

		override fun getActionUpdateThread() = ActionUpdateThread.EDT
	}

	/**
	 * Publishes the selected row into the action system. The lazily resolved `PSI_ELEMENT` is what lets the
	 * platform's own Find Usages work on a tree of PSI-free DTOs.
	 */
	private inner class KoreTree(model: TreeModel) : Tree(model), UiDataProvider {
		override fun uiDataSnapshot(sink: DataSink) {
			val node = selectedNode()
			sink[CommonDataKeys.PROJECT] = project
			if (node == null) return

			sink[KORE_NODE_KEY] = node
			val element = node.element ?: return
			sink.lazy(CommonDataKeys.PSI_ELEMENT) { runReadActionBlocking { element.findDeclaration(project) } }
		}
	}

	private inner class ExpandAllAction :
		AnAction("Expand All", null, AllIcons.Actions.Expandall), DumbAware {
		override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
		override fun getActionUpdateThread() = ActionUpdateThread.EDT
	}

	private inner class CollapseAllAction :
		AnAction("Collapse All", null, AllIcons.Actions.Collapseall), DumbAware {
		override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 0)
		override fun getActionUpdateThread() = ActionUpdateThread.EDT
	}
}

private val DefaultMutableTreeNode.koreNode: KoreTreeNode? get() = userObject as? KoreTreeNode

private class KoreTreeCellRenderer : ColoredTreeCellRenderer() {
	override fun customizeCellRenderer(
		tree: JTree,
		value: Any?,
		selected: Boolean,
		expanded: Boolean,
		leaf: Boolean,
		row: Int,
		hasFocus: Boolean,
	) {
		val node = (value as? DefaultMutableTreeNode)?.koreNode ?: return
		val dynamic = node.element?.isDynamic == true

		icon = node.icon
		// A runtime-built name is a template standing for every value the loop produces, hence the italics.
		append(node.label, if (dynamic) SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
		node.secondaryText?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
		toolTipText = node.tooltip()
	}
}
