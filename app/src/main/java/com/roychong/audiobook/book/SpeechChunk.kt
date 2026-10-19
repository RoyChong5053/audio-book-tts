package com.roychong.audiobook.book

/**
 * 一次 TTS 请求的文字范围。
 *
 * 只存章节内的字符区间，不复制正文；几十万字的书也只占很少内存。
 * [indexInChapter] 从 0 开始，用于缓存/归档命名；全局播放序号由列表下标决定。
 */
data class SpeechChunk(
    val chapterIndex: Int,
    val indexInChapter: Int,
    val startChar: Int,
    val endChar: Int,
    val startSentence: Int,
    val endSentence: Int,
) {
    fun text(book: Book): String {
        val text = book.chapters.getOrNull(chapterIndex)?.text ?: return ""
        val s = startChar.coerceIn(0, text.length)
        val e = endChar.coerceIn(s, text.length)
        return text.substring(s, e)
    }
}

object SpeechChunker {

    /** 每章第一块的上限：先快速开口，后面再按用户设置的块长攒。 */
    const val FIRST_CHUNK_MAX = 80

    /**
     * 把整本书切成扁平的 chunk 列表，顺序即播放顺序。
     * chunk 不跨章；每章第一块压到 [FIRST_CHUNK_MAX] 字以内。
     */
    fun build(book: Book, maxChars: Int): List<SpeechChunk> {
        val limit = maxChars.coerceIn(40, 5000)
        val out = ArrayList<SpeechChunk>()
        book.chapters.forEachIndexed { ci, chapter ->
            val sentences = chapter.sentences
            if (sentences.isEmpty()) return@forEachIndexed
            var si = 0
            var index = 0
            while (si < sentences.size) {
                val first = sentences[si]
                val cap = if (index == 0) minOf(limit, FIRST_CHUNK_MAX) else limit
                var sj = si + 1
                var end = first.end
                while (sj < sentences.size) {
                    val candidate = sentences[sj].end - first.start
                    if (candidate > cap) break
                    end = sentences[sj].end
                    sj++
                }
                out.add(
                    SpeechChunk(
                        chapterIndex = ci,
                        indexInChapter = index,
                        startChar = first.start.coerceIn(0, chapter.text.length),
                        endChar = end.coerceIn(0, chapter.text.length),
                        startSentence = si,
                        endSentence = sj - 1,
                    )
                )
                si = sj
                index++
            }
        }
        return out
    }
}
