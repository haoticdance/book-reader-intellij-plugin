package com.github.haoticdance.bookreaderintellijplugin.editor

import com.github.haoticdance.bookreaderintellijplugin.fileTypes.BookFileType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class BookFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.fileType is BookFileType
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return BookFileEditor(project, file)
    }

    override fun getEditorTypeId(): String = "book-reader-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
