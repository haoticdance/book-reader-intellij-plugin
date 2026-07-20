package com.github.haoticdance.bookreaderintellijplugin.editor

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import com.github.haoticdance.bookreaderintellijplugin.models.INTERNAL_LINK_SCHEME
import com.github.haoticdance.bookreaderintellijplugin.models.TocNode
import com.github.haoticdance.bookreaderintellijplugin.parsers.EpubParser
import com.github.haoticdance.bookreaderintellijplugin.parsers.FB2Parser
import com.github.haoticdance.bookreaderintellijplugin.parsers.MobiParser
import com.github.haoticdance.bookreaderintellijplugin.services.BookReaderService
import com.github.haoticdance.bookreaderintellijplugin.toolWindow.MyToolWindowFactory
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.*
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.math.pow
import kotlin.math.roundToInt

class BookFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())
    private val statusLabel = JLabel("", SwingConstants.CENTER)

    private var bookModel: BookModel? = null
    private var currentPage = 0
    private val service = project.service<BookReaderService>()

    private val cefBrowser = JBCefBrowser()
    private var pdfScrollTimer: Timer? = null
    private var jsQuery: JBCefJSQuery? = null

    // --- TOC sidebar ---
    private val tocRootNode = DefaultMutableTreeNode("Contents")
    private val tocTreeModel = DefaultTreeModel(tocRootNode)
    private val tocTree = Tree(tocTreeModel)
    private val tocPanel = JPanel(BorderLayout())
    private var tocVisible = true
    private lateinit var splitPane: JSplitPane
    private lateinit var tocToggleButton: JButton

    // --- Zoom ---
    private var zoomLevel: Double = 0.0
    private lateinit var zoomLabel: JLabel

    /** Suppress tree selection events while we programmatically update selection. */
    private var suppressTreeSelection = false

    init {
        zoomLevel = service.getZoomLevel()
        setupUI()
        setupCefHandlers()
        loadBook()
        applyZoom()

        project.messageBus.connect()
            .subscribe(MyToolWindowFactory.SETTINGS_TOPIC, object : MyToolWindowFactory.BookReaderSettingsListener {
                override fun onSettingsChanged() {
                    if (file.extension?.lowercase() != "pdf") {
                        showPage(currentPage)
                    }
                }
            })
    }

    // ──────────────────────────────────────────────
    //  UI setup
    // ──────────────────────────────────────────────

    private fun setupUI() {
        val isPdf = file.extension?.lowercase() == "pdf"

        if (!isPdf) {
            setupTocPanel()
            splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tocPanel, cefBrowser.component).apply {
                dividerSize = 3
                isContinuousLayout = true
                border = null
            }
            panel.add(splitPane, BorderLayout.CENTER)
        } else {
            panel.add(cefBrowser.component, BorderLayout.CENTER)
        }

        // --- Bottom navigation bar ---
        val navPanel = JPanel(BorderLayout())
        navPanel.border = JBUI.Borders.empty(2, 0)

        val themeButton = JButton(if (service.isDarkMode()) "☀ Light" else "🌙 Night").apply {
            addActionListener {
                val newMode = !service.isDarkMode()
                service.setDarkMode(newMode)
                text = if (newMode) "☀ Light" else "🌙 Night"
            }
        }

        if (!isPdf) {
            tocToggleButton = JButton("☰ TOC").apply {
                toolTipText = "Toggle Table of Contents"
                addActionListener { toggleToc() }
            }

            val prevButton = JButton("◀ Prev").apply {
                addActionListener { showPage(currentPage - 1) }
            }
            val nextButton = JButton("Next ▶").apply {
                addActionListener { showPage(currentPage + 1) }
            }

            // Zoom controls
            zoomLabel = JLabel(zoomPercentText())
            zoomLabel.horizontalAlignment = SwingConstants.CENTER
            zoomLabel.preferredSize = Dimension(50, zoomLabel.preferredSize.height)

            val zoomOutBtn = JButton("−").apply {
                toolTipText = "Zoom Out"
                marionSize()
                addActionListener { changeZoom(-0.5) }
            }
            val zoomInBtn = JButton("+").apply {
                toolTipText = "Zoom In"
                marionSize()
                addActionListener { changeZoom(0.5) }
            }
            val zoomResetBtn = JButton("⟲").apply {
                toolTipText = "Reset Zoom to 100%"
                marionSize()
                addActionListener { changeZoom(-zoomLevel) } // reset to 0
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0))
            leftPanel.add(tocToggleButton)
            leftPanel.add(prevButton)

            val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER, 2, 0))
            centerPanel.add(zoomOutBtn)
            centerPanel.add(zoomLabel)
            centerPanel.add(zoomInBtn)
            centerPanel.add(zoomResetBtn)
            centerPanel.add(Box.createHorizontalStrut(12))
            centerPanel.add(statusLabel)

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0))
            rightPanel.add(nextButton)
            rightPanel.add(themeButton)

            navPanel.add(leftPanel, BorderLayout.WEST)
            navPanel.add(centerPanel, BorderLayout.CENTER)
            navPanel.add(rightPanel, BorderLayout.EAST)
        } else {
            // PDF: zoom + file label
            zoomLabel = JLabel(zoomPercentText())
            zoomLabel.horizontalAlignment = SwingConstants.CENTER
            zoomLabel.preferredSize = Dimension(50, zoomLabel.preferredSize.height)

            val zoomOutBtn = JButton("−").apply {
                toolTipText = "Zoom Out"; marionSize()
                addActionListener { changeZoom(-0.5) }
            }
            val zoomInBtn = JButton("+").apply {
                toolTipText = "Zoom In"; marionSize()
                addActionListener { changeZoom(0.5) }
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0))
            leftPanel.add(JLabel(" PDF: ${file.name}"))

            val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER, 2, 0))
            centerPanel.add(zoomOutBtn)
            centerPanel.add(zoomLabel)
            centerPanel.add(zoomInBtn)

            navPanel.add(leftPanel, BorderLayout.WEST)
            navPanel.add(centerPanel, BorderLayout.CENTER)
        }

        panel.add(navPanel, BorderLayout.SOUTH)
    }

    /** Small fixed-width button for zoom controls. */
    private fun JButton.marionSize() {
        preferredSize = Dimension(36, preferredSize.height)
        minimumSize = preferredSize
    }

    // ──────────────────────────────────────────────
    //  TOC panel (JTree with nesting)
    // ──────────────────────────────────────────────

    private fun setupTocPanel() {
        tocPanel.border = JBUI.Borders.empty()
        tocPanel.preferredSize = Dimension(260, 0)
        tocPanel.minimumSize = Dimension(160, 0)

        val headerLabel = JLabel("  Table of Contents")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        headerLabel.border = JBUI.Borders.empty(8, 8, 6, 8)
        tocPanel.add(headerLabel, BorderLayout.NORTH)

        tocTree.isRootVisible = false
        tocTree.showsRootHandles = true
        tocTree.cellRenderer = TocTreeCellRenderer()
        tocTree.addTreeSelectionListener { e ->
            if (suppressTreeSelection) return@addTreeSelectionListener
            val selected = tocTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val tocNode = selected.userObject as? TocNode ?: return@addTreeSelectionListener
            if (tocNode.chapterIndex >= 0) {
                showPage(tocNode.chapterIndex)
            }
        }

        val scrollPane = JBScrollPane(tocTree)
        scrollPane.border = JBUI.Borders.empty()
        tocPanel.add(scrollPane, BorderLayout.CENTER)
    }

    private fun toggleToc() {
        tocVisible = !tocVisible
        tocPanel.isVisible = tocVisible
        splitPane.dividerSize = if (tocVisible) 3 else 0
        if (tocVisible) {
            splitPane.dividerLocation = 260
        }
    }

    /**
     * Populate the JTree with the TOC hierarchy (or flat chapter list as fallback).
     */
    private fun populateToc() {
        tocRootNode.removeAllChildren()
        val model = bookModel ?: return

        val tocNodes = model.toc
        if (tocNodes != null && tocNodes.isNotEmpty()) {
            // Hierarchical TOC from EPUB NCX
            for (node in tocNodes) {
                tocRootNode.add(buildTreeNode(node))
            }
        } else {
            // Flat fallback from chapter list
            for ((i, chapter) in model.chapters.withIndex()) {
                val title = chapter.title.ifBlank { "Chapter ${i + 1}" }
                tocRootNode.add(DefaultMutableTreeNode(TocNode(title, i)))
            }
        }

        tocTreeModel.reload()
        // Expand all top-level nodes
        for (i in 0 until tocRootNode.childCount) {
            val child = tocRootNode.getChildAt(i) as DefaultMutableTreeNode
            tocTree.expandPath(TreePath(child.path))
        }
    }

    private fun buildTreeNode(tocNode: TocNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(tocNode)
        for (child in tocNode.children) {
            treeNode.add(buildTreeNode(child))
        }
        return treeNode
    }

    /**
     * Highlight the tree node matching [page] without firing a navigation event.
     */
    private fun highlightTocEntry(page: Int) {
        val node = findTreeNodeByChapterIndex(tocRootNode, page) ?: return
        val path = TreePath(node.path)
        suppressTreeSelection = true
        tocTree.selectionPath = path
        tocTree.scrollPathToVisible(path)
        suppressTreeSelection = false
    }

    private fun findTreeNodeByChapterIndex(parent: DefaultMutableTreeNode, index: Int): DefaultMutableTreeNode? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val tocNode = child.userObject as? TocNode
            if (tocNode?.chapterIndex == index) return child
            val found = findTreeNodeByChapterIndex(child, index)
            if (found != null) return found
        }
        return null
    }

    // ──────────────────────────────────────────────
    //  TOC tree cell renderer
    // ──────────────────────────────────────────────

    private inner class TocTreeCellRenderer : DefaultTreeCellRenderer() {
        init {
            // Remove default icons for a cleaner look
            leafIcon = null
            openIcon = null
            closedIcon = null
        }

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val node = (value as? DefaultMutableTreeNode)?.userObject as? TocNode
            if (node != null) {
                val displayTitle = if (node.title.length > 55) node.title.take(52) + "…" else node.title
                text = displayTitle
                // Bold the currently displayed chapter
                if (!selected && node.chapterIndex == currentPage) {
                    font = font.deriveFont(Font.BOLD)
                }
            }
            border = JBUI.Borders.empty(2, 4, 2, 4)
            return component
        }
    }

    // ──────────────────────────────────────────────
    //  Zoom
    // ──────────────────────────────────────────────

    private fun zoomPercentText(): String {
        val percent = (1.2.pow(zoomLevel) * 100).roundToInt()
        return "${percent}%"
    }

    private fun changeZoom(delta: Double) {
        zoomLevel = (zoomLevel + delta).coerceIn(-3.0, 5.0)
        service.setZoomLevel(zoomLevel)
        applyZoom()
        if (::zoomLabel.isInitialized) {
            zoomLabel.text = zoomPercentText()
        }
    }

    private fun applyZoom() {
        cefBrowser.cefBrowser.setZoomLevel(zoomLevel)
    }

    // ──────────────────────────────────────────────
    //  CEF handlers
    // ──────────────────────────────────────────────

    private fun setupCefHandlers() {
        cefBrowser.jbCefClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
            override fun onKeyEvent(browser: CefBrowser?, event: CefKeyboardHandler.CefKeyEvent?): Boolean {
                if (event?.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) {
                    if (file.extension?.lowercase() != "pdf") {
                        when (event.windows_key_code) {
                            KeyEvent.VK_LEFT -> {
                                SwingUtilities.invokeLater { showPage(currentPage - 1) }
                                return true
                            }
                            KeyEvent.VK_RIGHT -> {
                                SwingUtilities.invokeLater { showPage(currentPage + 1) }
                                return true
                            }
                        }
                    }
                }
                return false
            }
        }, cefBrowser.cefBrowser)

        // Intercept in-text link clicks: internal book links (resolved to INTERNAL_LINK_SCHEME
        // by the parsers) become a page jump instead of a failed navigation, and external
        // http(s) links open in the system browser instead of hijacking the embedded reader.
        cefBrowser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, user_gesture: Boolean, is_redirect: Boolean
            ): Boolean {
                val url = request?.url ?: return false
                if (!user_gesture) return false // let programmatic loadHTML navigation through untouched

                if (url.startsWith(INTERNAL_LINK_SCHEME)) {
                    val target = url.removePrefix(INTERNAL_LINK_SCHEME)
                    val page = target.substringBefore("#").toIntOrNull()
                    val anchor = target.substringAfter("#", "").ifEmpty { null }
                    if (page != null) {
                        SwingUtilities.invokeLater {
                            showPage(page)
                            if (anchor != null) scrollToAnchor(anchor)
                        }
                    }
                    return true
                }

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    BrowserUtil.browse(url)
                    return true
                }

                return false
            }
        }, cefBrowser.cefBrowser)

        if (file.extension?.lowercase() == "pdf") {
            jsQuery = JBCefJSQuery.create(cefBrowser as JBCefBrowserBase).apply {
                addHandler { dataStr ->
                    try {
                        val parts = dataStr.split(",")
                        val scrollY = parts[0].toDouble().toInt()
                        val totalHeight = parts[1].toDouble().toInt()
                        if (totalHeight > 0) {
                            service.updateBookProgress(
                                file.path, file.name, "PDF Document",
                                scrollY, totalHeight, moveToTop = false
                            )
                        }
                    } catch (_: Exception) {}
                    null
                }
            }

            cefBrowser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, url: String?) {
                    val lastBook = service.getRecentBooks().find { it.path == file.path }
                    if (lastBook != null && lastBook.lastPage > 0) {
                        cefBrowser.cefBrowser.executeJavaScript("""
                            setTimeout(function() { window.scrollTo(0, ${lastBook.lastPage}); }, 1000);
                        """.trimIndent(), "", 0)
                    }
                    // Re-apply zoom after page loads
                    applyZoom()
                }
            }, cefBrowser.cefBrowser)

            pdfScrollTimer = Timer(5000) {
                jsQuery?.let { query ->
                    cefBrowser.cefBrowser.executeJavaScript(
                        query.inject("(window.scrollY || 0).toString() + ',' + (document.documentElement.scrollHeight || 0).toString()"),
                        "", 0
                    )
                }
            }
            pdfScrollTimer?.start()
        }
    }

    // ──────────────────────────────────────────────
    //  Book loading & page display
    // ──────────────────────────────────────────────

    private fun loadBook() {
        val ioFile = File(file.path)
        if (!ioFile.exists()) {
            renderHtml("<h1>File not found</h1><p>${ioFile.absolutePath}</p>")
            return
        }

        val recent = service.getRecentBooks().find { it.path == file.path }

        if (file.extension?.lowercase() == "pdf") {
            val url = ioFile.toURI().toString()
            cefBrowser.loadURL(url)
            service.updateBookProgress(
                file.path, file.name, "PDF Document",
                recent?.lastPage ?: 0, recent?.totalPages ?: 0, moveToTop = true
            )
            return
        }

        try {
            bookModel = when (file.extension?.lowercase()) {
                "fb2" -> FB2Parser().parse(ioFile.inputStream())
                "epub" -> EpubParser().parse(ioFile)
                "mobi" -> MobiParser().parse(ioFile)
                else -> null
            }

            val model = bookModel
            if (model != null) {
                populateToc()
                if (recent != null) currentPage = recent.lastPage
                showPage(currentPage, saveProgress = true, moveToTop = true)
            } else {
                renderHtml("<h1>Unsupported format</h1><p>Extension: ${file.extension}</p>")
            }
        } catch (e: Exception) {
            val stackTrace = e.stackTraceToString()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            renderHtml("""
                <h1>Error loading book</h1>
                <p><b>${e::class.simpleName}:</b> ${e.message?.replace("<", "&lt;")}</p>
                <pre style="font-size:11px;white-space:pre-wrap">$stackTrace</pre>
            """.trimIndent())
        }
    }

    private fun showPage(page: Int, saveProgress: Boolean = true, moveToTop: Boolean = true) {
        val model = bookModel ?: return
        if (page < 0 || page >= model.chapters.size) return

        currentPage = page
        val chapter = model.chapters[page]

        val isDark = service.isDarkMode()
        val bgColor = if (isDark) "#1e1e1e" else "#fdfdfd"
        val textColor = if (isDark) "#dcdcdc" else "#333"
        val titleColor = if (isDark) "#ffffff" else "#111"
        val linkColor = if (isDark) "#7ab4f5" else "#0645ad"

        val htmlContent = if (chapter.isHtml) {
            injectReaderStyles(chapter.body, isDark, bgColor, textColor, titleColor, linkColor, chapter.title, chapter.stripExistingStyles)
        } else {
            buildPlainTextHtml(chapter, isDark, bgColor, textColor, titleColor)
        }

        renderHtml(htmlContent)
        statusLabel.text = "Chapter ${page + 1} of ${model.chapters.size}"
        highlightTocEntry(page)

        if (saveProgress) {
            service.updateBookProgress(
                file.path, model.title, model.author,
                currentPage, model.chapters.size, moveToTop = moveToTop
            )
        }
    }

    // ──────────────────────────────────────────────
    //  HTML builders
    // ──────────────────────────────────────────────

    private fun injectReaderStyles(
        html: String, isDark: Boolean, bgColor: String,
        textColor: String, titleColor: String, linkColor: String,
        chapterTitle: String, stripExistingStyles: Boolean = false
    ): String {
        val readerCss = """
            /* === R3 Reader overlay === */
            * { box-sizing: border-box; }
            html, body {
                background-color: $bgColor !important;
                color: $textColor !important;
            }
            html, body, p, div, span, h1, h2, h3, h4, h5, h6, a, li, td, th, blockquote, em, strong, b, i, u {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important;
            }
            body {
                max-width: 860px;
                margin: 0 auto !important;
                padding: 32px 24px !important;
                font-size: 17px;
                line-height: 1.75;
                word-wrap: break-word;
                overflow-wrap: break-word;
            }
            img, svg, figure {
                max-width: 100% !important;
                height: auto !important;
                display: block;
                margin: 1em auto;
            }
            a { color: $linkColor !important; }
            h1, h2, h3, h4, h5, h6 { color: $titleColor !important; margin-top: 1.2em; margin-bottom: 0.6em; }
            ${if (isDark) "table, td, th { border-color: #444 !important; }" else ""}
        """.trimIndent()

        val styleTag = "<style id=\"r3-reader-override\">\n$readerCss\n</style>"

        // Conditionally strip existing <style> blocks (MOBI = yes, EPUB = no)
        var cleaned = if (stripExistingStyles) {
            html.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        } else {
            html
        }

        // Extract body content for clean wrapping
        val bodyContent = when {
            cleaned.contains("<body", ignoreCase = true) && cleaned.contains("</body>", ignoreCase = true) -> {
                val bodyMatch = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(cleaned)
                bodyMatch?.groupValues?.get(1) ?: cleaned
            }
            else -> {
                cleaned
                    .replace(Regex("""<\?xml[^>]*>""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""</?html[^>]*>""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""<head[^>]*>.*?</head>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    .replace(Regex("""</?head[^>]*>""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""</?body[^>]*>""", RegexOption.IGNORE_CASE), "")
            }
        }

        // For EPUB, preserve existing <style> blocks by extracting them
        val existingStyles = if (!stripExistingStyles) {
            val styleBlocks = mutableListOf<String>()
            Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(bodyContent).forEach { styleBlocks.add(it.value) }
            styleBlocks.joinToString("\n")
        } else ""

        return """
            <html>
            <head>
                <meta charset="utf-8">
                $existingStyles
                $styleTag
            </head>
            <body>
                $bodyContent
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildPlainTextHtml(
        chapter: Chapter, isDark: Boolean,
        bgColor: String, textColor: String, titleColor: String
    ): String {
        val borderColor = if (isDark) "#333" else "#eee"
        return """
            <html>
            <head>
                <meta charset="utf-8">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        line-height: 1.75; color: $textColor;
                        max-width: 860px; margin: 0 auto; padding: 32px 24px;
                        background-color: $bgColor;
                    }
                    h1, h2, h3 { color: $titleColor; border-bottom: 1px solid $borderColor; padding-bottom: 8px; }
                    p { margin-bottom: 1.4em; text-align: justify; }
                    .chapter-title {
                        font-size: 1.8em; font-weight: bold;
                        margin-bottom: 28px; text-align: center; color: $titleColor;
                    }
                </style>
            </head>
            <body>
                <div class="chapter-title">${chapter.title}</div>
                <div>${chapter.body.replace("\n", "<p>")}</div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderHtml(html: String) {
        cefBrowser.loadHTML(html)
        // Re-apply zoom after content loads
        SwingUtilities.invokeLater { applyZoom() }
    }

    /** Scrolls the current page to an element by id/name once the freshly loaded page settles. */
    private fun scrollToAnchor(id: String) {
        val escaped = id.replace("\\", "\\\\").replace("'", "\\'")
        cefBrowser.cefBrowser.executeJavaScript(
            """
            setTimeout(function() {
                var el = document.getElementById('$escaped') || document.getElementsByName('$escaped')[0];
                if (el) el.scrollIntoView({block: 'start'});
            }, 200);
            """.trimIndent(),
            "", 0
        )
    }

    // ──────────────────────────────────────────────
    //  FileEditor interface
    // ──────────────────────────────────────────────

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = cefBrowser.component
    override fun getName(): String = "R3 Book Reader"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() {
        pdfScrollTimer?.stop()
        cefBrowser.dispose()
    }
}
