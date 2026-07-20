package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import com.github.haoticdance.bookreaderintellijplugin.models.INTERNAL_LINK_SCHEME
import com.github.haoticdance.bookreaderintellijplugin.models.TocNode
import java.io.File
import java.nio.charset.Charset
import java.util.Base64

/**
 * A robust, high-performance standalone parser for MOBI / AZW3 / KF8 / PalmDOC files.
 * Reads the file into a safe memory buffer, extracts metadata from MOBI/EXTH headers,
 * decompresses PalmDOC text records while cleanly stripping trailing extra data bytes,
 * extracts embedded image records as Base64 data URIs, cleans HTML styling,
 * and builds chapters with a hierarchical table of contents.
 */
class MobiParser {

    fun parse(file: File): BookModel {
        val data = file.readBytes()
        if (data.size < 78) {
            throw IllegalArgumentException("Invalid MOBI file: file is too small (${data.size} bytes).")
        }

        // 1. PDB Header (first 78 bytes)
        var title = readNullTerminatedString(data, 0, 32, Charsets.ISO_8859_1)
        if (title.isEmpty()) {
            title = file.nameWithoutExtension
        }

        val numRecords = readUInt16(data, 76)
        if (numRecords <= 0) {
            throw IllegalArgumentException("Invalid MOBI file: 0 records found.")
        }

        val recordOffsets = IntArray(numRecords)
        var pos = 78
        for (i in 0 until numRecords) {
            if (pos + 8 > data.size) {
                throw IllegalArgumentException("Invalid MOBI file: unexpected EOF in record directory.")
            }
            recordOffsets[i] = readInt32(data, pos)
            pos += 8
        }

        fun getRecord(index: Int): ByteArray {
            if (index < 0 || index >= numRecords) return ByteArray(0)
            val start = recordOffsets[index]
            val end = if (index + 1 < numRecords) recordOffsets[index + 1] else data.size
            if (start < 0 || start >= data.size || end < start) return ByteArray(0)
            return data.copyOfRange(start, minOf(end, data.size))
        }

        // 2. Parse Record 0 (PalmDOC / MOBI / EXTH Headers)
        val rec0 = getRecord(0)
        if (rec0.size < 16) {
            throw IllegalArgumentException("Invalid MOBI file: Record 0 is too small.")
        }

        val compression = readUInt16(rec0, 0)
        val recordCount = readUInt16(rec0, 8)
        val encryption = readUInt16(rec0, 12)
        if (encryption != 0) {
            throw IllegalArgumentException("DRM-protected MOBI files are not supported.")
        }

        var author = "Unknown Author"
        var charset = Charsets.ISO_8859_1
        var firstImageRecord = -1
        var extraDataFlags = 0

        // Check for MOBI header at offset 16
        if (rec0.size >= 32) {
            val magic = readString(rec0, 16, 4, Charsets.US_ASCII)
            if (magic == "MOBI") {
                val mobiHeaderLen = readInt32(rec0, 20)
                val textEncoding = readInt32(rec0, 28)
                charset = when (textEncoding) {
                    65001 -> Charsets.UTF_8
                    1251 -> Charset.forName("CP1251")
                    else -> Charset.forName("CP1252")
                }

                // Full title from MOBI header (MOBI+0x54 and MOBI+0x58)
                if (rec0.size >= 16 + 0x5C) {
                    val fullNameOffset = readInt32(rec0, 16 + 0x54)
                    val fullNameLen = readInt32(rec0, 16 + 0x58)
                    if (fullNameOffset in 0 until rec0.size && fullNameLen > 0 && fullNameOffset + fullNameLen <= rec0.size) {
                        val headerTitle = readString(rec0, fullNameOffset, fullNameLen, charset).trim { it <= ' ' || it == '\u0000' }
                        if (headerTitle.isNotEmpty()) {
                            title = headerTitle
                        }
                    }
                }

                // First image record index (MOBI+0x6C)
                if (rec0.size >= 16 + 0x70 && mobiHeaderLen >= 0x70) {
                    firstImageRecord = readInt32(rec0, 16 + 0x6C)
                }

                // Extra Data Flags (MOBI+0xF2)
                if (rec0.size >= 16 + 0xF4 && mobiHeaderLen >= 0xF4) {
                    extraDataFlags = readUInt16(rec0, 16 + 0xF2)
                }

                // EXTH Header (starts at 16 + mobiHeaderLen)
                val exthStart = 16 + mobiHeaderLen
                if (rec0.size >= exthStart + 12) {
                    val exthMagic = readString(rec0, exthStart, 4, Charsets.US_ASCII)
                    if (exthMagic == "EXTH") {
                        val exthCount = readInt32(rec0, exthStart + 8)
                        var exthPos = exthStart + 12
                        for (c in 0 until exthCount) {
                            if (exthPos + 8 > rec0.size) break
                            val recType = readInt32(rec0, exthPos)
                            val recLen = readInt32(rec0, exthPos + 4)
                            if (recLen < 8 || exthPos + recLen > rec0.size) break

                            if (recType == 100 && recLen > 8) { // Author
                                val authorStr = readString(rec0, exthPos + 8, recLen - 8, charset).trim { it <= ' ' || it == '\u0000' }
                                if (authorStr.isNotEmpty()) {
                                    author = authorStr
                                }
                            }
                            exthPos += recLen
                        }
                    }
                }
            }
        }

        // 3. Build Image Cache
        val imageCache = buildImageCache(data, recordOffsets, firstImageRecord)

        // 4. Read & Decompress Text Records
        val fullText = StringBuilder()
        for (i in 1..recordCount) {
            if (i >= numRecords) break
            val recordData = getRecord(i)
            if (recordData.isEmpty()) continue

            val trailingBytes = computeTrailingBytes(recordData, extraDataFlags)
            val usableLen = (recordData.size - trailingBytes).coerceAtLeast(0)
            val payload = recordData.copyOf(usableLen)

            val decompressed = when (compression) {
                1 -> payload // No compression
                2 -> decompressPalmDoc(payload)
                17480 -> throw UnsupportedOperationException("HUFF/CDIC compressed MOBI files (type 17480) are not supported. Please use PalmDOC or uncompressed format.")
                else -> payload
            }
            fullText.append(String(decompressed, charset))
        }

        // 5. Build Chapters & Hierarchical TOC
        // Legacy Mobipocket "filepos" links are byte offsets into this raw, concatenated,
        // pre-cleanup text stream, so chapters must be sliced out of it (and internal links
        // resolved against it) before per-chapter HTML cleanup runs.
        val fileposResolvable = charset != Charsets.UTF_8
        val (chapters, toc) = buildChaptersAndToc(fullText.toString(), imageCache, fileposResolvable)
        return BookModel(title, author, chapters, toc)
    }

