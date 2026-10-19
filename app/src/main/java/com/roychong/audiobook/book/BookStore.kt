package com.roychong.audiobook.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.roychong.audiobook.parse.BookParser
import com.roychong.audiobook.parse.ParsedBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 书库存储：
 *  - books/<id>.json     解析后的正文（章节 + 句子区间）
 *  - sources/<id>.<ext>  原始文件，便于以后重新解析
 *  - covers/<id>.<ext>   封面
 *  - books/index.json    轻量索引（不加载正文就能显示书架）
 */
class BookStore(private val context: Context) {

    private val root = File(context.filesDir, "audiobook").apply { mkdirs() }
    private val booksDir = File(root, "books").apply { mkdirs() }
    private val sourcesDir = File(root, "sources").apply { mkdirs() }
    private val coversDir = File(root, "covers").apply { mkdirs() }
    private val indexFile = File(booksDir, "index.json")
    private val progressPrefs = context.getSharedPreferences("audiobook_progress", Context.MODE_PRIVATE)
    private val bookmarkPrefs = context.getSharedPreferences("audiobook_bookmarks", Context.MODE_PRIVATE)

    private var cached: Book? = null
    private var cachedAt = 0L

    init {
        // PdfBox-Android 需要一次资源初始化（字体/ToUnicode 映射）
        runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    // ---------------- 索引 ----------------

    fun library(): List<BookInfo> {
        val raw = runCatching { indexFile.readText() }.getOrNull()
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<BookInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                BookInfo(
                    id = o.optString("id"),
                    title = o.optString("title", "未命名"),
                    author = o.optString("author", ""),
                    format = o.optString("format", ""),
                    coverPath = o.optString("cover").ifBlank { null },
                    chapterCount = o.optInt("chapters", 0),
                    totalChars = o.optInt("chars", 0),
                    addedAt = o.optLong("addedAt", 0L),
                )
            )
        }
        return out.sortedByDescending { progress(it.id).updatedAt.takeIf { t -> t > 0 } ?: it.addedAt }
    }

    private fun writeIndex(list: List<BookInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("author", it.author)
                    .put("format", it.format)
                    .put("cover", it.coverPath ?: "")
                    .put("chapters", it.chapterCount)
                    .put("chars", it.totalChars)
                    .put("addedAt", it.addedAt)
            )
        }
        runCatching { indexFile.writeText(arr.toString()) }
    }

    // ---------------- 导入 ----------------

    data class ImportResult(val book: Book)

    /** 同步执行，调用方放后台线程。 */
    fun import(uri: Uri): ImportResult {
        val (name, _) = displayName(uri)
        val id = Book.newId()
        val ext = name.substringAfterLast('.', "").lowercase().ifBlank { "bin" }
        val source = File(sourcesDir, "$id.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            source.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取文件")
        if (source.length() == 0L) {
            source.delete()
            throw IllegalStateException("文件为空")
        }
        val mime = context.contentResolver.getType(uri) ?: ""
        val parsed = BookParser.parse(source, name, mime)
        if (parsed.chapters.isEmpty() || parsed.chapters.all { it.paragraphs.isEmpty() }) {
            source.delete()
            throw IllegalStateException("没有解析出文字（可能是扫描版 PDF）")
        }
        val cover = parsed.coverBytes?.let { bytes ->
            val f = File(coversDir, "$id.img")
            runCatching { f.writeBytes(bytes) }
            if (f.exists() && f.length() > 0) f.absolutePath else null
        }
        val book = parsed.toBook(
            id = id,
            sourceName = name,
            coverPath = cover,
            addedAt = System.currentTimeMillis(),
        )
        save(book)
        cached = book
        cachedAt = System.currentTimeMillis()
        return ImportResult(book)
    }

    private fun displayName(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "book"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    // ---------------- 读写正文 ----------------

    fun save(book: Book) {
        val json = book.toJson().toString()
        val tmp = File(booksDir, "${book.id}.tmp")
        tmp.writeText(json)
        val dest = File(booksDir, "${book.id}.json")
        if (!tmp.renameTo(dest)) {
            dest.writeText(json)
            tmp.delete()
        }
        val infos = library().filterNot { it.id == book.id }.toMutableList()
        infos.add(0, book.toInfo())
        writeIndex(infos)
        if (cached?.id == book.id) cached = book
    }

    private fun Book.toInfo() = BookInfo(
        id = id, title = title, author = author, format = format,
        coverPath = coverPath, chapterCount = chapters.size,
        totalChars = totalChars, addedAt = addedAt,
    )

    fun load(id: String): Book? {
        val now = System.currentTimeMillis()
        cached?.let { if (it.id == id && now - cachedAt < 60_000) return it }
        val f = File(booksDir, "$id.json")
        if (!f.exists()) return null
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val book = Book.fromJson(o)
        cached = book
        cachedAt = now
        return book
    }

    fun delete(id: String) {
        File(booksDir, "$id.json").delete()
        sourcesDir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        coversDir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        writeIndex(library().filterNot { it.id == id })
        progressPrefs.edit().remove("p_$id").apply()
        saveBookmarks(bookmarks().filterNot { it.bookId == id })
        if (cached?.id == id) cached = null
    }

    // ---------------- 进度 ----------------

    fun progress(bookId: String): Position {
        val raw = progressPrefs.getString("p_$bookId", null) ?: return Position()
        return runCatching {
            val o = JSONObject(raw)
            Position(
                chapter = o.optInt("c", 0),
                sentence = o.optInt("s", 0),
                fraction = o.optDouble("f", 0.0).toFloat(),
                updatedAt = o.optLong("t", 0L),
                percent = o.optInt("pc", 0),
                charOffset = o.optInt("co", -1),
            )
        }.getOrDefault(Position())
    }

    fun saveProgress(bookId: String, pos: Position) {
        progressPrefs.edit().putString(
            "p_$bookId",
            JSONObject()
                .put("c", pos.chapter)
                .put("s", pos.sentence)
                .put("f", pos.fraction.toDouble())
                .put("pc", pos.percent)
                .put("co", pos.charOffset)
                .put("t", System.currentTimeMillis())
                .toString()
        ).apply()
    }

    // ---------------- 书签 ----------------

    fun bookmarks(bookId: String? = null): List<Bookmark> {
        val raw = bookmarkPrefs.getString("list", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Bookmark>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (bookId != null && o.optString("book") != bookId) continue
            out.add(
                Bookmark(
                    id = o.optString("id"),
                    bookId = o.optString("book"),
                    chapter = o.optInt("c", 0),
                    sentence = o.optInt("s", 0),
                    chapterTitle = o.optString("title", ""),
                    note = o.optString("note", ""),
                    createdAt = o.optLong("t", 0L),
                )
            )
        }
        return out.sortedByDescending { it.createdAt }
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("book", it.bookId)
                    .put("c", it.chapter)
                    .put("s", it.sentence)
                    .put("title", it.chapterTitle)
                    .put("note", it.note)
                    .put("t", it.createdAt)
            )
        }
        bookmarkPrefs.edit().putString("list", arr.toString()).apply()
    }

    fun addBookmark(bookId: String, chapter: Int, sentence: Int, chapterTitle: String, note: String) {
        val b = Bookmark(
            id = UUID.randomUUID().toString(),
            bookId = bookId, chapter = chapter, sentence = sentence,
            chapterTitle = chapterTitle, note = note.trim(), createdAt = System.currentTimeMillis(),
        )
        saveBookmarks(bookmarks() + b)
    }

    fun deleteBookmark(id: String) {
        saveBookmarks(bookmarks().filterNot { it.id == id })
    }

    // ---------------- 杂项 ----------------

    fun sourceFile(bookId: String): File? =
        sourcesDir.listFiles()?.firstOrNull { it.name.startsWith("$bookId.") }

    fun rootDir(): File = root
}
