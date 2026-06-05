package com.github.haoticdance.bookreaderintellijplugin.toolWindow

import com.github.haoticdance.bookreaderintellijplugin.services.BookReaderService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.messages.Topic
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder

class MyToolWindowFactory : ToolWindowFactory {
    companion object {
        val TOPIC = Topic.create("BookReaderUpdate", BookReaderUpdateListener::class.java)
        val SETTINGS_TOPIC = Topic.create("BookReaderSettings", BookReaderSettingsListener::class.java)
    }

    interface BookReaderUpdateListener {
        fun onBookUpdated()
    }

    interface BookReaderSettingsListener {
        fun onSettingsChanged()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
        
        project.messageBus.connect().subscribe(TOPIC, object : BookReaderUpdateListener {
            override fun onBookUpdated() {
                UIUtil.invokeLaterIfNeeded {
                    if (!toolWindow.isDisposed) {
                        myToolWindow.updateList()
                    }
                }
            }
        })
    }

    class MyToolWindow(private val project: Project) {
        private val service = project.service<BookReaderService>()
        private val listModel = DefaultListModel<BookReaderService.RecentBook>()
        private val mainPanel = JPanel(BorderLayout())
        private val list = JBList(listModel)

        fun getContent(): JComponent {
            mainPanel.background = UIUtil.getPanelBackground()

            // Header with "Open New Book"
            val headerPanel = JPanel(BorderLayout())
            headerPanel.border = JBUI.Borders.empty(10)
            val openButton = JButton("Open New Book").apply {
                addActionListener {
                    val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withFileFilter { it.extension?.lowercase() in listOf("fb2", "epub", "pdf") }
                    
                    val file = FileChooser.chooseFile(descriptor, project, null)
                    if (file != null) {
                        FileEditorManager.getInstance(project).openFile(file, true)
                    }
                }
            }
            headerPanel.add(openButton, BorderLayout.CENTER)
            mainPanel.add(headerPanel, BorderLayout.NORTH)

            // Recent Books List
            updateList()
            
            list.apply {
                cellRenderer = BookListCellRenderer()
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                border = EmptyBorder(0, 0, 0, 0)
                
                // Click to open or remove
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        val index = locationToIndex(e.point)
                        if (index != -1) {
                            val selected = listModel.getElementAt(index)
                            val cellBounds = getCellBounds(index, index)
                            
                            // Check if trash icon was clicked (it's on the right side)
                            val trashIconWidth = 40
                            if (e.x > cellBounds.x + cellBounds.width - trashIconWidth) {
                                // Remove book from history
                                service.removeBook(selected.path)
                                
                                // Close editor if open
                                val vFile = LocalFileSystem.getInstance().findFileByPath(selected.path)
                                if (vFile != null) {
                                    FileEditorManager.getInstance(project).closeFile(vFile)
                                }
                            } else {
                                // Open book
                                val vFile = LocalFileSystem.getInstance().findFileByPath(selected.path)
                                if (vFile != null && vFile.exists()) {
                                    FileEditorManager.getInstance(project).openFile(vFile, true)
                                }
                            }
                        }
                    }
                })
            }

            val scrollPane = JBScrollPane(list)
            scrollPane.border = JBUI.Borders.empty()
            mainPanel.add(scrollPane, BorderLayout.CENTER)

            return mainPanel
        }

        fun updateList() {
            val recentBooks = service.getRecentBooks()
            
            // Check if we need to show the empty label
            val hasBooks = recentBooks.isNotEmpty()
            val centerComponent = (mainPanel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
            
            if (!hasBooks) {
                if (centerComponent !is JBLabel) {
                    mainPanel.remove(centerComponent)
                    val emptyLabel = JBLabel("<html><center>No recent books. Start reading!<br><br><font color='gray'>Supported formats: FB2, EPUB, PDF</font></center></html>").apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                    mainPanel.add(emptyLabel, BorderLayout.CENTER)
                    mainPanel.revalidate()
                    mainPanel.repaint()
                }
            } else {
                if (centerComponent is JBLabel) {
                    mainPanel.remove(centerComponent)
                    val scrollPane = JBScrollPane(list)
                    scrollPane.border = JBUI.Borders.empty()
                    mainPanel.add(scrollPane, BorderLayout.CENTER)
                    mainPanel.revalidate()
                    mainPanel.repaint()
                }
                
                listModel.clear()
                recentBooks.forEach { listModel.addElement(it) }
            }
        }
    }

    private class BookListCellRenderer : ListCellRenderer<BookReaderService.RecentBook> {
        override fun getListCellRendererComponent(
            list: JList<out BookReaderService.RecentBook>,
            value: BookReaderService.RecentBook,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(GridBagLayout())
            panel.border = JBUI.Borders.empty(8, 12)
            panel.background = if (isSelected) list.selectionBackground else list.background

            val gbc = GridBagConstraints()
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0

            // Title
            val titleLabel = JLabel(value.title.ifEmpty { File(value.path).name })
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
            titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            gbc.gridy = 0
            gbc.gridx = 0
            panel.add(titleLabel, gbc)

            // Author
            if (value.author.isNotEmpty()) {
                val authorLabel = JLabel(value.author)
                authorLabel.font = authorLabel.font.deriveFont(11f)
                authorLabel.foreground = if (isSelected) list.selectionForeground else UIUtil.getInactiveTextColor()
                gbc.gridy = 1
                gbc.gridx = 0
                panel.add(authorLabel, gbc)
            }

            // Progress
            val isPdf = value.path.lowercase().endsWith(".pdf")
            if (!isPdf) {
                val progressPercent = if (value.totalPages > 0) (value.lastPage + 1) * 100 / value.totalPages else 0
                val progressLabel = JLabel("Progress: $progressPercent% (${value.lastPage + 1}/${value.totalPages})")
                progressLabel.font = progressLabel.font.deriveFont(10f)
                progressLabel.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
                gbc.gridy = 2
                gbc.gridx = 0
                panel.add(progressLabel, gbc)
            }

            // Trash Icon
            val trashLabel = JLabel(com.intellij.icons.AllIcons.Actions.GC)
            gbc.gridx = 1
            gbc.gridy = 0
            gbc.gridheight = 3
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.EAST
            panel.add(trashLabel, gbc)

            return panel
        }
    }
}