    // ────────────────────────────────────────────────────────────
    //  Image Cache & Resolution
    // ────────────────────────────────────────────────────────────

    private fun buildImageCache(data: ByteArray, recordOffsets: IntArray, firstImageRecord: Int): Map<Int, String> {
        val cache = mutableMapOf<Int, String>()
        val numRecords = recordOffsets.size
        if (numRecords <= 1) return cache

        val startScan = if (firstImageRecord in 1 until numRecords) firstImageRecord else 1
        var imageIndex1 = 1 // 1-based indexing
        var imageIndex0 = 0 // 0-based indexing

        for (recIdx in startScan until numRecords) {
            val start = recordOffsets[recIdx]
            val end = if (recIdx + 1 < numRecords) recordOffsets[recIdx + 1] else data.size
            if (start < 0 || start >= data.size || end <= start) continue
            val len = minOf(end - start, data.size - start)
            if (len > 15_000_000) continue

            try {
                val recordData = data.copyOfRange(start, start + len)
                val mime = detectImageMime(recordData)
                if (mime != null) {
                    val b64 = Base64.getEncoder().encodeToString(recordData)
                    val dataUri = "data:$mime;base64,$b64"
                    cache[imageIndex1] = dataUri
                    cache[imageIndex0] = dataUri
                    cache[recIdx] = dataUri
                    if (firstImageRecord > 0) {
                        cache[recIdx - firstImageRecord] = dataUri
                        cache[recIdx - firstImageRecord + 1] = dataUri
                    }
                    imageIndex1++
                    imageIndex0++
                }
            } catch (_: Exception) {
                // Ignore corrupt image records
            }
        }
        return cache
    }

