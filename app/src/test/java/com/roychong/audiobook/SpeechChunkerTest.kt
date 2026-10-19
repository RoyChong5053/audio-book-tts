package com.roychong.audiobook

import com.roychong.audiobook.book.Book
import com.roychong.audiobook.book.Chapter
import com.roychong.audiobook.book.Sentence
import com.roychong.audiobook.book.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    private fun book(vararg chapters: String): Book {
        val chs = chapters.map { text ->
            val sentences = ArrayList<Sentence>()
            var start = 0
            for (part in text.split(Regex("(?<=[。！？])")).filter { it.isNotEmpty() }) {
                val s = text.indexOf(part, start)
                sentences.add(Sentence(part, s, s + part.length))
                start = s + part.length
            }
            Chapter("章", text, sentences)
        }
        return Book("id", "书名", "", "txt", "test.txt", null, 0L, chs)
    }

    @Test
    fun firstChunkIsSmallAndLaterChunksUseLimit() {
        val text = "第一句。".repeat(200)
        val b = book(text)
        val chunks = SpeechChunker.build(b, 300)
        assertTrue(chunks.size > 2)
        assertEquals(0, chunks[0].startChar)
        assertTrue(chunks[0].endChar - chunks[0].startChar <= 80)
        for (i in 1 until chunks.size) {
            assertTrue(chunks[i].endChar - chunks[i].startChar <= 300 + 100)
        }
        // 覆盖且不重叠
        for (i in 1 until chunks.size) assertEquals(chunks[i - 1].endChar, chunks[i].startChar)
        assertEquals(text.length, chunks.last().endChar)
    }

    @Test
    fun chunksDoNotCrossChapters() {
        val b = book("甲。".repeat(30), "乙。".repeat(30))
        val chunks = SpeechChunker.build(b, 80)
        assertTrue(chunks.all { it.chapterIndex == 0 || it.chapterIndex == 1 })
        assertTrue(chunks.any { it.chapterIndex == 0 })
        assertTrue(chunks.any { it.chapterIndex == 1 })
    }
}
