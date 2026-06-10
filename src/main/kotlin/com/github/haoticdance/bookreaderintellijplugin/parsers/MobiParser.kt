package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.BookModel
import com.github.haoticdance.bookreaderintellijplugin.models.Chapter
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

class MobiParser {
    fun parse(file: File): BookModel {
        val raf = RandomAccessFile(file, "r")
        try {
            // 1. PDB Header — numRecords is at offset 76
            raf.seek(76)
            val numRecords = raf.readShort().toInt() and 0xFFFF

            // 2. Record Info List — each entry is 8 bytes (offset 4 + attributes+uid 4)
            val offsets = LongArray(numRecords)
            for (i in 0 until numRecords) {
                offsets[i] = raf.readInt().toLong() and 0xFFFFFFFFL
                raf.skipBytes(4)
            }

            // 3. Record 0: Palm DB header starts at offsets[0]
            raf.seek(offsets[0])
            val compression = raf.readShort().toInt() and 0xFFFF
            raf.skipBytes(2)                                    // spare
            raf.readInt()                                       // textLength (unused here)
            val recordCount = raf.readShort().toInt() and 0xFFFF
            raf.readShort()                                     // recordSize (unused)
            val encryption = raf.readShort().toInt() and 0xFFFF

            if (encryption != 0) {
                throw IllegalArgumentException("DRM-protected MOBI files are not supported")
            }

            // 4. MOBI header starts at offsets[0] + 16
            raf.seek(offsets[0] + 16)
            val identifier = ByteArray(4)
            raf.readFully(identifier)
            if (String(identifier) != "MOBI") {
                throw IllegalArgumentException("Invalid MOBI file: MOBI identifier not found")
            }

            val headerLength = raf.readInt()   // length of MOBI header (from "MOBI" identifier onward)
            raf.readInt()                       // mobiType
            val textEncoding = raf.readInt()
            val charset = when (textEncoding) {
                65001 -> Charsets.UTF_8
                1251 -> Charset.forName("CP1251")
                else -> Charset.forName("CP1252")
            }

            raf.skipBytes(36)                  // skip: uid, generatorVersion, reserved×5, firstNonBookIndex,
            //        fullNameOffset_hi (unused here), ...
            // fullNameOffset and fullNameLength are at MOBI header + 0x54 and 0x58
            // which is offsets[0] + 16 + 0x54 = offsets[0] + 100
            raf.seek(offsets[0] + 16 + 0x54)
            val fullNameOffset = raf.readInt()
            val fullNameLength = raf.readInt()

            // 5. Read title from record 0
            raf.seek(offsets[0] + fullNameOffset)
            val titleBytes = ByteArray(fullNameLength)
            raf.readFully(titleBytes)
            val title = String(titleBytes, charset).trim { it <= ' ' || it == '\u0000' }

            // 6. EXTH header: immediately after the MOBI header
            //    MOBI header starts at offsets[0]+16, its own length is headerLength
            var author = "Unknown Author"
            val exthStart = offsets[0] + 16 + headerLength
            raf.seek(exthStart)
            val exthId = ByteArray(4)
            raf.readFully(exthId)
            if (String(exthId) == "EXTH") {
                raf.readInt()                   // exthLength (total EXTH block size)
                val exthRecordCount = raf.readInt()
                repeat(exthRecordCount) {
                    val recordType = raf.readInt()
                    val recordLen = raf.readInt()
                    val dataLen = recordLen - 8  // recordLen includes the type+length fields
                    if (recordType == 100 && dataLen > 0) {
                        val authorBytes = ByteArray(dataLen)
                        raf.readFully(authorBytes)
                        author = String(authorBytes, charset).trim { it <= ' ' || it == '\u0000' }
                        return@repeat          // break out of repeat after finding author
                    } else {
                        raf.seek(raf.filePointer + dataLen)  // safe skip
                    }
                }
            }

            // 7. Read content records 1..recordCount (text records only)
            val fullText = StringBuilder()
            for (i in 1..recordCount) {
                if (i >= offsets.size) break
                val start = offsets[i]
                // End of this record = start of the next record (or EOF as last resort)
                val end = if (i + 1 < offsets.size) offsets[i + 1] else file.length()
                val len = (end - start).toInt()
                if (len <= 0) continue

                raf.seek(start)
                val recordData = ByteArray(len)
                raf.readFully(recordData)

                // Strip the trailing size byte used by PalmDOC records (last 1 or 2 bytes
                // encode how many bytes of the uncompressed output belong to this record vs
                // the overlap with the next). For PalmDOC (compression==2) each record ends
                // with a 1-byte "size overlap" value; strip it before decompressing.
                val payload = if (compression == 2 && recordData.isNotEmpty())
                    recordData.copyOf(recordData.size - 1)
                else
                    recordData

                val decompressed = when (compression) {
                    1 -> payload
                    2 -> decompressPalmDoc(payload)
                    else -> payload
                }
                fullText.append(String(decompressed, charset))
            }

            val chapters = splitIntoChapters(fullText.toString())
            return BookModel(title, author, chapters)
        } finally {
            raf.close()
        }
    }

    private fun decompressPalmDoc(compressed: ByteArray): ByteArray {
        val out = ArrayList<Byte>(compressed.size * 2)
        var i = 0
        while (i < compressed.size) {
            val b = compressed[i++].toInt() and 0xFF
            when {
                b == 0x00 -> out.add(0)
                b in 0x01..0x08 -> {
                    // Literal run: next b bytes are copied as-is
                    for (j in 0 until b) {
                        if (i < compressed.size) out.add(compressed[i++])
                    }
                }

                b in 0x09..0x7F -> out.add(b.toByte())
                b in 0x80..0xBF -> {
                    // Back-reference: 2-byte sequence encodes distance+length
                    if (i < compressed.size) {
                        val b2 = compressed[i++].toInt() and 0xFF
                        val combined = ((b and 0x3F) shl 8) or b2
                        val distance = combined shr 3
                        val length = (combined and 0x07) + 3
                        if (distance > 0) {
                            for (j in 0 until length) {
                                val idx = out.size - distance
                                if (idx >= 0) out.add(out[idx])
                            }
                        }
                    }
                }

                else -> {
                    // 0xC0..0xFF: space + decoded character
                    out.add(' '.code.toByte())
                    out.add((b xor 0x80).toByte())
                }
            }
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun splitIntoChapters(html: String): List<Chapter> {
        val parts = html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE))

        if (parts.size > 1) {
            return parts.mapIndexed { index, part ->
                Chapter("Chapter ${index + 1}", extractTextFromHtml(part))
            }.filter { it.body.isNotBlank() }
        }

        val maxLength = 20_000
        if (html.length > maxLength) {
            val chunks = mutableListOf<Chapter>()
            var start = 0
            var count = 1
            while (start < html.length) {
                val end = minOf(start + maxLength, html.length)
                chunks.add(Chapter("Part $count", extractTextFromHtml(html.substring(start, end))))
                start = end
                count++
            }
            return chunks
        }

        return listOf(Chapter("Full Text", extractTextFromHtml(html)))
    }

    private fun extractTextFromHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}