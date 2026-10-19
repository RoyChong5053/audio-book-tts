package com.roychong.audiobook.parse

import com.roychong.audiobook.book.Book
import com.roychong.audiobook.book.Chapter
import com.roychong.audiobook.book.Sentence
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document as JsoupDocument
import org.jsoup.nodes.Element
import org.w3c.dom.Document as XmlDocument
import org.w3c.dom.Element as XmlElement
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedChapter(
    val title: String,
    val paragraphs: List<String>,
)

data class ParsedBook(
    val title: String,
    val author: String,
    val format: String,
    val coverBytes: ByteArray?,
    val chapters: List<ParsedChapter>,
) {
    fun toBook(id: String, sourceName: String, coverPath: String?, addedAt: Long): Book {
        val chapters = this.chapters.mapNotNull { pc ->
            val builder = BookParser.ChapterBuilder(pc.title)
            pc.paragraphs.forEach { builder.addParagraph(it) }
            builder.build().takeIf { it.sentences.isNotEmpty() }
        }
        return Book(
            id = id,
            title = title.ifBlank { sourceName.substringBeforeLast('.').ifBlank { "未命名" } },
            author = author,
            format = format,
            sourceName = sourceName,
            coverPath = coverPath,
            addedAt = addedAt,
            chapters = chapters.ifEmpty { listOf(Chapter("正文", "", emptyList())) },
        )
    }
}

object BookParser {

    private val CHAPTER_RE = Regex(
        "^(第\\s*[0-9零一二三四五六七八九十百千万两〇]+\\s*[章节卷回部篇集].{0,60}|" +
            "Chapter\\s+[0-9IVXLCDMivxlcdm]+.{0,60}|" +
            "CHAPTER\\s+.{0,60}|" +
            "(序章|楔子|引子|前言|序言|后记|尾声|番外|终章|尾声).{0,40})$",
        RegexOption.IGNORE_CASE,
    )

    private val SECTION_RE = Regex(
        "^(Abstract|Introduction|Conclusion|References|Acknowledg(e)?ments|" +
            "[0-9]+(\\.[0-9]+)*\\s+[A-Z][^。！？]{0,60})$",
        RegexOption.IGNORE_CASE,
    )

    private val PAGE_NUMBER_RE = Regex("^\\s*[-–—]?\\s*[0-9]{1,4}\\s*[-–—]?\\s*$")
    private val CJK_SPACE_RE = Regex("(?<=[\\u4e00-\\u9fff\\u3000-\\u303f])\\s+(?=[\\u4e00-\\u9fff\\u3000-\\u303f])")

    fun parse(file: File, name: String, mime: String): ParsedBook {
        val ext = name.substringAfterLast('.', "").lowercase()
        val stem = name.substringBeforeLast('.').ifBlank { "未命名" }
        return when {
            ext == "pdf" || mime.contains("pdf") -> parsePdf(file, stem)
            ext == "epub" || mime.contains("epub") -> parseEpub(file, stem)
            ext == "html" || ext == "htm" || ext == "xhtml" || mime.contains("html") ->
                parseHtml(decode(readBytes(file)), stem)
            ext == "md" || ext == "markdown" || mime.contains("markdown") ->
                parseMarkdown(decode(readBytes(file)), stem)
            else -> parseText(decode(readBytes(file)), stem)
        }
    }

    private fun readBytes(f: File): ByteArray = f.readBytes()

