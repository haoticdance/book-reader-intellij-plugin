package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class EpubParser {
    fun parse(file: File): BookModel {
        val zipFile = ZipFile(file)
        var opfPath = ""

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()

        // 1. Find META-INF/container.xml
        val containerEntry = zipFile.getEntry("META-INF/container.xml")
            ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml not found")

        zipFile.getInputStream(containerEntry).use { input ->
            val doc = builder.parse(input)
            val rootfiles = doc.getElementsByTagName("rootfile")
            if (rootfiles.length > 0) {
                opfPath = (rootfiles.item(0) as Element).getAttribute("full-path")
            }
        }

        if (opfPath.isEmpty()) {
            throw IllegalArgumentException("Invalid EPUB: OPF path not found in container.xml")
        }

        // 2. Parse OPF file
        val opfEntry = zipFile.getEntry(opfPath)
            ?: throw IllegalArgumentException("Invalid EPUB: OPF file not found at $opfPath")

        var title = "Unknown Title"
        var author = "Unknown Author"
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()

        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

        zipFile.getInputStream(opfEntry).use { input ->
            val doc = builder.parse(input)

            // Parse metadata
            val titles = doc.getElementsByTagName("dc:title")
            if (titles.length > 0) title = titles.item(0).textContent

            val creators = doc.getElementsByTagName("dc:creator")
            if (creators.length > 0) author = creators.item(0).textContent

            // Parse manifest
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                manifest[item.getAttribute("id")] = item.getAttribute("href")
            }

            // Parse spine
            val itemrefs = doc.getElementsByTagName("itemref")
            for (i in 0 until itemrefs.length) {
                val itemref = itemrefs.item(i) as Element
                spine.add(itemref.getAttribute("idref"))
            }
        }

        // 3. Read chapters
        val chapters = mutableListOf<Chapter>()
        for ((index, idref) in spine.withIndex()) {
            val href = manifest[idref] ?: continue
            val entryPath = opfDir + href
            val chapterEntry = zipFile.getEntry(entryPath) ?: continue

            val content = zipFile.getInputStream(chapterEntry).bufferedReader().use { it.readText() }

            // Basic HTML parsing (just stripping tags for now, or could keep it as HTML)
            val bodyText = extractTextFromHtml(content)

            chapters.add(Chapter("Chapter ${index + 1}", bodyText))
        }

        zipFile.close()
        return BookModel(title, author, chapters)
    }

    private fun extractTextFromHtml(html: String): String {
        // A simple tag stripper
        val noScripts = html.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        val noStyles = noScripts.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        val noTags = noStyles.replace(Regex("<[^>]*>"), " ")
        return noTags.replace(Regex("\\s+"), " ").trim()
    }
}
