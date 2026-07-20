package com.github.haoticdance.bookreaderintellijplugin.models

/**
 * Pseudo-URL scheme used to encode in-book navigation targets (e.g. resolved MOBI
 * filepos/anchor links) as real, clickable `<a href>` values. The reader's CEF request
 * handler intercepts navigation to this scheme and calls back into the editor instead
 * of letting the embedded browser attempt to load it.
 */
const val INTERNAL_LINK_SCHEME = "r3book://page/"

data class BookModel(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val toc: List<TocNode>? = null
)

data class Chapter(
    val title: String,
    val body: String,
    val isHtml: Boolean = false,
    val stripExistingStyles: Boolean = false
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