    private fun detectImageMime(data: ByteArray): String? {
        if (data.size < 4) return null
        return when {
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "image/jpeg"
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png"
            data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() -> "image/gif"
            data[0] == 0x42.toByte() && data[1] == 0x4D.toByte() -> "image/bmp"
            data.size >= 12 &&
                data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
                data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() -> "image/webp"
            else -> null
        }
    }

    // ────────────────────────────────────────────────────────────
    //  HTML Processing & Sanitization
    // ────────────────────────────────────────────────────────────

    private fun processMobiHtml(html: String, imageCache: Map<Int, String>): String {
        var result = html

        // Remove <script>, <style>, <link>
        result = result.replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        result = result.replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        result = result.replace(Regex("""<link[^>]*rel=["']?stylesheet["']?[^>]*>""", RegexOption.IGNORE_CASE), "")

        // Strip MOBI-proprietary tags except <mbp:pagebreak>
        result = result.replace(Regex("""</?mbp:(?!pagebreak)[^>]*>""", RegexOption.IGNORE_CASE), "")

        // Strip font-family declarations from inline styles to allow IDE reader fonts to render cleanly
        result = result.replace(Regex("""(style\s*=\s*["'])([^"']*)["']""", RegexOption.IGNORE_CASE)) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val styleContent = matchResult.groupValues[2]
            val cleaned = styleContent
                .replace(Regex("""font-family\s*:[^;]*(;|$)""", RegexOption.IGNORE_CASE), "")
                .trim().trimEnd(';')
            if (cleaned.isBlank()) "" else """${prefix}${cleaned}""""
        }

