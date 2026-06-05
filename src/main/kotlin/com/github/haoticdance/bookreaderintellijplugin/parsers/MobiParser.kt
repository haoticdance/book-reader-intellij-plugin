package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import java.io.File
import java.io.InputStream

class MobiParser {
    fun parse(file: File): BookModel {
        // TODO: Integrate com.github.binarywang:java-mobi-parser or analog
        // For now, providing a basic fallback or stub to satisfy the architecture requirement
        
        val title = file.nameWithoutExtension
        val author = "Unknown Author"
        
        val chapters = mutableListOf<Chapter>()
        chapters.add(Chapter("Chapter 1", "MOBI parsing is not fully implemented yet. Please add java-mobi-parser dependency."))
        
        return BookModel(title, author, chapters)
    }
}
