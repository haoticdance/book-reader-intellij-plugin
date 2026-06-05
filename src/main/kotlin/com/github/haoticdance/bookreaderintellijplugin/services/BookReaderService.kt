package com.github.haoticdance.bookreaderintellijplugin.services

import com.intellij.openapi.components.*
import com.github.haoticdance.bookreaderintellijplugin.toolWindow.MyToolWindowFactory

@State(
    name = "BookReaderState",
    storages = [Storage("book-reader-state.xml")]
)
@Service(Service.Level.PROJECT)
class BookReaderService(private val project: com.intellij.openapi.project.Project) : PersistentStateComponent<BookReaderService.State> {
    data class RecentBook(
        var path: String = "",
        var title: String = "",
        var author: String = "",
        var lastPage: Int = 0,
        var totalPages: Int = 0,
        var lastOpenedTime: Long = 0
    )

    data class State(
        var recentBooks: MutableList<RecentBook> = mutableListOf(),
        var isDarkMode: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState.recentBooks.clear()
        myState.recentBooks.addAll(state.recentBooks)
        myState.isDarkMode = state.isDarkMode
    }

    fun isDarkMode(): Boolean = myState.isDarkMode
    
    fun setDarkMode(enabled: Boolean) {
        myState.isDarkMode = enabled
        project.messageBus.syncPublisher(MyToolWindowFactory.SETTINGS_TOPIC).onSettingsChanged()
    }

    fun updateBookProgress(path: String, title: String, author: String, page: Int, total: Int, moveToTop: Boolean = true) {
        val existing = myState.recentBooks.find { it.path == path }
        if (existing != null) {
            existing.lastPage = page
            existing.totalPages = total
            existing.lastOpenedTime = System.currentTimeMillis()
            if (moveToTop) {
                myState.recentBooks.remove(existing)
                myState.recentBooks.add(0, existing)
            }
        } else {
            myState.recentBooks.add(0, RecentBook(path, title, author, page, total, System.currentTimeMillis()))
        }
        
        if (myState.recentBooks.size > 10) {
            myState.recentBooks.removeAt(myState.recentBooks.size - 1)
        }
        
        project.messageBus.syncPublisher(MyToolWindowFactory.TOPIC).onBookUpdated()
    }

    fun removeBook(path: String) {
        if (myState.recentBooks.removeIf { it.path == path }) {
            project.messageBus.syncPublisher(MyToolWindowFactory.TOPIC).onBookUpdated()
        }
    }

    fun getRecentBooks(): List<RecentBook> = myState.recentBooks
    
    fun getLastPath(): String? = myState.recentBooks.firstOrNull()?.path
    fun getLastPage(): Int = myState.recentBooks.firstOrNull()?.lastPage ?: 0
}
