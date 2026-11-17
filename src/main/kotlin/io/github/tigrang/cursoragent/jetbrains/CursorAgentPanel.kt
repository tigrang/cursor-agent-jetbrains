package io.github.tigrang.cursoragent.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.PlatformUtils
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicHTML

/**
 * Coordinates JetBrains-native tool-window tabs and the Cursor session browser.
 * The interactive agent UI is always the real Cursor CLI, never an ACP imitation.
 */
class CursorAgentPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : Disposable {
    private val store = CursorSessionStore()
    private val contentManager = toolWindow.contentManager
    private val model = DefaultListModel<CursorSession>()
    private val sessionList = object : JBList<CursorSession>(model) {
        override fun getToolTipText(event: MouseEvent): String? =
            if (openButtonAt(event) != null) "Open chat" else super.getToolTipText(event)
    }
    private val search = SearchTextField()
    private val countLabel = JBLabel(" ")
    private val refreshSerial = AtomicInteger()
    private val activeSessions = mutableMapOf<String, Content>()
    private val knownSessions = mutableListOf<CursorSession>()
    private var hoveredOpenRow: Int? = null
    private val historyView = createHistoryView()
    private var historyOnlyContent: Content? = null
    private var disposed = false
    private var tabCount = 0

    init {
        toolWindow.setToHideOnEmptyContent(false)
        val tabListener = object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.remove) {
                    hideHistory(event.content)
                }
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                if (!disposed && !project.isDisposed && event.content !== historyOnlyContent && contentManager.isEmpty) {
                    showStandaloneHistory()
                    val history = historyOnlyContent
                    // Let the IDE finish closing the tab before restoring a hidden tool window.
                    SwingUtilities.invokeLater {
                        if (!disposed && !project.isDisposed && contentManager.selectedContent === history &&
                            !toolWindow.isVisible
                        ) {
                            toolWindow.show()
                        }
                    }
                }
            }
        }
        contentManager.addContentManagerListener(tabListener)
        Disposer.register(this, Disposable { contentManager.removeContentManagerListener(tabListener) })
        toolWindow.setTitleActions(listOf(
            object : DumbAwareAction("New Agent", "Start a Cursor CLI conversation", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = openTerminal()
            },
            object : DumbAwareAction("History", "Browse and resume Cursor conversations", AllIcons.Vcs.History) {
                override fun actionPerformed(e: AnActionEvent) = toggleHistory()
            },
        ))
        showStandaloneHistory(requestFocus = false)
    }

    private fun toggleHistory() {
        val selected = contentManager.selectedContent
        if (selected != null && hideHistory(selected)) {
            contentManager.requestFocus(selected, true)
            return
        }
        if (selected == null) {
            showStandaloneHistory()
            return
        }
        reloadHistory()
        if (selected !== historyOnlyContent) {
            val cards = selected.component as JPanel
            cards.add(historyView, "history")
            (cards.layout as CardLayout).show(cards, "history")
        }
        contentManager.requestFocus(selected, true)
    }

    private fun showStandaloneHistory(requestFocus: Boolean = true) {
        reloadHistory()
        val content = ContentFactory.getInstance().createContent(historyView, "", false).apply {
            isCloseable = false
            setPreferredFocusableComponent(search.textEditor)
        }
        historyOnlyContent = content
        contentManager.addContent(content)
        contentManager.setSelectedContent(content, requestFocus)
    }

    private fun hideHistory(content: Content): Boolean {
        val cards = historyView.parent as? JPanel ?: return false
        if (cards !== content.component) return false
        cards.remove(historyView)
        cards.revalidate()
        cards.repaint()
        return true
    }

    private fun createHistoryView(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty()

        val top = JPanel(BorderLayout(0, JBUI.scale(9)))
        top.border = JBUI.Borders.empty(12, 12, 10, 12)
        val heading = JBLabel("Conversations").apply { font = font.deriveFont(Font.BOLD) }
        countLabel.foreground = UIUtil.getContextHelpForeground()
        val headingRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(heading, BorderLayout.WEST)
            add(countLabel, BorderLayout.EAST)
        }
        top.add(headingRow, BorderLayout.NORTH)

        search.textEditor.putClientProperty("JTextField.placeholderText", "Search conversations")
        search.textEditor.toolTipText = "Search by title, working directory or session ID"
        search.textEditor.accessibleContext.accessibleName = "Search Cursor conversations"
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        val searchInput = search.textEditor.getInputMap(JComponent.WHEN_FOCUSED)
        for ((key, direction) in listOf(KeyEvent.VK_UP to -1, KeyEvent.VK_DOWN to 1)) {
            val action = "selectCursorSession$key"
            searchInput.put(KeyStroke.getKeyStroke(key, 0), action)
            search.textEditor.actionMap.put(action, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    if (model.isEmpty) return
                    val index = (sessionList.selectedIndex + direction).coerceIn(0, model.size - 1)
                    sessionList.selectedIndex = index
                    sessionList.ensureIndexIsVisible(index)
                }
            })
        }
        top.add(search, BorderLayout.SOUTH)
        root.add(top, BorderLayout.NORTH)

        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.accessibleContext.accessibleName = "Cursor conversation history"
        sessionList.fixedCellHeight = -1
        sessionList.cellRenderer = SessionRenderer()
        sessionList.emptyText.text = "No matching conversations"
        sessionList.toolTipText = ""
        val rowMouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val index = openButtonAt(e)
                if (index != null && e.clickCount == 1) {
                    sessionList.selectedIndex = index
                    resume(model[index])
                } else if (index == null && e.clickCount == 2) {
                    sessionAt(e)?.let(::resume)
                }
            }

            override fun mouseMoved(e: MouseEvent) = updateHoveredOpenRow(openButtonAt(e))

            override fun mouseExited(e: MouseEvent) = updateHoveredOpenRow(null)
        }
        sessionList.addMouseListener(rowMouse)
        sessionList.addMouseMotionListener(rowMouse)

        val resumeSelection = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                sessionList.selectedValue?.let(::resume)
            }
        }
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        for (component in listOf(search.textEditor, sessionList)) {
            component.getInputMap(JComponent.WHEN_FOCUSED).put(enter, "resumeCursorSession")
            component.actionMap.put("resumeCursorSession", resumeSelection)
        }
        root.add(JBScrollPane(sessionList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        return root
    }

    private fun reloadHistory() {
        val serial = refreshSerial.incrementAndGet()
        countLabel.text = "Loading…"
        val projectRoot = project.basePath
        CompletableFuture.supplyAsync {
            val workspace = CursorSessionStore.workspaceDirectory(projectRoot) ?: return@supplyAsync emptyList()
            store.list().filter { CursorSessionStore.workspaceDirectory(it.cwd) == workspace }
        }.whenComplete { result, error ->
            SwingUtilities.invokeLater {
                if (disposed || serial != refreshSerial.get()) return@invokeLater
                if (error != null) {
                    countLabel.text = "Unavailable"
                    knownSessions.clear()
                    model.clear()
                    sessionList.emptyText.text = "Could not read local Cursor sessions."
                } else {
                    knownSessions.clear()
                    knownSessions.addAll(result)
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        if (disposed) return
        val selection = sessionList.selectedValue?.id
        val filter = search.text.trim()
        val matches = knownSessions.filter {
            filter.isBlank() || it.title.contains(filter, true) || it.cwd.contains(filter, true) || it.id.contains(filter, true)
        }
        model.clear()
        matches.forEach(model::addElement)
        countLabel.text = "${matches.size} ${if (matches.size == 1) "session" else "sessions"}"
        if (matches.isNotEmpty()) {
            sessionList.emptyText.text = "No matching conversations"
        }
        val selectedIndex = matches.indexOfFirst { it.id == selection }
        when {
            selectedIndex >= 0 -> sessionList.selectedIndex = selectedIndex
            matches.isNotEmpty() -> sessionList.selectedIndex = 0
            else -> {
                sessionList.emptyText.text = when {
                    filter.isNotBlank() -> "No conversations match your search."
                    else -> "No conversations for this project yet."
                }
            }
        }
    }

    private fun sessionAt(event: MouseEvent): CursorSession? {
        val index = sessionList.locationToIndex(event.point)
        if (sessionList.getCellBounds(index, index)?.contains(event.point) != true) return null
        return model[index]
    }

    private fun openButtonAt(event: MouseEvent): Int? {
        val index = sessionList.locationToIndex(event.point)
        val row = sessionList.getCellBounds(index, index) ?: return null
        val buttonSize = JBUI.scale(rowActionSize)
        val top = row.y + (row.height - buttonSize) / 2
        val end = row.x + row.width - JBUI.scale(rowHorizontalPadding)
        return index.takeIf {
            row.contains(event.point) && event.y in top until (top + buttonSize) &&
                event.x in (end - buttonSize) until end
        }
    }

    private fun updateHoveredOpenRow(index: Int?) {
        if (hoveredOpenRow == index) return
        hoveredOpenRow = index
        sessionList.cursor = Cursor.getPredefinedCursor(if (index == null) Cursor.DEFAULT_CURSOR else Cursor.HAND_CURSOR)
        sessionList.repaint()
    }

    private fun resume(session: CursorSession) {
        val workspace = CursorSessionStore.workspaceDirectory(project.basePath)
        if (workspace == null || CursorSessionStore.workspaceDirectory(session.cwd) != workspace) {
            Messages.showErrorDialog(project, "The chat’s working directory no longer matches the current project.", "Cannot Resume")
            reloadHistory()
            return
        }
        activeSessions[session.id]?.let {
            if (contentManager.getIndexOfContent(it) >= 0) {
                hideHistory(it)
                contentManager.setSelectedContent(it, true)
                return
            }
            activeSessions.remove(session.id)
        }
        openTerminal(session.id, workspace.toString(), session.title)
    }

    private fun openTerminal(
        sessionId: String? = null,
        cwd: String = project.basePath ?: System.getProperty("user.home"),
        title: String = "New Agent",
    ) {
        val configuredCliPath = CursorAgentSettings.cliPath
        val tabTitle = when {
            sessionId == null -> "Agent ${++tabCount}"
            BasicHTML.isHTMLString(title) -> "Chat – $title".take(28)
            else -> title.take(28)
        }
        val terminalDisposable = Disposer.newDisposable("Cursor Agent: $tabTitle")
        Disposer.register(this, terminalDisposable)
        val host = JPanel(BorderLayout())
        showState(host, "Starting Cursor Agent…", cwd, isBusy = true)

        val cards = JPanel(CardLayout()).apply { add(host, "terminal") }
        var terminalFocus: JComponent = host
        val content = ContentFactory.getInstance().createContent(cards, tabTitle, false).apply {
            isCloseable = true
            setDisposer(terminalDisposable)
            setPreferredFocusedComponent { if (historyView.parent === cards) search.textEditor else terminalFocus }
        }
        if (sessionId != null) {
            activeSessions[sessionId] = content
            Disposer.register(terminalDisposable, Disposable {
                if (activeSessions[sessionId] === content) activeSessions.remove(sessionId)
            })
        }
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
        historyOnlyContent?.let { contentManager.removeContent(it, true) }
        historyOnlyContent = null

        CompletableFuture.supplyAsync { CursorCli.executable(configuredCliPath) }.whenComplete { executable, error ->
            SwingUtilities.invokeLater {
                if (disposed || Disposer.isDisposed(terminalDisposable) || contentManager.getIndexOfContent(content) < 0) {
                    return@invokeLater
                }
                if (error != null || executable == null) {
                    showState(host, "Cursor CLI path required", "Set a valid absolute executable path under Settings → Tools → Cursor Agent.") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, CursorAgentSettingsConfigurable::class.java)
                    }
                    return@invokeLater
                }
                runCatching {
                    val runner = LocalTerminalDirectRunner.createTerminalRunner(project)
                    val hostIde = when {
                        PlatformUtils.isPhpStorm() -> "PhpStorm"
                        PlatformUtils.isIntelliJ() -> "IntelliJ IDEA"
                        else -> null
                    }
                    val options = ShellStartupOptions.Builder()
                        .workingDirectory(cwd)
                        .envVariables(hostIde?.let { mapOf("CURSOR_AGENT_HOST_IDE" to it) } ?: emptyMap())
                        .build()
                    val widget: TerminalWidget = runner.startShellTerminalWidget(terminalDisposable, options, false)
                    val command = CursorCli.command(executable, sessionId, SystemInfo.isWindows)
                    host.removeAll()
                    host.add(widget.component, BorderLayout.CENTER)
                    host.revalidate()
                    host.repaint()
                    terminalFocus = widget.preferredFocusableComponent ?: widget.component
                    keepEscapeInTerminal(widget, terminalDisposable)
                    widget.sendCommandToExecute(command)
                    if (content.isSelected && historyView.parent !== cards) widget.requestFocus()
                }.onFailure {
                    showState(host, "Terminal could not start", it.message ?: "Unknown error")
                }
            }
        }
    }

    private fun keepEscapeInTerminal(widget: TerminalWidget, terminalDisposable: Disposable) {
        val terminal = JBTerminalWidget.asJediTermWidget(widget) ?: return
        IdeEventQueue.getInstance().addDispatcher(IdeEventQueue.EventDispatcher { event ->
            if (event !is KeyEvent || event.modifiersEx != 0 || !widget.hasFocus()) {
                return@EventDispatcher false
            }
            val escape = if (event.id == KeyEvent.KEY_TYPED) {
                event.keyChar == '\u001b'
            } else {
                event.keyCode == KeyEvent.VK_ESCAPE
            }
            if (!escape) return@EventDispatcher false

            // Send Esc before the IDE can use it to move focus back to the editor.
            if (event.id == KeyEvent.KEY_PRESSED) {
                terminal.terminalStarter?.sendString("\u001b", true)
            }
            true
        }, terminalDisposable)
    }

    private fun showState(
        host: JPanel,
        heading: String,
        detail: String,
        isBusy: Boolean = false,
        action: (() -> Unit)? = null,
    ) {
        val content = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            gridx = 0
            anchor = GridBagConstraints.CENTER
            insets = Insets(0, JBUI.scale(14), JBUI.scale(7), JBUI.scale(14))
        }
        content.add(JBLabel(heading).apply { font = font.deriveFont(Font.BOLD) }, constraints)
        constraints.gridy = 1
        content.add(JBLabel(if (detail.length > 52) "…${detail.takeLast(51)}" else detail).apply {
            foreground = UIUtil.getContextHelpForeground()
            horizontalAlignment = JLabel.CENTER
            putClientProperty("html.disable", true)
            toolTipText = "Details: $detail"
        }, constraints)
        if (isBusy) {
            constraints.gridy = 2
            content.add(JProgressBar().apply {
                isIndeterminate = true
                preferredSize = JBUI.size(120, 3)
                isBorderPainted = false
            }, constraints)
        } else if (action != null) {
            constraints.gridy = 2
            content.add(JButton("Open Settings").apply { addActionListener { action() } }, constraints)
        }
        host.removeAll()
        host.add(content, BorderLayout.CENTER)
        host.revalidate()
        host.repaint()
    }

    override fun dispose() {
        disposed = true
        refreshSerial.incrementAndGet()
    }

    private class RowActionLabel : JBLabel() {
        var hovered = false

        init {
            horizontalAlignment = SwingConstants.CENTER
            preferredSize = JBUI.size(rowActionSize, rowActionSize)
        }

        override fun paintComponent(g: Graphics) {
            if (hovered) {
                val background = g.create() as Graphics2D
                try {
                    background.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    background.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    val arc = JBUI.scale(8)
                    background.fillRoundRect(0, 0, width, height, arc, arc)
                } finally {
                    background.dispose()
                }
            }
            super.paintComponent(g)
        }
    }

    private inner class SessionRenderer : javax.swing.ListCellRenderer<CursorSession> {
        private val title = JBLabel().apply { putClientProperty("html.disable", true) }
        private val subtitle = JBLabel().apply { putClientProperty("html.disable", true) }
        private val date = JBLabel()
        private val openButton = RowActionLabel()
        private val cell = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(rowVerticalPadding, rowHorizontalPadding)
            add(JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                isOpaque = false
                add(title, BorderLayout.NORTH)
                add(subtitle, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(date, BorderLayout.WEST)
                add(JPanel(GridBagLayout()).apply {
                    isOpaque = false
                    add(openButton)
                }, BorderLayout.CENTER)
            }, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out CursorSession>,
            value: CursorSession?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val selectedFg = list.selectionForeground
            val normalFg = list.foreground
            val mainFg = if (isSelected) selectedFg else normalFg
            val mutedFg = if (isSelected) selectedFg else UIUtil.getContextHelpForeground()
            cell.background = if (isSelected) list.selectionBackground else list.background
            title.text = value?.title?.take(55) ?: ""
            title.foreground = mainFg
            title.font = list.font.deriveFont(Font.BOLD)
            subtitle.text = value?.cwd?.substringAfterLast('/')?.ifBlank { value.cwd }.orEmpty()
            subtitle.foreground = mutedFg
            subtitle.font = list.font
            date.text = value?.let { shortDateFormat.format(it.updatedAt) } ?: ""
            date.foreground = mutedFg
            date.font = list.font.deriveFont(list.font.size2D - 1f)
            openButton.icon = AllIcons.Actions.Edit.takeIf { value != null }
            openButton.hovered = value != null && hoveredOpenRow == index
            cell.toolTipText = value?.let { "Working directory: ${it.cwd} · ${it.id}" }
            return cell
        }
    }

    companion object {
        private const val rowVerticalPadding = 9
        private const val rowHorizontalPadding = 12
        private const val rowActionSize = 30
        private val shortDateFormat = DateTimeFormatter.ofPattern("MMM d")
            .withZone(ZoneId.systemDefault())
    }
}
