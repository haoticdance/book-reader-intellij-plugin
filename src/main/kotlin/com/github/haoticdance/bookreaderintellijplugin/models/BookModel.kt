package com.github.haoticdance.bookreaderintellijplugin.models

data class BookModel(
    val title: String,
    val author: String,
    val chapters: List<Chapter>
)

data class Chapter(
    val title: String,
    val body: String
)
