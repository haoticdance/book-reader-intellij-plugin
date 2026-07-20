package com.github.haoticdance.bookreaderintellijplugin.parsers

import com.github.haoticdance.bookreaderintellijplugin.models.INTERNAL_LINK_SCHEME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class MobiParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Builds a minimal uncompressed, single-text-record MOBI file (CP1251, filepos-friendly) and parses it. */
    private fun buildAndParse(htmlBody: String, title: String = "Minimal Test Book"): com.github.haoticdance.bookreaderintellijplugin.models.BookModel {
        val htmlContent = htmlBody.toByteArray(Charsets.ISO_8859_1)

        val mobiHeaderLen = 0xE8
        val rec0Len = 16 + mobiHeaderLen
        val pdbHeaderLen = 78
        val numRecords = 2
        val recDirLen = numRecords * 8

        val rec0Offset = pdbHeaderLen + recDirLen
        val rec1Offset = rec0Offset + rec0Len
        val totalSize = rec1Offset + htmlContent.size

        val buffer = ByteBuffer.allocate(totalSize)

        // 1. PDB Header (78 bytes)
        val titleBytes = title.toByteArray(Charsets.ISO_8859_1)
        buffer.put(titleBytes)
        buffer.position(76)
        buffer.putShort(numRecords.toShort())

        // 2. Record Directory (16 bytes)
        buffer.putInt(rec0Offset)
        buffer.put(0.toByte())
        buffer.put(0.toByte()); buffer.put(0.toByte()); buffer.put(0.toByte())
        buffer.putInt(rec1Offset)
        buffer.put(0.toByte())
        buffer.put(0.toByte()); buffer.put(0.toByte()); buffer.put(1.toByte())

        // 3. Record 0 (PalmDOC + MOBI Header)
        buffer.position(rec0Offset)
        buffer.putShort(1.toShort()) // compression: 1 = uncompressed
        buffer.putShort(0.toShort())
        buffer.putInt(htmlContent.size)
        buffer.putShort(1.toShort()) // recordCount
        buffer.putShort(4096.toShort())
        buffer.putShort(0.toShort()) // encryption
        buffer.putShort(0.toShort())

        buffer.put("MOBI".toByteArray(Charsets.US_ASCII))
        buffer.putInt(mobiHeaderLen)
        buffer.putInt(2) // mobiType = book
        buffer.putInt(1251) // textEncoding = CP1251 (single-byte, filepos-resolvable)

        // 4. Record 1 (HTML Content)
        buffer.position(rec1Offset)
        buffer.put(htmlContent)

        val mobiFile = tempFolder.newFile("test_book_${System.identityHashCode(htmlBody)}.mobi")
        mobiFile.writeBytes(buffer.array())

        return MobiParser().parse(mobiFile)
    }

    @Test
    fun testParseMinimalUncompressedMobi() {
        val html = """
            <html>
            <body>
                <mbp:pagebreak />
                <h1>First Chapter</h1>
                <p>Hello, MOBI Reader!</p>
                <mbp:pagebreak />
                <h1>Second Chapter</h1>
                <p>Welcome to the second chapter.</p>
            </body>
            </html>
        """.trimIndent()

        val bookModel = buildAndParse(html)

        assertEquals("Minimal Test Book", bookModel.title)
        assertEquals(2, bookModel.chapters.size)
        assertEquals("First Chapter", bookModel.chapters[0].title)
        assertTrue(bookModel.chapters[0].body.contains("Hello, MOBI Reader!"))
        assertEquals("Second Chapter", bookModel.chapters[1].title)
        assertTrue(bookModel.chapters[1].body.contains("Welcome to the second chapter."))

        val toc = bookModel.toc
        assertTrue(toc != null && toc.size == 2)
        assertEquals("First Chapter", toc!![0].title)
        assertEquals(0, toc[0].chapterIndex)
        assertEquals("Second Chapter", toc[1].title)
        assertEquals(1, toc[1].chapterIndex)
    }

    @Test
    fun testFileposLinkResolvesToInternalPageLink() {
        // Legacy Mobipocket-style link: <a filepos="N"> with no href at all — must become clickable.
        fun buildPrefix(fileposValue: Int) =
            "<html><body><mbp:pagebreak /><h1>First Chapter</h1>" +
                "<p>See the <a filepos=\"$fileposValue\">next chapter</a> for more.</p><mbp:pagebreak />"
        val target = "<h1>Second Chapter</h1><p>Welcome to the second chapter.</p>"
        val suffix = "</body></html>"

        // filepos is an offset into the raw decompressed stream and, since the target immediately
        // follows the prefix here, must equal the prefix's own length — including its digits' length.
        // Solve that small fixed point instead of guessing, so the test doesn't depend on digit count.
        var filepos = buildPrefix(0).length
        repeat(4) { filepos = buildPrefix(filepos).length }
        val html = buildPrefix(filepos) + target + suffix

        val bookModel = buildAndParse(html)

        assertEquals(2, bookModel.chapters.size)
        val firstChapterBody = bookModel.chapters[0].body
        assertTrue(
            "Expected filepos link rewritten to ${INTERNAL_LINK_SCHEME}1, got: $firstChapterBody",
            firstChapterBody.contains("href=\"${INTERNAL_LINK_SCHEME}1\"")
        )
    }

    @Test
    fun testCrossChapterFragmentLinkResolvesToInternalPageLink() {
        val html = """
            <html>
            <body>
                <mbp:pagebreak />
                <h1>First Chapter</h1>
                <p>See the <a href="#note1">footnote</a>.</p>
                <mbp:pagebreak />
                <h1>Second Chapter</h1>
                <p id="note1">This is the footnote text.</p>
            </body>
            </html>
        """.trimIndent()

        val bookModel = buildAndParse(html)

        assertEquals(2, bookModel.chapters.size)
        val firstChapterBody = bookModel.chapters[0].body
        assertTrue(
            "Expected #note1 rewritten to point at page 1, got: $firstChapterBody",
            firstChapterBody.contains("href=\"${INTERNAL_LINK_SCHEME}1#note1\"")
        )
    }

    @Test
    fun testSameChapterFragmentLinkIsLeftNative() {
        val html = """
            <html>
            <body>
                <mbp:pagebreak />
                <h1>Only Chapter</h1>
                <p>See the <a href="#note1">footnote</a>.</p>
                <p id="note1">This is the footnote text.</p>
            </body>
            </html>
        """.trimIndent()

        val bookModel = buildAndParse(html)

        assertEquals(1, bookModel.chapters.size)
        assertTrue(bookModel.chapters[0].body.contains("href=\"#note1\""))
    }
}