        // Resolve embedded images
        if (imageCache.isNotEmpty()) {
            result = result.replace(Regex("""<(?:img|image)\s+[^>]*>""", RegexOption.IGNORE_CASE)) { matchResult ->
                val tag = matchResult.value
                val imageIndex = extractImageIndex(tag)
                if (imageIndex != null && imageCache.containsKey(imageIndex)) {
                    val dataUri = imageCache[imageIndex]!!
                    var modifiedTag = tag
                    var replaced = false
                    if (Regex("""xlink:href\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE).containsMatchIn(modifiedTag)) {
                        modifiedTag = Regex("""xlink:href\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE).replace(modifiedTag, """xlink:href="$dataUri"""")
                        replaced = true
                    }
                    if (Regex("""(?<!xlink:)href\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE).containsMatchIn(modifiedTag)) {
                        modifiedTag = Regex("""(?<!xlink:)href\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE).replace(modifiedTag, """href="$dataUri"""")
                        replaced = true
                    }
                    if (Regex("""src\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE).containsMatchIn(modifiedTag)) {
                        modifiedTag = Regex("""src\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE).replace(modifiedTag, """src="$dataUri"""")
                        replaced = true
                    }
                    if (!replaced) {
                        modifiedTag = modifiedTag.replace(Regex("""/?>$"""), """ src="$dataUri" />""")
                    }
                    modifiedTag
                } else {
                    tag
                }
            }
        }

        // Collapse excessive blank lines
        result = result.replace(Regex("""\n\s*\n\s*\n"""), "\n\n")
        return result
    }

    private fun extractImageIndex(imgTag: String): Int? {
        // 1. Standard recindex="N" (MOBI/KF7)
        val recindexMatch = Regex("""recindex\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(imgTag)
        if (recindexMatch != null) {
            return recindexMatch.groupValues[1].toIntOrNull()
        }

        // 2. kindle:embed:XXXX (KF8/AZW3) or kindle:embed:0001
        val kindleMatch = Regex("""(?:src|href|xlink:href)\s*=\s*["']?kindle:embed:([0-9A-Za-z]+)["']?""", RegexOption.IGNORE_CASE).find(imgTag)
        if (kindleMatch != null) {
            val encoded = kindleMatch.groupValues[1]
            return try {
                encoded.toInt(32)
            } catch (_: NumberFormatException) {
                encoded.toIntOrNull()
            }
        }

        // 3. filepos="N" fallback
        val fileposMatch = Regex("""filepos\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(imgTag)
        if (fileposMatch != null) {
            return fileposMatch.groupValues[1].toIntOrNull()
        }

        // 4. Any sequential number in src, href, id, or name (e.g. src="images/00001.jpg", id="img_1")
        val numMatch = Regex("""(?:src|href|xlink:href|id|name)\s*=\s*["'][^"']*?(?:image|img|pic|_|recindex:)*0*(\d+)(?:\.[a-z]+)?["']""", RegexOption.IGNORE_CASE).find(imgTag)
        if (numMatch != null) {
            val idx = numMatch.groupValues[1].toIntOrNull()
            if (idx != null) return idx
        }

        // 5. General fallback: extract the first integer found inside src or href attribute
        val srcFallback = Regex("""(?:src|href|xlink:href)\s*=\s*["'][^"']*?(\d+)[^"']*["']""", RegexOption.IGNORE_CASE).find(imgTag)
        if (srcFallback != null) {
            return srcFallback.groupValues[1].toIntOrNull()
        }

        return null
    }

    // ────────────────────────────────────────────────────────────
    //  PalmDOC Decompression & Trailing Bytes
    // ────────────────────────────────────────────────────────────

    private fun computeTrailingBytes(recordData: ByteArray, extraDataFlags: Int): Int {
        if (recordData.isEmpty() || extraDataFlags == 0) return 0
        var trailing = 0

        // Bit 0: multibyte overlap
        if ((extraDataFlags and 1) != 0) {
            if (recordData.size > trailing) {
                val lastByte = recordData[recordData.size - 1 - trailing].toInt() and 0xFF
                val overlap = (lastByte and 0x03) + 1
                if (recordData.size >= trailing + overlap) {
                    trailing += overlap
                }
            }
        }

        // Bits 1..15: trailing data blocks
        var flags = extraDataFlags shr 1
        while (flags > 0) {
            if ((flags and 1) != 0) {
                if (recordData.size > trailing) {
                    val entrySize = getSizeOfTrailingEntry(recordData, recordData.size - trailing)
                    if (recordData.size >= trailing + entrySize) {
                        trailing += entrySize
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            flags = flags shr 1
        }

        return minOf(trailing, recordData.size)
    }

    private fun getSizeOfTrailingEntry(data: ByteArray, end: Int): Int {
        if (end <= 0) return 0
        var pos = end - 1
        var result = 0
        var bitPos = 0
        for (i in 0 until 4) {
            if (pos < 0) break
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F) shl bitPos)
            bitPos += 7
            pos--
            if ((b and 0x80) == 0) break
        }
        return result
    }

    private fun decompressPalmDoc(compressed: ByteArray): ByteArray {
        val out = ArrayList<Byte>(compressed.size * 2)
        var i = 0
        while (i < compressed.size) {
            val b = compressed[i++].toInt() and 0xFF
            when {
                b == 0x00 -> out.add(0)
                b in 0x01..0x08 -> {
                    for (j in 0 until b) {
                        if (i < compressed.size) out.add(compressed[i++])
                    }
                }
                b in 0x09..0x7F -> out.add(b.toByte())
                b in 0x80..0xBF -> {
                    if (i < compressed.size) {
                        val b2 = compressed[i++].toInt() and 0xFF
                        val combined = ((b and 0x3F) shl 8) or b2
                        val distance = combined shr 3
                        val length = (combined and 0x07) + 3
                        if (distance > 0) {
                            for (j in 0 until length) {
                                val idx = out.size - distance
                                if (idx >= 0 && idx < out.size) {
                                    out.add(out[idx])
                                } else {
                                    out.add(' '.code.toByte())
                                }
                            }
                        }
                    }
                }
                else -> {
                    out.add(' '.code.toByte())
                    out.add((b xor 0x80).toByte())
                }
            }
        }
        return ByteArray(out.size) { out[it] }
    }

    // ────────────────────────────────────────────────────────────
    //  Book & Chapter Building
    // ────────────────────────────────────────────────────────────

    /** A slice of the raw, pre-cleanup text stream, tagged with its absolute character range. */
    private data class RawSegment(val start: Int, val end: Int, val text: String)

    private fun buildChaptersAndToc(
        rawHtml: String,
        imageCache: Map<Int, String>,
        fileposResolvable: Boolean
    ): Pair<List<Chapter>, List<TocNode>> {
        // Chapters are sliced out of the RAW text (before processMobiHtml cleanup) because
        // legacy Mobipocket "filepos" links are byte/char offsets into that raw stream —
        // resolving them after cleanup (which strips tags and inlines large image data URIs)
        // would make those offsets meaningless.
        val breakMatches = Regex("""<mbp:pagebreak[^>]*>|<hr[^>]*class=["'][^"']*pagebreak[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(rawHtml).toList()
        val hasPagebreaks = breakMatches.isNotEmpty()

        val rawSegments = mutableListOf<RawSegment>()
        if (hasPagebreaks) {
            var cursor = 0
            for (m in breakMatches) {
                rawSegments.add(RawSegment(cursor, m.range.first, rawHtml.substring(cursor, m.range.first)))
                cursor = m.range.last + 1
            }
            rawSegments.add(RawSegment(cursor, rawHtml.length, rawHtml.substring(cursor)))
        } else {
            rawSegments.add(RawSegment(0, rawHtml.length, rawHtml))
        }

        // Drop blank pagebreak-delimited segments so segment order stays 1:1 with the final
        // chapter list (the internal-link offset lookup below depends on that alignment).
        val segments = if (hasPagebreaks) {
            rawSegments.filter { seg ->
                val textOnly = seg.text.replace(Regex("""<[^>]*>"""), "").trim()
                textOnly.isNotEmpty() || seg.text.contains("<img", ignoreCase = true) || seg.text.contains("<svg", ignoreCase = true)
            }
        } else {
            splitLargeSegmentBySize(rawSegments[0])
        }

        // Map every id/name anchor in the whole raw stream to its raw offset, so cross-chapter
        // fragment links (footnotes, in-text "Contents" pages) can be resolved to a page index.
        val idOffsets = HashMap<String, Int>()
        Regex("""\b(?:id|name)\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE).findAll(rawHtml).forEach { m ->
            idOffsets.putIfAbsent(m.groupValues[1], m.range.first)
        }

        fun chapterIndexForOffset(offset: Int): Int {
            for ((idx, seg) in segments.withIndex()) {
                if (offset < seg.end) return idx
            }
            return (segments.size - 1).coerceAtLeast(0)
        }

        val chapters = mutableListOf<Chapter>()
        for (seg in segments) {
            val linked = resolveInternalLinks(seg.text, chapters.size, fileposResolvable, idOffsets, ::chapterIndexForOffset)
            val body = processMobiHtml(linked, imageCache)

            val titleMatch = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", RegexOption.IGNORE_CASE).find(body)
            var rawTitle = titleMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]*>"""), "")?.trim()
            if (rawTitle.isNullOrBlank()) {
                rawTitle = when {
                    hasPagebreaks -> "Chapter ${chapters.size + 1}"
                    segments.size > 1 -> "Part ${chapters.size + 1}"
                    else -> "Full Text"
                }
            }
            chapters.add(Chapter(rawTitle, body, isHtml = true, stripExistingStyles = true))
        }

        val toc = buildTocFromHeadings(chapters)
        return Pair(chapters, toc)
    }

    /** Splits an oversized, pagebreak-free segment by size around clean block boundaries. */
    private fun splitLargeSegmentBySize(segment: RawSegment): List<RawSegment> {
        val html = segment.text
        val maxLength = 25_000
        if (html.length <= maxLength) return listOf(segment)

        val result = mutableListOf<RawSegment>()
        var start = 0
        while (start < html.length) {
            val targetEnd = minOf(start + maxLength, html.length)
            var splitEnd = targetEnd
            if (targetEnd < html.length) {
                // Try to find a clean closing block tag or line break
                val window = html.substring(maxOf(start, targetEnd - 2000), minOf(html.length, targetEnd + 2000))
                val boundaryIndices = listOf(
                    window.lastIndexOf("</div>", ignoreCase = true),
                    window.lastIndexOf("</p>", ignoreCase = true),
                    window.lastIndexOf("<h1", ignoreCase = true),
                    window.lastIndexOf("<h2", ignoreCase = true),
                    window.lastIndexOf("\n\n")
                ).filter { it != -1 }.maxOrNull()

                if (boundaryIndices != null) {
                    val absoluteBoundary = maxOf(start, targetEnd - 2000) + boundaryIndices
                    if (absoluteBoundary > start) {
                        splitEnd = if (html.substring(absoluteBoundary).startsWith("</", ignoreCase = true)) {
                            val tagEnd = html.indexOf('>', absoluteBoundary)
                            if (tagEnd != -1 && tagEnd < start + maxLength + 3000) tagEnd + 1 else absoluteBoundary
                        } else {
                            absoluteBoundary
                        }
                    }
                }
            }

            result.add(RawSegment(segment.start + start, segment.start + splitEnd, html.substring(start, splitEnd)))
            start = splitEnd
        }
        return result
    }

    /**
     * Rewrites in-text links so they're actually navigable within the app: legacy Mobipocket
     * `filepos`-offset links (which have no `href` at all, so nothing happens on click) and
     * cross-chapter `#id` fragment links (which target an element that isn't in the DOM of
     * whichever single chapter is currently rendered) both get pointed at [INTERNAL_LINK_SCHEME]
     * instead, which the editor's CEF request handler intercepts and turns into a page jump.
     */
    private fun resolveInternalLinks(
        text: String,
        currentChapterIndex: Int,
        fileposResolvable: Boolean,
        idOffsets: Map<String, Int>,
        chapterIndexForOffset: (Int) -> Int
    ): String {
        var result = text

        if (fileposResolvable) {
            // <a filepos="0000123456">...</a>
            result = Regex("""(<a\s[^>]*?)\bfilepos\s*=\s*["']?0*(\d+)["']?([^>]*>)""", RegexOption.IGNORE_CASE)
                .replace(result) { m ->
                    val target = m.groupValues[2].toIntOrNull()
                    if (target != null) {
                        "${m.groupValues[1]}href=\"$INTERNAL_LINK_SCHEME${chapterIndexForOffset(target)}\"${m.groupValues[3]}"
                    } else m.value
                }
            // <a href="#filepos0000123456">...</a>
            result = Regex("""(<a\s[^>]*?href\s*=\s*["'])#filepos0*(\d+)(["'][^>]*>)""", RegexOption.IGNORE_CASE)
                .replace(result) { m ->
                    val target = m.groupValues[2].toIntOrNull()
                    if (target != null) {
                        "${m.groupValues[1]}$INTERNAL_LINK_SCHEME${chapterIndexForOffset(target)}${m.groupValues[3]}"
                    } else m.value
                }
        }

        // <a href="#someId">...</a> — same-chapter targets are left alone since the browser
        // resolves those natively; cross-chapter ones need an actual page jump.
        result = Regex("""(<a\s[^>]*?href\s*=\s*["'])#([^"'#]+)(["'][^>]*>)""", RegexOption.IGNORE_CASE).replace(result) { m ->
            val anchorId = m.groupValues[2]
            val targetOffset = idOffsets[anchorId]
            val targetChapter = targetOffset?.let(chapterIndexForOffset)
            if (targetChapter != null && targetChapter != currentChapterIndex) {
                "${m.groupValues[1]}$INTERNAL_LINK_SCHEME$targetChapter#$anchorId${m.groupValues[3]}"
            } else m.value
        }

        return result
    }

    private fun buildTocFromHeadings(chapters: List<Chapter>): List<TocNode> {
        class BuilderNode(val title: String, val chapterIndex: Int, val level: Int) {
            val children = mutableListOf<BuilderNode>()
            fun toTocNode(): TocNode = TocNode(title, chapterIndex, children.map { it.toTocNode() })
        }

        val rootNodes = mutableListOf<BuilderNode>()
        val stack = mutableListOf<BuilderNode>()

        for ((index, chapter) in chapters.withIndex()) {
            // Each chapter maps to exactly one navigable page, so only the first heading
            // (the chapter/section's own title) becomes a TOC entry. Additional headings
            // further down in the same chapter (subheadings, epigraphs, decorative markup)
            // would produce entries that point at the same page and appear to "do nothing"
            // when clicked, since there's no in-page anchor navigation.
            val firstHeading = Regex("""<h([1-6])[^>]*>(.*?)</h[1-6]>""", RegexOption.IGNORE_CASE).find(chapter.body)

            val level: Int
            val title: String
            if (firstHeading == null) {
                level = 1
                title = chapter.title
            } else {
                level = firstHeading.groupValues[1].toIntOrNull() ?: 1
                val rawTitle = firstHeading.groupValues[2].replace(Regex("""<[^>]*>"""), "").trim()
                title = if (rawTitle.isEmpty()) chapter.title else rawTitle
            }

            val node = BuilderNode(title, index, level)
            while (stack.isNotEmpty() && stack.last().level >= level) {
                stack.removeLast()
            }
            if (stack.isEmpty()) {
                rootNodes.add(node)
            } else {
                stack.last().children.add(node)
            }
            stack.add(node)
        }

        return rootNodes.map { it.toTocNode() }
    }


    // ────────────────────────────────────────────────────────────
    //  Binary Reader Helpers
    // ────────────────────────────────────────────────────────────

    private fun readUInt8(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset >= data.size) return 0
        return data[offset].toInt() and 0xFF
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= data.size) return 0L
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun readInt32(data: ByteArray, offset: Int): Int = readUInt32(data, offset).toInt()

    private fun readString(data: ByteArray, offset: Int, length: Int, charset: Charset): String {
        if (offset < 0 || offset >= data.size || length <= 0) return ""
        val actualLen = minOf(length, data.size - offset)
        return String(data, offset, actualLen, charset)
    }

    private fun readNullTerminatedString(data: ByteArray, offset: Int, maxLength: Int, charset: Charset): String {
        if (offset < 0 || offset >= data.size || maxLength <= 0) return ""
        val actualMax = minOf(maxLength, data.size - offset)
        var end = offset
        while (end < offset + actualMax && data[end] != 0.toByte()) {
            end++
        }
        return String(data, offset, end - offset, charset).trim()
    }
}
