package com.github.haoticdance.bookreaderintellijplugin.editor

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.parsers.EpubParser
import com.github.haoticdance.bookreaderintellijplugin.parsers.FB2Parser
import com.github.haoticdance.bookreaderintellijplugin.services.BookReaderService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import com.github.haoticdance.bookreaderintellijplugin.toolWindow.MyToolWindowFactory
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.*

class BookFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())
    private val statusLabel = JLabel("", SwingConstants.CENTER)
    
    private var bookModel: BookModel? = null
    private var currentPage = 0
    private val service = project.service<BookReaderService>()
    
    private val cefBrowser = JBCefBrowser()
    private var pdfScrollTimer: Timer? = null
    private var jsQuery: JBCefJSQuery? = null

    init {
        setupUI()
        setupCefHandlers()
        loadBook()
        
        project.messageBus.connect().subscribe(MyToolWindowFactory.SETTINGS_TOPIC, object : MyToolWindowFactory.BookReaderSettingsListener {
            override fun onSettingsChanged() {
                if (file.extension?.lowercase() != "pdf") {
                    showPage(currentPage)
                }
            }
        })
    }

    private fun setupUI() {
        panel.add(cefBrowser.component, BorderLayout.CENTER)
        
        val navPanel = JPanel(BorderLayout())
        val themeButton = JButton(if (service.isDarkMode()) "☀ Light" else "🌙 Night").apply {
            addActionListener {
                val newMode = !service.isDarkMode()
                service.setDarkMode(newMode)
                text = if (newMode) "☀ Light" else "🌙 Night"
            }
        }
        
        if (file.extension?.lowercase() != "pdf") {
            val prevButton = JButton("Previous").apply {
                addActionListener { showPage(currentPage - 1) }
            }
            val nextButton = JButton("Next").apply {
                addActionListener { showPage(currentPage + 1) }
            }
            
            navPanel.add(prevButton, BorderLayout.WEST)
            navPanel.add(statusLabel, BorderLayout.CENTER)
            val rightPanel = JPanel(BorderLayout())
            rightPanel.add(nextButton, BorderLayout.WEST)
            rightPanel.add(themeButton, BorderLayout.EAST)
            navPanel.add(rightPanel, BorderLayout.EAST)
        } else {
            // No theme button for PDF as it's not supported by renderer
            navPanel.add(JLabel(" PDF: ${file.name}"), BorderLayout.WEST)
        }
        
        panel.add(navPanel, BorderLayout.SOUTH)
    }

    private fun setupCefHandlers() {
        cefBrowser.jbCefClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
            override fun onKeyEvent(browser: CefBrowser?, event: CefKeyboardHandler.CefKeyEvent?): Boolean {
                if (event?.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) {
                    if (file.extension?.lowercase() != "pdf") {
                        when (event.windows_key_code) {
                            KeyEvent.VK_LEFT -> {
                                showPage(currentPage - 1)
                                return true
                            }
                            KeyEvent.VK_RIGHT -> {
                                showPage(currentPage + 1)
                                return true
                            }
                        }
                    }
                }
                return false
            }
        }, cefBrowser.cefBrowser)

        if (file.extension?.lowercase() == "pdf") {
            jsQuery = JBCefJSQuery.create(cefBrowser).apply {
                addHandler { dataStr ->
                    try {
                        val parts = dataStr.split(",")
                        val scrollY = parts[0].toDouble().toInt()
                        val totalHeight = parts[1].toDouble().toInt()
                        if (totalHeight > 0) {
                            service.updateBookProgress(file.path, file.name, "PDF Document", scrollY, totalHeight, moveToTop = false)
                        }
                    } catch (e: Exception) {}
                    null
                }
            }

            cefBrowser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, url: String?) {
                    val lastBook = service.getRecentBooks().find { it.path == file.path }
                    if (lastBook != null && lastBook.lastPage > 0) {
                        cefBrowser.cefBrowser.executeJavaScript("""
                            setTimeout(function() {
                                window.scrollTo(0, ${lastBook.lastPage});
                            }, 1000);
                        """.trimIndent(), "", 0)
                    }
                }
            }, cefBrowser.cefBrowser)

            pdfScrollTimer = Timer(5000) { // Increased to 5s to reduce list updates
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

    private fun loadBook() {
        val ioFile = File(file.path)
        val recent = service.getRecentBooks().find { it.path == file.path }
        
        if (file.extension?.lowercase() == "pdf") {
            cefBrowser.loadURL("file://${ioFile.absolutePath}")
            service.updateBookProgress(file.path, file.name, "PDF Document", recent?.lastPage ?: 0, recent?.totalPages ?: 0, moveToTop = true)
            return
        }

        try {
            bookModel = when (file.extension?.lowercase()) {
                "fb2" -> FB2Parser().parse(ioFile.inputStream())
                "epub" -> EpubParser().parse(ioFile)
                else -> null
            }
            
            val model = bookModel
            if (model != null) {
                if (recent != null) {
                    currentPage = recent.lastPage
                }
                showPage(currentPage, saveProgress = true, moveToTop = true)
            } else {
                renderHtml("<h1>Unsupported format or parsing error</h1>")
            }
        } catch (e: Exception) {
            renderHtml("<h1>Error loading book</h1><p>${e.message}</p>")
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
        val borderColor = if (isDark) "#333" else "#eee"

        val htmlContent = """
            <html>
            <head>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        line-height: 1.6;
                        color: $textColor;
                        max-width: 800px;
                        margin: 0 auto;
                        padding: 40px 20px;
                        background-color: $bgColor;
                        transition: background-color 0.3s, color 0.3s;
                    }
                    h1, h2, h3 {
                        color: $titleColor;
                        border-bottom: 1px solid $borderColor;
                        padding-bottom: 10px;
                    }
                    p {
                        margin-bottom: 1.5em;
                        text-align: justify;
                    }
                    .chapter-title {
                        font-size: 2em;
                        margin-bottom: 30px;
                        text-align: center;
                        color: $titleColor;
                    }
                </style>
            </head>
            <body>
                <div class="chapter-title">${chapter.title}</div>
                <div>${chapter.body.replace("\n", "<p>")}</div>
            </body>
            </html>
        """.trimIndent()
        
        renderHtml(htmlContent)
        statusLabel.text = "Chapter ${page + 1} of ${model.chapters.size}"
        if (saveProgress) {
            service.updateBookProgress(file.path, model.title, model.author, currentPage, model.chapters.size, moveToTop = moveToTop)
        }
    }

    private fun renderHtml(html: String) {
        cefBrowser.loadHTML(html)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = cefBrowser.component
    override fun getName(): String = "Book Reader"
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
