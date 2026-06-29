package com.github.haoticdance.bookreaderintellijplugin.models

data class BookModel(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val toc: List<TocNode>? = null
)

data class Chapter(
    val title: String,
    val body: String,
    val isHtml: Boolean = false
)

/**
 * Hierarchical table-of-contents entry.
 * [chapterIndex] maps to BookModel.chapters index (-1 = no direct content).
 */
data class TocNode(
    val title: String,
    val chapterIndex: Int = -1,
    val children: List<TocNode> = emptyList()
)
