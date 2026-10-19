package com.roychong.audiobook

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.roychong.audiobook.book.Book
import com.roychong.audiobook.book.Bookmark
import com.roychong.audiobook.book.BookStore
import com.roychong.audiobook.player.PlaybackService
import com.roychong.audiobook.player.ReadingController
import com.roychong.audiobook.ui.ThemeUi
import java.util.concurrent.Executors
import kotlin.math.max

class ReaderActivity : AppCompatActivity(), ReadingController.Listener {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }

    private lateinit var store: BookStore
    private lateinit var settings: AppSettings
    private lateinit var root: View
    private lateinit var scroll: ScrollView
    private lateinit var content: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var bookTitle: TextView
    private lateinit var chapterTitle: TextView
    private lateinit var playButton: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnToc: TextView
    private lateinit var btnBookmark: TextView
    private lateinit var btnMore: TextView

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var bookId: String = ""
    private var book: Book? = null
    private var controller: ReadingController? = null
    private var bound = false
    private var chapterIndex = 0
    private var sentenceIndex = 0
    private var fraction = 0f
    private var theme = ReaderTheme.DARK

    private var spannable: Spannable? = null
    private var hotSpan: ForegroundColorSpan? = null
    private var bgSpan: BackgroundColorSpan? = null
    private var weightCum: FloatArray = FloatArray(0)
    private var lastHighlight = 0
    private var lastSentence = -1
    private var lastError: String? = null

    private var suppressAutoScrollUntil = 0L
    private var barsVisible = true

    private val gesture by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleBars()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                seekFromTouch(e)
            }
        })
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? PlaybackService.LocalBinder ?: return
            val c = b.controller()
            controller = c
            bound = true
            c.addListener(this@ReaderActivity)
            val st = c.state()
            if (st.bookId == bookId && c.currentBook() != null) {
                chapterIndex = st.chapterIndex
                sentenceIndex = st.sentenceIndex
                onState(st)
            } else {
                c.openBook(bookId, autoPlay = false)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controller = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BookStore(this)
        settings = AppSettings(this)
        theme = settings.theme()
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: settings.lastBookId().orEmpty()
        if (bookId.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_reader)

        root = findViewById(R.id.root)
        scroll = findViewById(R.id.scroll)
        content = findViewById(R.id.tvContent)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        bookTitle = findViewById(R.id.tvBookTitle)
        chapterTitle = findViewById(R.id.tvChapterTitle)
        playButton = findViewById(R.id.btnPlay)
        prevButton = findViewById(R.id.btnPrev)
        nextButton = findViewById(R.id.btnNext)
        progressText = findViewById(R.id.tvProgress)
        statusText = findViewById(R.id.tvStatus)
        btnBack = findViewById(R.id.btnBack)
        btnToc = findViewById(R.id.btnToc)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnMore = findViewById(R.id.btnMore)

        btnBack.setOnClickListener { finish() }
        playButton.setOnClickListener { controller?.toggle() ?: toast(getString(R.string.reader_loading)) }
        prevButton.setOnClickListener { controller?.prevSentence() }
        nextButton.setOnClickListener { controller?.nextSentence() }
        btnToc.setOnClickListener { showToc() }
        btnBookmark.setOnClickListener { showBookmarks() }
        btnMore.setOnClickListener { showMore() }

        scroll.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                suppressAutoScrollUntil = System.currentTimeMillis() + 3000
            }
            gesture.onTouchEvent(ev)
            false
        }

        applyColors()
        loadBook()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        if (id == bookId) return
        controller?.removeListener(this)
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
        controller = null
        bookId = id
        book = null
        lastRenderedChapter = -1
        setIntent(intent)
        loadBook()
    }

    private fun loadBook() {
        val loadingId = bookId
        io.execute {
            val loaded = runCatching { store.load(loadingId) }.getOrNull()
            main.post {
                if (bookId != loadingId) return@post
                if (loaded == null) {
                    toast("书籍不存在")
                    finish()
                    return@post
                }
                book = loaded
                bookTitle.text = loaded.title
                val pos = store.progress(loadingId)
                val c = pos.chapter.coerceIn(0, (loaded.chapters.size - 1).coerceAtLeast(0))
                renderChapter(c, pos.sentence, if (pos.updatedAt > 0) pos.fraction else 0f)
                connect()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val t = settings.theme()
        if (t != theme) {
            theme = t
            applyColors()
            rebuildSpannable()
        } else {
            applyFont()
        }
    }

    override fun onStart() {
        super.onStart()
        if (book != null && !bound) connect()
    }

    override fun onStop() {
        super.onStop()
        controller?.removeListener(this)
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
    }

    private fun connect() {
        if (bound) return
        PlaybackService.start(this)
        runCatching {
            bindService(
                Intent(this, PlaybackService::class.java),
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    // ---------------- 显示 ----------------

    private fun applyColors() {
        ThemeUi.apply(this, theme)
        root.setBackgroundColor(theme.bg)
        bookTitle.setTextColor(theme.text)
        chapterTitle.setTextColor(theme.dim)
        btnBack.setTextColor(theme.text)
        btnMore.setTextColor(theme.text)
        btnToc.setTextColor(theme.accent)
        btnBookmark.setTextColor(theme.accent)
        playButton.setTextColor(theme.accent)
        prevButton.setTextColor(theme.dim)
        nextButton.setTextColor(theme.dim)
        progressText.setTextColor(theme.dim)
        statusText.setTextColor(theme.dim)
        content.setTextColor(theme.dim)
        topBar.setBackgroundColor(ThemeUi.withAlpha(theme.bg, 0xF2))
        bottomBar.setBackgroundColor(ThemeUi.withAlpha(theme.bg, 0xF2))
        applyFont()
    }

    private fun applyFont() {
        content.setTextSize(settings.fontSize().toFloat())
        content.setLineSpacing(0f, settings.lineSpacing())
        val m = settings.marginDp() * resources.displayMetrics.density
        content.setPadding(m.toInt(), (18 * resources.displayMetrics.density).toInt(),
            m.toInt(), (90 * resources.displayMetrics.density).toInt())
        content.typeface = if (settings.serif()) Typeface.SERIF else Typeface.SANS_SERIF
    }

    private fun renderChapter(chapter: Int, sentence: Int, frac: Float) {
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapter) ?: return
        chapterIndex = chapter
        sentenceIndex = sentence
        fraction = frac
        chapterTitle.text = ch.title
        weightCum = buildWeights(ch.text)
        lastHighlight = 0
        lastSentence = -1
        hotSpan = null
        bgSpan = null
        val sp = SpannableString(ch.text)
        spannable = sp
        content.setTextColor(theme.dim)
        content.setText(sp, TextView.BufferType.SPANNABLE)
        spannable = content.text as? Spannable ?: sp
        content.scrollTo(0, 0)
        applyFont()
        drawAtSentence(sentence, sentenceStartOffset(ch, sentence), forceScroll = true)
        scroll.post { scrollToOffset(sentenceStartOffset(ch, sentence), force = true) }
    }

    private fun rebuildSpannable() {
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapterIndex) ?: return
        val keepIdx = lastHighlight
        val keepSentence = max(0, lastSentence)
        weightCum = buildWeights(ch.text)
        val sp = SpannableString(ch.text)
        spannable = sp
        content.setTextColor(theme.dim)
        hotSpan = null
        bgSpan = null
        content.setText(sp, TextView.BufferType.SPANNABLE)
        spannable = content.text as? Spannable ?: sp
        lastHighlight = 0
        drawAtSentence(keepSentence, keepIdx, forceScroll = false)
        scroll.post { scrollToOffset(keepIdx, force = false) }
    }

    private fun sentenceStartOffset(ch: com.roychong.audiobook.book.Chapter, sentence: Int): Int =
        (ch.sentences.getOrNull(sentence)?.start ?: 0).coerceIn(0, ch.text.length)

    private fun drawAtSentence(sentence: Int, offset: Int, forceScroll: Boolean) {
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapterIndex) ?: return
        val sp = spannable ?: return
        val idx = offset.coerceIn(0, sp.length)
        sentenceIndex = sentence
        lastSentence = sentence
        lastHighlight = idx.coerceAtMost(idx)
        if (hotSpan != null) sp.removeSpan(hotSpan)
        hotSpan = if (idx > 0) {
            ForegroundColorSpan(theme.hot).also {
                sp.setSpan(it, 0, idx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else null
        if (bgSpan != null) sp.removeSpan(bgSpan)
        val s = ch.sentences.getOrNull(sentence)
        if (s != null && s.start < s.end && s.end <= sp.length) {
            bgSpan = BackgroundColorSpan(theme.sentenceBg).also {
                sp.setSpan(it, s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        content.invalidate()
        if (forceScroll) scrollToOffset(idx, force = true)
    }

    private fun onProgressHighlight(chapter: Int, sentence: Int, frac: Float) {
        val b = book ?: return
        if (chapter != chapterIndex) {
            renderChapter(chapter, sentence, frac)
            lastRenderedChapter = chapter
            return
        }
        val ch = b.chapters.getOrNull(chapter) ?: return
        val s = ch.sentences.getOrNull(sentence) ?: return
        fraction = frac
        val target = weightTarget(s.start, s.end, frac)
        val idx = if (target < lastHighlight - 2) target else max(lastHighlight, target)
        if (sentence != lastSentence) {
            lastSentence = sentence
            sentenceIndex = sentence
            drawAtSentence(sentence, s.start, forceScroll = false)
            lastHighlight = s.start
        }
        if (idx > lastHighlight) {
            lastHighlight = idx
            drawAt(sentence, idx)
            scrollToOffset(idx, force = false)
        }
    }

    private fun drawAt(sentence: Int, idx: Int) {
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapterIndex) ?: return
        val sp = spannable ?: return
        val i = idx.coerceIn(0, sp.length)
        if (hotSpan != null) sp.removeSpan(hotSpan)
        hotSpan = if (i > 0) {
            ForegroundColorSpan(theme.hot).also {
                sp.setSpan(it, 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else null
        if (bgSpan != null) sp.removeSpan(bgSpan)
        val s = ch.sentences.getOrNull(sentence)
        if (s != null && s.start < s.end && s.end <= sp.length) {
            bgSpan = BackgroundColorSpan(theme.sentenceBg).also {
                sp.setSpan(it, s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        content.invalidate()
    }

    private fun buildWeights(text: String): FloatArray {
        val cum = FloatArray(text.length + 1)
        var acc = 0f
        for (i in text.indices) {
            acc += 1f
            acc += when (text[i]) {
                '。', '！', '？', '!', '?', '\n' -> 3.5f
                '，', '、', '；', '：', ',', ';', ':', '…' -> 1.5f
                else -> 0f
            }
            cum[i + 1] = acc
        }
        return cum
    }

    private fun weightTarget(start: Int, end: Int, frac: Float): Int {
        if (weightCum.size != (book?.chapters?.getOrNull(chapterIndex)?.text?.length ?: 0) + 1) {
            weightCum = buildWeights(book?.chapters?.getOrNull(chapterIndex)?.text.orEmpty())
        }
        val lo = weightCum.getOrElse(start) { 0f }
        val hi = weightCum.getOrElse(end) { lo }
        val target = lo + (hi - lo) * frac.coerceIn(0f, 1f)
        return weightIndex(target)
    }

    private fun weightIndex(target: Float): Int {
        val n = weightCum.size - 1
        if (n <= 0) return 0
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (weightCum[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun scrollToOffset(offset: Int, force: Boolean) {
        if (!force && (!settings.autoScroll() ||
                System.currentTimeMillis() < suppressAutoScrollUntil)
        ) {
            return
        }
        scroll.post {
            val layout = content.layout ?: return@post
            val line = layout.getLineForOffset(offset.coerceIn(0, content.text.length))
            val y = layout.getLineTop(line) - scroll.height / 3
            val target = y.coerceAtLeast(0)
            if (force || target > scroll.scrollY + 4 || target < scroll.scrollY - scroll.height) {
                scroll.scrollTo(0, target)
            }
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val vis = if (barsVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
        statusText.visibility = if (barsVisible && statusText.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- 播放器回调 ----------------

    override fun onState(state: ReadingController.PlayerState) {
        if (state.bookId != null && state.bookId != bookId) return
        if (state.loading) return
        if (state.chapterIndex != chapterIndex || state.chapterIndex != lastRenderedChapter) {
            renderChapter(state.chapterIndex, state.sentenceIndex, 0f)
            lastRenderedChapter = state.chapterIndex
        }
        bookTitle.text = state.bookTitle.ifBlank { book?.title.orEmpty() }
        chapterTitle.text = state.chapterTitle
        playButton.text = if (state.playing || state.preparing) "❚❚" else "▶"
        progressText.text = "${state.chapterIndex + 1}/${state.chapterCount} · ${state.sentenceIndex + 1}/${state.sentenceCount}"
        if (state.error != null && state.error != lastError) {
            lastError = state.error
            toast(getString(R.string.reader_error_fmt, state.error))
        }
        statusText.text = when {
            state.error != null -> getString(R.string.reader_error_fmt, state.error)
            state.preparing -> "合成中…"
            state.sleepAt > 0 -> {
                val min = ((state.sleepAt - System.currentTimeMillis()) / 60000L).coerceAtLeast(0) + 1
                getString(R.string.reader_sleep_set, min)
            }
            else -> ""
        }
        statusText.visibility =
            if (barsVisible && statusText.text.isNotEmpty()) View.VISIBLE else View.GONE
        statusText.setTextColor(if (state.error != null) theme.hot else theme.dim)
        root.keepScreenOn = state.playing || state.preparing
    }

    override fun onHighlight(chapter: Int, sentence: Int, fraction: Float) {
        onProgressHighlight(chapter, sentence, fraction)
    }

    override fun onFinished() {
        toast(getString(R.string.reader_end))
        playButton.text = "▶"
    }

    private var lastRenderedChapter = -1

    // ---------------- 目录 / 书签 / 设置 ----------------

    private fun showToc() {
        val b = book ?: return
        val titles = b.chapters.mapIndexed { i, ch ->
            "${i + 1}. ${ch.title.ifBlank { "第 ${i + 1} 章" }}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_toc)
            .setItems(titles) { _, which ->
                controller?.goToChapter(which)
                    ?: toast(getString(R.string.reader_loading))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val b = book ?: return
        val list = store.bookmarks(bookId)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.reader_bookmark_add))
        list.forEach { bm ->
            val preview = b.chapters.getOrNull(bm.chapter)
                ?.sentences?.getOrNull(bm.sentence)?.text?.take(26).orEmpty()
            val note = if (bm.note.isBlank()) "" else " · ${bm.note}"
            labels.add("${bm.chapterTitle} · $preview$note")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_bookmark)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) addBookmark()
                else bookmarkMenu(list[which - 1])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addBookmark() {
        val st = controller?.state() ?: ReadingController.PlayerState(
            chapterIndex = chapterIndex, sentenceIndex = sentenceIndex
        )
        val b = book ?: return
        val ch = b.chapters.getOrNull(st.chapterIndex) ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.reader_bookmark_note)
            setSingleLine(true)
            setTextColor(0xFFE7ECF2.toInt())
            setHintTextColor(0xFF95A3B5.toInt())
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reader_bookmark_add))
            .setView(wrap)
            .setPositiveButton(R.string.reader_bookmark_add) { _, _ ->
                store.addBookmark(
                    bookId, st.chapterIndex, st.sentenceIndex,
                    ch.title, input.text?.toString().orEmpty(),
                )
                toast(getString(R.string.reader_bookmark_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bookmarkMenu(bm: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle(bm.chapterTitle)
            .setItems(
                arrayOf(getString(R.string.reader_bookmark_jump), getString(R.string.reader_bookmark_delete))
            ) { _, which ->
                if (which == 0) {
                    val playing = controller?.state()?.playing == true
                    controller?.seekTo(bm.chapter, bm.sentence, playing)
                        ?: toast(getString(R.string.reader_loading))
                } else {
                    store.deleteBookmark(bm.id)
                    toast(getString(R.string.reader_bookmark_delete))
                }
            }
            .show()
    }

    private fun showMore() {
        val items = arrayOf(
            getString(R.string.reader_prev_chapter),
            getString(R.string.reader_next_chapter),
            getString(R.string.reader_theme),
            getString(R.string.reader_font),
            getString(R.string.reader_sleep),
            getString(R.string.settings_title),
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> controller?.prevChapter()
                    1 -> controller?.nextChapter()
                    2 -> pickTheme()
                    3 -> pickFontSize()
                    4 -> pickSleep()
                    5 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun pickTheme() {
        val entries = ReaderTheme.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_theme)
            .setSingleChoiceItems(entries, ReaderTheme.entries.indexOf(theme)) { d, which ->
                theme = ReaderTheme.entries[which]
                settings.saveTheme(theme)
                applyColors()
                rebuildSpannable()
                d.dismiss()
            }
            .show()
    }

    private fun pickFontSize() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val seek = SeekBar(this).apply {
            max = 20
            progress = settings.fontSize() - 14
        }
        val preview = TextView(this).apply {
            text = "字号预览 Aa 书"
            setTextColor(0xFFE7ECF2.toInt())
            textSize = settings.fontSize().toFloat()
            setPadding(0, pad / 2, 0, 0)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                preview.textSize = (14 + p).toFloat()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 3, pad, 0)
            addView(seek)
            addView(preview)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_font)
            .setView(wrap)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                settings.saveFontSize(14 + seek.progress)
                applyFont()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickSleep() {
        val labels = arrayOf(
            getString(R.string.reader_sleep_off),
            getString(R.string.reader_sleep_min, 15),
            getString(R.string.reader_sleep_min, 30),
            getString(R.string.reader_sleep_min, 45),
            getString(R.string.reader_sleep_min, 60),
        )
        val mins = intArrayOf(0, 15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_sleep)
            .setItems(labels) { _, which ->
                controller?.setSleepTimer(mins[which])
                if (mins[which] > 0) {
                    toast(getString(R.string.reader_sleep_set, mins[which]))
                }
                controller?.state()?.let { onState(it) }
            }
            .show()
    }

    // ---------------- 手势 ----------------

    private fun seekFromTouch(e: MotionEvent) {
        val layout = content.layout ?: return
        val x = e.x + scroll.scrollX - content.left
        val y = e.y + scroll.scrollY - content.top
        val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
        val offset = layout.getOffsetForHorizontal(line, x.coerceAtLeast(0f))
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapterIndex) ?: return
        val s = ch.sentenceAt(offset)
        controller?.playFrom(chapterIndex, s) ?: toast(getString(R.string.reader_loading))
        suppressAutoScrollUntil = 0
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
