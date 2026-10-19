package com.roychong.audiobook

import com.roychong.audiobook.parse.BookParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookParserTest {

    @Test
    fun splitsChineseSentences() {
        val text = "你好。世界！再见"
        val ranges = BookParser.splitSentenceRanges(text)
        assertEquals(3, ranges.size)
        assertEquals("你好。", text.substring(ranges[0].first, ranges[0].second))
        assertEquals("世界！", text.substring(ranges[1].first, ranges[1].second))
        assertEquals("再见", text.substring(ranges[2].first, ranges[2].second))
    }

    @Test
    fun parsesTxtChaptersAndOffsets() {
        val f = File.createTempFile("book", ".txt")
        f.writeText("第一章 开始\n\n第一段。第二句。\n\n第二章 结束\n\n最后一段。")
        val parsed = BookParser.parse(f, "测试.txt", "text/plain")
        val book = parsed.toBook("id", "测试.txt", null, 0L)
        f.delete()
        assertEquals(2, book.chapters.size)
        assertEquals("第一章 开始", book.chapters[0].title)
        assertEquals(2, book.chapters[0].sentences.size)
        for (ch in book.chapters) {
            for (s in ch.sentences) {
                assertTrue(s.start >= 0)
                assertTrue(s.end <= ch.text.length)
                assertEquals(s.text, ch.text.substring(s.start, s.end))
            }
        }
    }

    @Test
    fun decodesGbkText() {
        val f = File.createTempFile("book", ".txt")
        f.writeBytes("第一章\n\n你好，世界。".toByteArray(charset("GB18030")))
        val parsed = BookParser.parse(f, "gbk.txt", "text/plain")
        f.delete()
        assertEquals(1, parsed.chapters.size)
        assertTrue(parsed.chapters[0].paragraphs.joinToString("").contains("你好，世界。"))
    }

    @Test
    fun parsesHtmlHeadings() {
        val f = File.createTempFile("book", ".html")
        f.writeText(
            "<html><head><title>测试网页</title></head><body>" +
                "<h1>第一章 开始</h1><p>第一段。</p><p>第二段。</p>" +
                "<h2>第二章 结束</h2><p>最后一段。</p>" +
                "</body></html>"
        )
        val parsed = BookParser.parse(f, "测试.html", "text/html")
        f.delete()
        assertEquals(2, parsed.chapters.size)
        assertEquals("测试网页", parsed.title)
        assertTrue(parsed.chapters[0].paragraphs.joinToString("").contains("第一段"))
    }

    @Test
    fun parsesMarkdownHeadings() {
        val f = File.createTempFile("book", ".md")
        f.writeText("# 第一章 起点\n\n这是**正文**。\n\n## 第二章 终点\n\n[链接](https://example.com) 结束。")
        val parsed = BookParser.parse(f, "测试.md", "text/markdown")
        val titles = parsed.chapters.map { it.title }
        f.delete()
        assertEquals(2, parsed.chapters.size)
        assertTrue(titles.any { it.contains("第一章") })
        assertTrue(parsed.chapters[0].paragraphs.joinToString("").contains("正文"))
    }
}
