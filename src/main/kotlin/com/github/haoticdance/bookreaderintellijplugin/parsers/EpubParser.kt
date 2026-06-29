package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import com.github.haoticdance.bookreaderintellijplugin.models.TocNode
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class EpubParser {
    fun parse(file: File): BookModel {
        ZipFile(file).use { zipFile ->
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
            val manifest = mutableMapOf<String, String>()        // id -> href
            val manifestMediaType = mutableMapOf<String, String>() // id -> media-type
            val spine = mutableListOf<String>()

            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            zipFile.getInputStream(opfEntry).use { input ->
                val doc = builder.parse(input)

                val titles = doc.getElementsByTagName("dc:title")
                if (titles.length > 0) title = titles.item(0).textContent.trim()

                val creators = doc.getElementsByTagName("dc:creator")
                if (creators.length > 0) author = creators.item(0).textContent.trim()

                val items = doc.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as Element
                    val id = item.getAttribute("id")
                    manifest[id] = item.getAttribute("href")
                    manifestMediaType[id] = item.getAttribute("media-type")
                }

                val itemrefs = doc.getElementsByTagName("itemref")
                for (i in 0 until itemrefs.length) {
                    val itemref = itemrefs.item(i) as Element
                    spine.add(itemref.getAttribute("idref"))
                }
            }

            // 3. Build asset caches
            val imageCache = buildImageCache(zipFile, opfDir, manifest, manifestMediaType)
            val cssCache = buildCssCache(zipFile, opfDir, manifest, manifestMediaType)

            // 4. Build href -> spine-index mapping (for TOC resolution)
            val hrefToSpineIndex = mutableMapOf<String, Int>()
            for ((index, idref) in spine.withIndex()) {
                val href = manifest[idref] ?: continue
                val hrefClean = href.substringBefore("#")
                hrefToSpineIndex[hrefClean] = index
                // Also map by filename only for fuzzy match
                hrefToSpineIndex[hrefClean.substringAfterLast("/")] = index
            }

            // 5. Read flat chapter titles + hierarchical TOC tree
            val chapterTitles = readFlatChapterTitles(zipFile, opfDir, manifest, manifestMediaType, builder, spine)
            val tocTree = readTocTree(zipFile, opfDir, manifest, manifestMediaType, builder, hrefToSpineIndex)

            // 6. Read and process chapters
            val chapters = mutableListOf<Chapter>()
            for ((index, idref) in spine.withIndex()) {
                val href = manifest[idref] ?: continue
                val hrefClean = href.substringBefore("#")
                val entryPath = resolveZipPath(opfDir, hrefClean)
                val chapterEntry = zipFile.getEntry(entryPath) ?: continue

                val rawHtml = zipFile.getInputStream(chapterEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val chapterDir = if (entryPath.contains("/")) entryPath.substringBeforeLast("/") + "/" else ""
                val processedHtml = processChapterHtml(rawHtml, chapterDir, imageCache, cssCache, opfDir)

                val chapterTitle = chapterTitles.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${index + 1}"
                chapters.add(Chapter(chapterTitle, processedHtml, isHtml = true))
            }

            return BookModel(title, author, chapters, toc = tocTree.ifEmpty { null })
        }
    }

    // ──────────────────────────────────────────────
    //  TOC: hierarchical tree (from NCX navPoints)
    // ──────────────────────────────────────────────

    /**
     * Parse NCX to build a hierarchical TOC tree.
     * Each navPoint can contain nested navPoints — we recurse.
     */
    private fun readTocTree(
        zipFile: ZipFile,
        opfDir: String,
        manifest: Map<String, String>,
        manifestMediaType: Map<String, String>,
        builder: javax.xml.parsers.DocumentBuilder,
        hrefToSpineIndex: Map<String, Int>
    ): List<TocNode> {
        val ncxId = manifest.entries.find { manifestMediaType[it.key] == "application/x-dtbncx+xml" }?.key
            ?: return emptyList()

        val ncxHref = manifest[ncxId] ?: return emptyList()
        val ncxPath = resolveZipPath(opfDir, ncxHref)
        val ncxEntry = zipFile.getEntry(ncxPath) ?: return emptyList()

        return try {
            val ncxDoc = zipFile.getInputStream(ncxEntry).use { builder.parse(it) }
            // Find <navMap> element
            val navMaps = ncxDoc.getElementsByTagName("navMap")
            if (navMaps.length == 0) return emptyList()
            val navMap = navMaps.item(0) as Element
            parseChildNavPoints(navMap, hrefToSpineIndex)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parse direct child <navPoint> elements of [parent], recursing into nested ones.
     */
    private fun parseChildNavPoints(parent: Element, hrefToSpineIndex: Map<String, Int>): List<TocNode> {
        val result = mutableListOf<TocNode>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val el = child as Element
            if (el.localName != "navPoint" && el.tagName != "navPoint") continue

            // Extract label (direct child navLabel only)
            var label = ""
            var src = ""
            val directChildren = el.childNodes
            for (j in 0 until directChildren.length) {
                val dc = directChildren.item(j)
                if (dc.nodeType != Node.ELEMENT_NODE) continue
                val dce = dc as Element
                when {
                    dce.localName == "navLabel" || dce.tagName == "navLabel" -> {
                        label = dce.textContent.trim()
                    }
                    dce.localName == "content" || dce.tagName == "content" -> {
                        src = dce.getAttribute("src").substringBefore("#")
                    }
                }
            }

            if (label.isBlank()) continue

            val chapterIndex = hrefToSpineIndex[src]
                ?: hrefToSpineIndex[src.substringAfterLast("/")]
                ?: -1

            // Recurse into nested navPoints
            val nestedChildren = parseChildNavPoints(el, hrefToSpineIndex)

            result.add(TocNode(label, chapterIndex, nestedChildren))
        }
        return result
    }

    // ──────────────────────────────────────────────
    //  TOC: flat titles (for Chapter objects)
    // ──────────────────────────────────────────────

    private fun readFlatChapterTitles(
        zipFile: ZipFile,
        opfDir: String,
        manifest: Map<String, String>,
        manifestMediaType: Map<String, String>,
        builder: javax.xml.parsers.DocumentBuilder,
        spine: List<String>
    ): List<String> {
        val hrefToLabel = mutableMapOf<String, String>()

        val ncxId = manifest.entries.find { manifestMediaType[it.key] == "application/x-dtbncx+xml" }?.key

        if (ncxId != null) {
            val ncxHref = manifest[ncxId] ?: ""
            val ncxPath = resolveZipPath(opfDir, ncxHref)
            val ncxEntry = zipFile.getEntry(ncxPath)
            if (ncxEntry != null) {
                try {
                    val ncxDoc = zipFile.getInputStream(ncxEntry).use { builder.parse(it) }
                    collectNavPointLabels(ncxDoc.documentElement, hrefToLabel)
                } catch (_: Exception) {}
            }
        }

        return spine.map { idref ->
            val href = manifest[idref] ?: return@map null
            val hrefClean = href.substringBefore("#")
            hrefToLabel[hrefClean]
                ?: hrefToLabel[hrefClean.substringAfterLast("/")]
        }.map { it ?: "" }
    }

    /** Recursively collect all navPoint labels into a flat href->label map. */
    private fun collectNavPointLabels(element: Element, out: MutableMap<String, String>) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val el = child as Element
            if (el.localName == "navPoint" || el.tagName == "navPoint") {
                var label = ""
                var src = ""
                val dc = el.childNodes
                for (j in 0 until dc.length) {
                    val c = dc.item(j)
                    if (c.nodeType != Node.ELEMENT_NODE) continue
                    val ce = c as Element
                    when {
                        ce.localName == "navLabel" || ce.tagName == "navLabel" -> label = ce.textContent.trim()
                        ce.localName == "content" || ce.tagName == "content" -> src = ce.getAttribute("src").substringBefore("#")
                    }
                }
                if (label.isNotBlank() && src.isNotBlank()) {
                    out.putIfAbsent(src, label) // first (top-level) wins for flat list
                }
                // Recurse into nested navPoints
                collectNavPointLabels(el, out)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Asset caches & HTML processing (unchanged)
    // ──────────────────────────────────────────────

    private fun buildImageCache(
        zipFile: ZipFile, opfDir: String,
        manifest: Map<String, String>, manifestMediaType: Map<String, String>
    ): Map<String, String> {
        val cache = mutableMapOf<String, String>()
        for ((id, href) in manifest) {
            val mime = manifestMediaType[id] ?: continue
            if (!mime.startsWith("image/")) continue
            val entryPath = resolveZipPath(opfDir, href)
            val entry = zipFile.getEntry(entryPath) ?: continue
            try {
                val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                val b64 = Base64.getEncoder().encodeToString(bytes)
                cache[entryPath] = "data:$mime;base64,$b64"
                cache[href] = "data:$mime;base64,$b64"
            } catch (_: Exception) {}
        }
        return cache
    }

    private fun buildCssCache(
        zipFile: ZipFile, opfDir: String,
        manifest: Map<String, String>, manifestMediaType: Map<String, String>
    ): Map<String, String> {
        val cache = mutableMapOf<String, String>()
        for ((id, href) in manifest) {
            val mime = manifestMediaType[id] ?: continue
            if (mime != "text/css") continue
            val entryPath = resolveZipPath(opfDir, href)
            val entry = zipFile.getEntry(entryPath) ?: continue
            try {
                val css = zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                cache[entryPath] = css
                cache[href] = css
            } catch (_: Exception) {}
        }
        return cache
    }

    private fun processChapterHtml(
        html: String, chapterDir: String,
        imageCache: Map<String, String>, cssCache: Map<String, String>, opfDir: String
    ): String {
        var result = html
        result = result.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")

        result = result.replace(Regex("""<link[^>]+rel=["']stylesheet["'][^>]*>""", RegexOption.IGNORE_CASE)) { mr ->
            val hrefMatch = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(mr.value)
            val cssHref = hrefMatch?.groupValues?.get(1) ?: return@replace ""
            val cssAbsPath = resolveZipPath(chapterDir, cssHref)
            val cssText = cssCache[cssAbsPath] ?: cssCache[cssHref] ?: cssCache[resolveZipPath(opfDir, cssHref)] ?: return@replace ""
            "<style>\n${sanitizeCss(cssText)}\n</style>"
        }

        result = result.replace(Regex("""<link[^>]+href=["']([^"']+\.css)["'][^>]*>""", RegexOption.IGNORE_CASE)) { mr ->
            val hrefMatch = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(mr.value)
            val cssHref = hrefMatch?.groupValues?.get(1) ?: return@replace ""
            val cssAbsPath = resolveZipPath(chapterDir, cssHref)
            val cssText = cssCache[cssAbsPath] ?: cssCache[cssHref] ?: cssCache[resolveZipPath(opfDir, cssHref)] ?: return@replace ""
            "<style>\n${sanitizeCss(cssText)}\n</style>"
        }

        result = result.replace(Regex("""(<img[^>]+src=["'])([^"']+)(["'][^>]*>)""", RegexOption.IGNORE_CASE)) { mr ->
            val prefix = mr.groupValues[1]; val src = mr.groupValues[2]; val suffix = mr.groupValues[3]
            if (src.startsWith("data:")) return@replace mr.value
            val absPath = resolveZipPath(chapterDir, src)
            val dataUri = imageCache[absPath] ?: imageCache[src] ?: imageCache[resolveZipPath(opfDir, src)] ?: return@replace mr.value
            "$prefix$dataUri$suffix"
        }

        result = result.replace(Regex("""(xlink:href=["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)) { mr ->
            val prefix = mr.groupValues[1]; val src = mr.groupValues[2]; val suffix = mr.groupValues[3]
            if (src.startsWith("data:")) return@replace mr.value
            val absPath = resolveZipPath(chapterDir, src)
            val dataUri = imageCache[absPath] ?: imageCache[src] ?: return@replace mr.value
            "$prefix$dataUri$suffix"
        }

        return result
    }

    private fun sanitizeCss(css: String): String {
        var result = css.replace(Regex("""@import[^;]+;"""), "")
        result = result.replace(Regex("""url\(['"]?[^'")]+\.(ttf|otf|woff|woff2|eot)['"]?\)""", RegexOption.IGNORE_CASE), "url()")
        return result
    }

    private fun resolveZipPath(baseDir: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("data:")) return href
        val combined = if (baseDir.isEmpty()) href else "$baseDir$href"
        val parts = combined.split("/").toMutableList()
        val normalized = mutableListOf<String>()
        for (part in parts) {
            when {
                part == ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.size - 1)
                part != "." && part.isNotEmpty() -> normalized.add(part)
            }
        }
        return normalized.joinToString("/")
    }
}
