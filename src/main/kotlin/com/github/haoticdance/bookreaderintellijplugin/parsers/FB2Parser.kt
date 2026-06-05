package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class FB2Parser {
    fun parse(inputStream: InputStream): BookModel {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // ignore
        }

        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)

        doc.documentElement.normalize()

        var title = "Unknown Title"
        var author = "Unknown Author"

        val descriptionList = doc.getElementsByTagName("description")
        if (descriptionList.length > 0) {
            val titleInfoList = (descriptionList.item(0) as org.w3c.dom.Element).getElementsByTagName("title-info")
            if (titleInfoList.length > 0) {
                val titleInfo = titleInfoList.item(0) as org.w3c.dom.Element

                val bookTitleList = titleInfo.getElementsByTagName("book-title")
                if (bookTitleList.length > 0) {
                    title = bookTitleList.item(0).textContent
                }

                val authorList = titleInfo.getElementsByTagName("author")
                if (authorList.length > 0) {
                    val authorElem = authorList.item(0) as org.w3c.dom.Element
                    val firstName = authorElem.getElementsByTagName("first-name").item(0)?.textContent ?: ""
                    val lastName = authorElem.getElementsByTagName("last-name").item(0)?.textContent ?: ""
                    author = "$firstName $lastName".trim()
                    if (author.isEmpty()) {
                        val nickname = authorElem.getElementsByTagName("nickname").item(0)?.textContent ?: ""
                        author = nickname.trim()
                    }
                }
            }
        }

        val chapters = mutableListOf<Chapter>()
        val bodyList = doc.getElementsByTagName("body")
        if (bodyList.length > 0) {
            val mainBody = bodyList.item(0) as org.w3c.dom.Element
            val sections = mainBody.getElementsByTagName("section")

            for (i in 0 until sections.length) {
                val section = sections.item(i) as org.w3c.dom.Element

                var sectionTitle = "Chapter ${i + 1}"
                val titleElemList = section.getElementsByTagName("title")
                if (titleElemList.length > 0) {
                    val pList = (titleElemList.item(0) as org.w3c.dom.Element).getElementsByTagName("p")
                    if (pList.length > 0) {
                        sectionTitle = pList.item(0).textContent.trim()
                    } else {
                        sectionTitle = titleElemList.item(0).textContent.trim()
                    }
                }

                val sb = StringBuilder()
                val childNodes = section.childNodes
                for (j in 0 until childNodes.length) {
                    val child = childNodes.item(j)
                    if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE && child.nodeName != "title") {
                        sb.append(child.textContent).append("\n")
                    }
                }

                chapters.add(Chapter(sectionTitle, sb.toString().trim()))
            }
        }

        return BookModel(title, author, chapters)
    }
}
