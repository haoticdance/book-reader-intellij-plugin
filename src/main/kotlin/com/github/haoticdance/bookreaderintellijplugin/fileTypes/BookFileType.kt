package com.github.haoticdance.bookreaderintellijplugin.fileTypes

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class BookFileType : FileType {
    override fun getName(): String = "Book"

    override fun getDescription(): String = "Book and Document Files (FB2, EPUB, PDF)"

    override fun getDefaultExtension(): String = "epub"

    override fun getIcon(): Icon = IconLoader.getIcon("/icons/book.svg", BookFileType::class.java)

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null

    companion object {
        val INSTANCE = BookFileType()
    }
}