    // ---------------- 纯文本 / Markdown ----------------

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 严格 UTF-8；失败再按中文常见 GB18030
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        val gb = runCatching { String(bytes, charset("GB18030")) }.getOrNull()
        if (gb != null) return gb
        return String(bytes, Charsets.UTF_8)
    }

    private fun parseText(text: String, stem: String): ParsedBook {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paras = ArrayList<Pair<String, Boolean>>()
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            paras.add(line to isHeading(line))
        }
        val chapters = buildChapters(paras, "正文")
        return ParsedBook(stem, "", "txt", null, chapters)
    }

    private fun parseMarkdown(md: String, stem: String): ParsedBook {
        var text = md.replace("\r\n", "\n").replace('\r', '\n')
        // YAML front matter
        if (text.startsWith("---\n")) {
            val end = text.indexOf("\n---", 4)
            if (end > 0) text = text.substring(end + 4)
        }
        val lines = text.split('\n')
        val paras = ArrayList<Pair<String, Boolean>>()
        var inFence = false
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                if (trimmed.isNotEmpty()) paras.add(trimmed to false)
                continue
            }
            if (trimmed.isEmpty()) continue
            val heading = trimmed.startsWith("#")
            val cleaned = stripMarkdown(trimmed)
            if (cleaned.isBlank()) continue
            paras.add(cleaned to (heading || isHeading(cleaned)))
        }
        val chapters = buildChapters(paras, "正文")
        val title = paras.firstOrNull { it.second }?.first?.trimStart('#', ' ')?.ifBlank { stem } ?: stem
        return ParsedBook(title, "", "md", null, chapters)
    }

    private fun stripMarkdown(s: String): String {
        var t = s
        t = t.replace(Regex("^#{1,6}\\s*"), "")
        t = t.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("`{1,3}"), "")
        t = t.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
        t = t.replace(Regex("(\\*|_)(.*?)\\1"), "$2")
        t = t.replace(Regex("^\\s*>\\s?"), "")
        t = t.replace(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), "")
        t = t.replace(Regex("^\\s*[-*_]{3,}\\s*$"), "")
        t = t.replace(Regex("<[^>]+>"), "")
        return t.trim()
    }

    // ---------------- HTML / EPUB ----------------

    private data class HtmlSection(val title: String, val paragraphs: List<String>)

    private fun parseHtml(html: String, stem: String): ParsedBook {
        val doc = Jsoup.parse(html)
        doc.select("script,style,noscript,iframe,svg").remove()
        val sections = htmlSections(doc, stem)
        val title = doc.title().ifBlank { stem }
        val chapters = sections.map { sec ->
            ParsedChapter(sec.title.ifBlank { stem }, sec.paragraphs)
        }.filter { it.paragraphs.isNotEmpty() }
        return ParsedBook(title, "", "html", null,
            chapters.ifEmpty { listOf(ParsedChapter(stem, listOf(doc.body()?.text().orEmpty()))) })
    }

    private fun htmlSections(doc: JsoupDocument, fallback: String): List<HtmlSection> {
        val out = ArrayList<HtmlSection>()
        var curTitle = fallback
        var cur = ArrayList<String>()
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(HtmlSection(curTitle, cur))
                cur = ArrayList()
            }
        }
        val blocks = doc.select("h1,h2,h3,h4,p,blockquote,pre")
        for (el in blocks) {
            if (el.parents().any { p -> p.tagName() in setOf("h1", "h2", "h3", "h4", "p", "blockquote") && p !== el }) {
                continue
            }
            val text = blockText(el)
            if (text.isBlank()) continue
            if (el.tagName().startsWith("h")) {
                flush()
                curTitle = text.take(80)
            } else {
                cur.add(text)
            }
        }
        flush()
        if (out.isEmpty()) {
            val body = doc.body()?.text().orEmpty()
            if (body.isNotBlank()) out.add(HtmlSection(fallback, listOf(body)))
        }
        return out
    }

    private fun blockText(el: Element): String {
        val t = el.wholeText().ifBlank { el.text() }
        return t.replace('\u00a0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    private fun parseEpub(file: File, stem: String): ParsedBook {
        ZipFile(file).use { zip ->
            val entries = HashMap<String, ByteArray>()
            zip.entries().asSequence().forEach { e ->
                if (!e.isDirectory && e.size < 30_000_000) {
                    runCatching { entries[e.name] = zip.getInputStream(e).use { it.readBytes() } }
                }
            }
            val container = entries.entries.firstOrNull { it.key.endsWith("META-INF/container.xml") }
                ?: throw IllegalStateException("不是有效的 EPUB：缺少 container.xml")
            val containerDoc = xml(container.value)
            val rootfile = containerDoc.getElementsByTagName("rootfile").item(0) as? XmlElement
                ?: throw IllegalStateException("EPUB 缺少 rootfile")
            val opfPath = normalizeZipPath(rootfile.getAttribute("full-path"))
            val opfBytes = entries[opfPath] ?: throw IllegalStateException("EPUB 缺少 OPF：$opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = xml(opfBytes)

            val metadata = opf.getElementsByTagName("dc:title")
            var title = stem
            if (metadata.length > 0) {
                val t = metadata.item(0).textContent?.trim()
                if (!t.isNullOrBlank()) title = t
            }
            val creators = opf.getElementsByTagName("dc:creator")
            val author = if (creators.length > 0) creators.item(0).textContent?.trim().orEmpty() else ""

            // manifest: id -> (href, mediaType, properties)
            data class Item(val href: String, val type: String, val props: String)
            val manifest = HashMap<String, Item>()
            val items = opf.getElementsByTagName("item")
            var coverHref: String? = null
            var coverIdFromMeta: String? = null
            for (i in 0 until items.length) {
                val el = items.item(i) as? XmlElement ?: continue
                val id = el.getAttribute("id")
                val href = normalizeZipPath(joinPath(opfDir, el.getAttribute("href")))
                val type = el.getAttribute("media-type")
                val props = el.getAttribute("properties")
                manifest[id] = Item(href, type, props)
                if (props.contains("cover-image")) coverHref = href
            }
            val metas = opf.getElementsByTagName("meta")
            for (i in 0 until metas.length) {
                val el = metas.item(i) as? XmlElement ?: continue
                if (el.getAttribute("name").equals("cover", true)) coverIdFromMeta = el.getAttribute("content")
            }
            if (coverHref == null && coverIdFromMeta != null) coverHref = manifest[coverIdFromMeta]?.href
            val coverBytes = coverHref?.let { entries[it] }

            // spine
            val spineItems = ArrayList<Item>()
            val spine = opf.getElementsByTagName("itemref")
            for (i in 0 until spine.length) {
                val el = spine.item(i) as? XmlElement ?: continue
                manifest[el.getAttribute("idref")]?.let { spineItems.add(it) }
            }

            // 目录：EPUB3 nav 或 EPUB2 ncx
            val toc = HashMap<String, String>()
            val navItem = manifest.values.firstOrNull { it.props.split(' ').contains("nav") }
            val ncxItem = manifest.values.firstOrNull { it.type.contains("dtbncx") }
            if (navItem != null) {
                entries[navItem.href]?.let { bytes ->
                    val nav = Jsoup.parse(String(bytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                    nav.select("a[href]").forEach { a ->
                        val href = normalizeZipPath(joinPath(navItem.href.substringBeforeLast('/', ""), a.attr("href").substringBefore('#')))
                        val label = a.text().trim()
                        if (label.isNotEmpty()) toc.putIfAbsent(href, label)
                    }
                }
            } else if (ncxItem != null) {
                entries[ncxItem.href]?.let { bytes ->
                    val ncx = xml(bytes)
                    val points = ncx.getElementsByTagName("navPoint")
                    for (i in 0 until points.length) {
                        val p = points.item(i) as? XmlElement ?: continue
                        val label = p.getElementsByTagName("text").item(0)?.textContent?.trim().orEmpty()
                        val content = p.getElementsByTagName("content").item(0) as? XmlElement
                        val src = content?.getAttribute("src")?.substringBefore('#').orEmpty()
                        val href = normalizeZipPath(joinPath(ncxItem.href.substringBeforeLast('/', ""), src))
                        if (label.isNotEmpty() && src.isNotEmpty()) toc.putIfAbsent(href, label)
                    }
                }
            }

            val chapters = ArrayList<ParsedChapter>()
            spineItems.forEachIndexed { idx, item ->
                if (!(item.type.contains("html") || item.type.contains("xml"))) return@forEachIndexed
                val bytes = entries[item.href] ?: return@forEachIndexed
                val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
                doc.select("script,style,noscript,iframe,svg").remove()
                val secs = htmlSections(doc, toc[item.href] ?: "第 ${idx + 1} 章")
                if (secs.isEmpty()) return@forEachIndexed
                // 每个 spine 文件合并成一个章节；若文件内有多级标题，用第一个标题当章名
                val firstTitle = toc[item.href]
                    ?: secs.firstOrNull { it.title.isNotBlank() }?.title
                    ?: "第 ${idx + 1} 章"
                val paras = ArrayList<String>()
                secs.forEach { paras.addAll(it.paragraphs) }
                if (paras.isEmpty()) return@forEachIndexed
                chapters.add(ParsedChapter(firstTitle, paras))
            }
            if (chapters.isEmpty()) throw IllegalStateException("EPUB 里没有解析出正文")
            return ParsedBook(title, author, "epub", coverBytes, chapters)
        }
    }

    private fun xml(bytes: ByteArray): XmlDocument {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        runCatching {
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        runCatching {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun joinPath(dir: String, href: String): String {
        if (href.startsWith("/")) return href.removePrefix("/")
        if (dir.isBlank()) return href
        return "$dir/$href"
    }

    private fun normalizeZipPath(path: String): String {
        val parts = ArrayList<String>()
        path.replace('\\', '/').split('/').forEach { p ->
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    // ---------------- PDF ----------------

    private fun parsePdf(file: File, stem: String): ParsedBook {
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)
        try {
            val info = doc.documentInformation
            val title = info?.title?.trim()?.ifBlank { stem } ?: stem
            val author = info?.author?.trim().orEmpty()
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }
            val all = stripper.getText(doc)
            val pages = all.split('\u000c')
            if (pages.sumOf { it.trim().length } < pages.size * 15) {
                throw IllegalStateException("PDF 没有可用文字层（可能是扫描版，需要 OCR）")
            }

            // 页眉页脚：在多数页面顶部/底部重复出现的短行
            val repeated = HashMap<String, Int>()
            pages.forEach { page ->
                val lines = page.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                (lines.take(2) + lines.takeLast(2)).forEach { line ->
                    if (line.length <= 80) repeated[line] = (repeated[line] ?: 0) + 1
                }
            }
            val marginLines = repeated.filter { (_, n) -> n >= maxOf(3, pages.size * 2 / 3) }.keys

            val paragraphs = ArrayList<String>()
            pages.forEach { page ->
                val lines = page.split('\n').map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filterNot { PAGE_NUMBER_RE.matches(it) }
                    .filterNot { it in marginLines }
                paragraphs.addAll(flowPdfLines(lines))
            }

            val parasWithHeading = paragraphs.map { it to isHeading(it) }
            val chapters = if (parasWithHeading.any { it.second }) {
                buildChapters(parasWithHeading, "正文")
            } else {
                chunk(paragraphs, 9000)
            }
            if (chapters.isEmpty()) throw IllegalStateException("PDF 没有解析出正文")
            return ParsedBook(title, author, "pdf", null, chapters)
        } finally {
            runCatching { doc.close() }
        }
    }

    /** 把 PDF 的硬换行重新接成段落。 */
    private fun flowPdfLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val lengths = lines.map { it.length }.sorted()
        val median = if (lengths.isEmpty()) 40 else lengths[lengths.size / 2].coerceAtLeast(20)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) out.add(t)
            cur.setLength(0)
        }
        for (raw in lines) {
            val line = raw.replace(Regex(" {2,}"), " ").trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (isHeading(line)) {
                flush()
                out.add(line)
                continue
            }
            if (cur.isNotEmpty()) {
                val a = cur.last()
                val b = line.first()
                if (a == '-' && b.isLetter()) {
                    // 英文断词：state-of- + the-art → state-of-the-art
                    cur.setLength(cur.length - 1)
                } else {
                    val sep = if (a.isCjk() || b.isCjk() ||
                        a in "，。！？；：、）》”’" || b in "，。！？；：、）》”’"
                    ) "" else " "
                    cur.append(sep)
                }
            }
            cur.append(line)
            val end = line.lastOrNull() ?: ' '
            val strongEnd = end in "。！？；;"
            val latinEnd = end in ".!?" 
            if (strongEnd || (latinEnd && line.length < median * 0.9)) {
                flush()
            }
        }
        flush()
        return out
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3000'..'\u303f' || this in '\uff00'..'\uffef'

    private fun String.endsWithParagraph(): Boolean {
        val t = trimEnd()
        if (t.isEmpty()) return true
        return t.last() in "。！？!?；;…"
    }

    // ---------------- 通用切章 / 切句 ----------------

    private fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 70) return false
        if (CHAPTER_RE.matches(t)) return true
        if (t.startsWith("#") && t.length <= 70) return true
        // 英文 PDF / 技术文档的常见小节标题
        if (SECTION_RE.matches(t)) return true
        return false
    }

    /**
     * 有章节标题就按标题切；没有就按约 9000 字在段落边界自动分节。
     */
    private fun buildChapters(paras: List<Pair<String, Boolean>>, fallbackTitle: String): List<ParsedChapter> {
        val hasHeading = paras.any { it.second }
        if (!hasHeading) return chunk(paras.map { it.first }, 9000, fallbackTitle)
        val chapters = ArrayList<ParsedChapter>()
        var title = fallbackTitle
        var cur = ArrayList<String>()
        fun flush() {
            if (cur.isNotEmpty()) {
                chapters.add(ParsedChapter(title.ifBlank { fallbackTitle }, ArrayList(cur)))
                cur = ArrayList()
            }
        }
        paras.forEach { (text, heading) ->
            if (heading) {
                flush()
                title = text.trim()
            } else {
                cur.add(text)
            }
        }
        flush()
        return chapters
    }

    private fun chunk(paras: List<String>, maxChars: Int, fallbackTitle: String = "正文"): List<ParsedChapter> {
        val out = ArrayList<ParsedChapter>()
        var cur = ArrayList<String>()
        var chars = 0
        var n = 1
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(ParsedChapter(if (n == 1) fallbackTitle else "第 $n 节", ArrayList(cur)))
                n++
                cur = ArrayList()
                chars = 0
            }
        }
        paras.forEach { p ->
            if (chars > 0 && chars + p.length > maxChars) flush()
            cur.add(p)
            chars += p.length
        }
        flush()
        return out
    }

    /** 逐步拼出一个章节的全文与句子区间。 */
    class ChapterBuilder(private val title: String) {
        private val sb = StringBuilder()
        private val sentences = ArrayList<Sentence>()

        fun addParagraph(raw: String) {
            val p = raw.replace('\u00a0', ' ')
                .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
                .replace(Regex(" {2,}"), " ")
                .replace(CJK_SPACE_RE, "")
                .trim()
            if (p.isEmpty()) return
            val prefix = if (sb.isEmpty()) "" else "\n\n"
            val base = sb.length + prefix.length
            sb.append(prefix).append(p)
            splitSentenceRanges(p).forEach { (s, e) ->
                var s0 = s
                var e0 = e
                while (s0 < e0 && p[s0].isWhitespace()) s0++
                while (e0 > s0 && p[e0 - 1].isWhitespace()) e0--
                if (e0 > s0) {
                    sentences.add(
                        Sentence(p.substring(s0, e0), base + s0, base + e0)
                    )
                }
            }
        }

        fun build(): Chapter = Chapter(title, sb.toString(), sentences)
    }

    /**
     * 句子切分：优先在 。！？；… 处断，超长再退到逗号，最后硬切。
     * 返回的是 [start, end) 区间，保留原文，方便高亮定位。
     */
    fun splitSentenceRanges(p: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val n = p.length
        var start = 0
        var i = 0
        fun emit(endExclusive: Int) {
            var s = start
            var e = endExclusive
            while (s < e && p[s].isWhitespace()) s++
            while (e > s && p[e - 1].isWhitespace()) e--
            if (e > s) out.add(s to e)
            start = endExclusive
        }
        while (i < n) {
            val c = p[i]
            if (c == '\n') {
                emit(i)
                i++
                continue
            }
            if (c in "。！？!?；;…") {
                var j = i + 1
                while (j < n && p[j] in "。！？!?；;…") j++
                while (j < n && p[j] in "”’\"'）)】》」』]") j++
                emit(j)
                i = j
                continue
            }
            if (i - start >= 100) {
                // 超长：在最近一个逗号处断，找不到就硬切 100 字
                var cut = -1
                for (k in i downTo (start + 40)) {
                    if (p[k] in "，,、：:") {
                        cut = k + 1
                        break
                    }
                }
                if (cut <= start) cut = i
                emit(cut)
                i = cut
                continue
            }
            i++
        }
        if (start < n) emit(n)
        return out
    }
}
