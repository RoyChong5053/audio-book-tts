package com.roychong.audiobook.book

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一句话在章节全文里的位置。 */
data class Sentence(
    val text: String,
    val start: Int,
    val end: Int,
)

data class Chapter(
    val title: String,
    val text: String,
    val sentences: List<Sentence>,
) {
    fun sentenceAt(offset: Int): Int {
        if (sentences.isEmpty()) return 0
        var lo = 0
        var hi = sentences.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sentences[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val sourceName: String,
    val coverPath: String?,
    val addedAt: Long,
    val chapters: List<Chapter>,
) {
    val totalChars: Int = chapters.sumOf { it.text.length }

    fun charsBefore(index: Int): Int {
        var n = 0
        for (i in 0 until index.coerceIn(0, chapters.size)) n += chapters[i].text.length
        return n
    }

    fun percent(chapter: Int, sentence: Int): Int {
        if (totalChars <= 0) return 0
        val c = chapters.getOrNull(chapter) ?: return 0
        val s = c.sentences.getOrNull(sentence) ?: return 0
        val atEnd = chapter == chapters.lastIndex && sentence >= c.sentences.lastIndex
        val chars = charsBefore(chapter) + if (atEnd) c.text.length else s.start
        return (chars * 100L / totalChars).toInt().coerceIn(0, 100)
    }

    fun sentence(chapter: Int, sentence: Int): Sentence? =
        chapters.getOrNull(chapter)?.sentences?.getOrNull(sentence)

    fun toJson(): JSONObject {
        val chaptersArr = JSONArray()
        chapters.forEach { ch ->
            val sents = JSONArray()
            ch.sentences.forEach { s ->
                sents.put(JSONArray().put(s.text).put(s.start).put(s.end))
            }
            chaptersArr.put(
                JSONObject()
                    .put("title", ch.title)
                    .put("text", ch.text)
                    .put("sentences", sents)
            )
        }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("author", author)
            .put("format", format)
            .put("sourceName", sourceName)
            .put("coverPath", coverPath ?: "")
            .put("addedAt", addedAt)
            .put("chapters", chaptersArr)
    }

    companion object {
        fun fromJson(o: JSONObject): Book {
            val chapters = ArrayList<Chapter>()
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val sents = ArrayList<Sentence>()
                val sa = c.optJSONArray("sentences") ?: JSONArray()
                for (j in 0 until sa.length()) {
                    val s = sa.optJSONArray(j) ?: continue
                    sents.add(
                        Sentence(
                            text = s.optString(0),
                            start = s.optInt(1, 0),
                            end = s.optInt(2, 0),
                        )
                    )
                }
                chapters.add(
                    Chapter(
                        title = c.optString("title", "正文"),
                        text = c.optString("text", ""),
                        sentences = sents,
                    )
                )
            }
            return Book(
                id = o.optString("id"),
                title = o.optString("title", "未命名"),
                author = o.optString("author", ""),
                format = o.optString("format", ""),
                sourceName = o.optString("sourceName", ""),
                coverPath = o.optString("coverPath").ifBlank { null },
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                chapters = chapters,
            )
        }

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

data class BookInfo(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val coverPath: String?,
    val chapterCount: Int,
    val totalChars: Int,
    val addedAt: Long,
)

data class Position(
    val chapter: Int = 0,
    val sentence: Int = 0,
    val fraction: Float = 0f,
    val updatedAt: Long = 0L,
    val percent: Int = 0,
)

data class Bookmark(
    val id: String,
    val bookId: String,
    val chapter: Int,
    val sentence: Int,
    val chapterTitle: String,
    val note: String,
    val createdAt: Long,
)
