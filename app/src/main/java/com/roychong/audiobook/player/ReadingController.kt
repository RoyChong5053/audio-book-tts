package com.roychong.audiobook.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.roychong.audiobook.AppSettings
import com.roychong.audiobook.book.Book
import com.roychong.audiobook.book.BookStore
import com.roychong.audiobook.book.Position
import com.roychong.audiobook.book.SpeechChunk
import com.roychong.audiobook.book.SpeechChunker
import com.roychong.audiobook.tts.AudioMeta
import com.roychong.audiobook.tts.BookAudioStore
import com.roychong.audiobook.tts.TtsClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 朗读控制器：分块合成、磁盘预取、连续播放、KTV 进度和归档。
 *
 * 关键变化（v0.2）：
 *  - 不再一句一请求，而是按用户选择的块长（120 / 300 / 2000 字…）请求 TTS；
 *  - 首块永远压到 80 字以内，先快速开口；
 *  - 合成线程只看“预取目标”往前跑，播放在后面消费，云端不会无限调用；
 *  - 每块音频落盘缓存，并 append 到“本模型 + 本次播放”的归档文件；
 *  - 大块也能从块中间恢复：保存字符偏移，恢复时按权重 seek 到对应音频位置。
 */
class ReadingController(context: Context) {

    data class PlayerState(
        val bookId: String? = null,
        val bookTitle: String = "",
        val chapterIndex: Int = 0,
        val chapterTitle: String = "",
        val chapterCount: Int = 0,
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val playing: Boolean = false,
        val preparing: Boolean = false,
        val speaking: Boolean = false,
        val preloading: Boolean = false,
        val buffering: Boolean = false,
        val preloadBufferedMs: Long = 0L,
        val preloadTargetMs: Long = 0L,
        val bufferedAheadMs: Long = 0L,
        val chunkIndex: Int = 0,
        val chunkCount: Int = 0,
        val error: String? = null,
        val sleepAt: Long = 0L,
        val loading: Boolean = false,
    )

    interface Listener {
        fun onState(state: PlayerState)
        fun onHighlight(chapter: Int, sentence: Int, fraction: Float)
        fun onFinished() {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val store = BookStore(app)
    private val settings = AppSettings(app)
    private val player = AudioPlayer(app)
    private val audio = BookAudioStore(app)
    private val loader = Executors.newSingleThreadExecutor()

    @Volatile private var book: Book? = null
    @Volatile private var chunks: List<SpeechChunk> = emptyList()
    private var chunkCharsBuilt = -1
    private val weightCache = HashMap<Int, FloatArray>()

    @Volatile private var chapterIndex = 0
    @Volatile private var sentenceIndex = 0
    @Volatile private var sentenceFraction = 0f
    @Volatile private var currentChunkIndex = 0
    @Volatile private var currentChunkFraction = 0f

    @Volatile private var session = 0
    @Volatile private var paused = true
    @Volatile private var openToken = 0
    @Volatile private var lastSaved = 0L
    @Volatile private var state = PlayerState()
    private var currentSession: Session? = null
    private var sleepRunnable: Runnable? = null

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            main.post { pause() }
        }
    }

    // ---------------- 对外 ----------------

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        main.post { l.onState(state) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun state(): PlayerState = state

    fun currentBook(): Book? = book

    fun currentChapterIndex(): Int = chapterIndex

    fun currentSentenceIndex(): Int = sentenceIndex

    fun currentSentenceText(): String {
        val b = book ?: return ""
        return b.chapters.getOrNull(chapterIndex)?.sentences?.getOrNull(sentenceIndex)?.text.orEmpty()
    }

    fun currentChapter(): com.roychong.audiobook.book.Chapter? =
        book?.chapters?.getOrNull(chapterIndex)

    fun openBook(bookId: String, chapter: Int = -1, sentence: Int = -1, autoPlay: Boolean = false) {
        val b = book
        if (b != null && b.id == bookId) {
            if (chapter >= 0) {
                val c = chapter.coerceIn(0, (b.chapters.size - 1).coerceAtLeast(0))
                val s = sentence.coerceIn(0, (b.chapters.getOrNull(c)?.sentences?.size?.minus(1) ?: 0).coerceAtLeast(0))
                if (state.playing && (c != chapterIndex || s != sentenceIndex)) {
                    moveTo(c, s, play = true)
                } else if (!state.playing) {
                    moveTo(c, s, play = false)
                }
            }
            if (autoPlay) play()
            return
        }
        val token = ++openToken
        stopPlayback(clearBook = true)
        state = PlayerState(loading = true, bookId = bookId)
        publish()
        loader.execute {
            val loaded = runCatching { store.load(bookId) }.getOrNull()
            val pos = store.progress(bookId)
            main.post {
                if (token != openToken) return@post
                if (loaded == null) {
                    state = state.copy(loading = false, error = "书籍文件不存在")
                    publish()
                    return@post
                }
                book = loaded
                rebuildChunks(loaded)
                val c = (if (chapter >= 0) chapter else pos.chapter)
                    .coerceIn(0, (loaded.chapters.size - 1).coerceAtLeast(0))
                val maxS = (loaded.chapters.getOrNull(c)?.sentences?.size?.minus(1) ?: 0).coerceAtLeast(0)
                val s = (if (sentence >= 0) sentence else pos.sentence).coerceIn(0, maxS)
                chapterIndex = c
                sentenceIndex = s
                sentenceFraction = if (chapter < 0 && sentence < 0) pos.fraction else 0f
                val start = resolveStart(loaded, c, s, pos)
                currentChunkIndex = start.first
                currentChunkFraction = start.second
                settings.saveLastBookId(bookId)
                state = PlayerState(
                    bookId = loaded.id,
                    bookTitle = loaded.title,
                    chapterIndex = c,
                    chapterTitle = loaded.chapters[c].title,
                    chapterCount = loaded.chapters.size,
                    sentenceIndex = s,
                    sentenceCount = loaded.chapters[c].sentences.size,
                    chunkIndex = currentChunkIndex,
                    chunkCount = chunks.size,
                    sleepAt = state.sleepAt,
                )
                publish()
                notifyHighlight()
                if (autoPlay) startSession(currentChunkIndex, currentChunkFraction)
            }
        }
    }

    fun play() {
        if (book == null) {
            settings.lastBookId()?.let { openBook(it, autoPlay = true) }
            return
        }
        if (state.playing || state.preloading) return
        ensureChunksFresh()
        startSession(currentChunkIndex, currentChunkFraction)
    }

    fun pause() {
        if (!state.playing && !state.preloading) return
        paused = true
        ++session
        cancelCurrentSession()
        saveProgress(force = true)
        abandonFocus()
        state = state.copy(
            playing = false, preparing = false, speaking = false,
            preloading = false, buffering = false,
        )
        publish()
        notifyHighlight()
        onActiveChanged?.invoke(false)
    }

    fun toggle() {
        if (state.playing || state.preloading) pause() else play()
    }

    fun stop() {
        paused = true
        ++session
        cancelCurrentSession()
        saveProgress(force = true)
        abandonFocus()
        state = state.copy(
            playing = false, preparing = false, speaking = false,
            preloading = false, buffering = false,
        )
        publish()
        onActiveChanged?.invoke(false)
    }

    fun nextSentence() {
        val b = book ?: return
        var c = chapterIndex
        var s = sentenceIndex + 1
        val ch = b.chapters.getOrNull(c) ?: return
        if (s >= ch.sentences.size) {
            c++
            s = 0
        }
        if (c >= b.chapters.size) return
        moveTo(c, s, play = state.playing || state.preloading)
    }

    fun prevSentence() {
        val b = book ?: return
        var c = chapterIndex
        var s = sentenceIndex - 1
        if (s < 0) {
            c--
            if (c < 0) return
            s = (b.chapters[c].sentences.size - 1).coerceAtLeast(0)
        }
        moveTo(c, s, play = state.playing || state.preloading)
    }

    fun nextChapter() {
        val b = book ?: return
        if (chapterIndex + 1 >= b.chapters.size) return
        moveTo(chapterIndex + 1, 0, play = state.playing || state.preloading)
    }

    fun prevChapter() {
        book ?: return
        if (chapterIndex <= 0) return
        moveTo(chapterIndex - 1, 0, play = state.playing || state.preloading)
    }

    fun goToChapter(chapter: Int) =
        moveTo(chapter, 0, play = state.playing || state.preloading)

    fun playFrom(chapter: Int, sentence: Int) = moveTo(chapter, sentence, play = true)

    fun seekTo(chapter: Int, sentence: Int, play: Boolean) = moveTo(chapter, sentence, play)

    fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { main.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes <= 0) {
            state = state.copy(sleepAt = 0L)
            publish()
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        state = state.copy(sleepAt = at)
        publish()
        val r = Runnable {
            sleepRunnable = null
            state = state.copy(sleepAt = 0L)
            pause()
            publish()
        }
        sleepRunnable = r
        main.postDelayed(r, minutes * 60_000L)
    }

    fun sleepAt(): Long = state.sleepAt

    fun cacheBytes(): Long = audio.totalBytes()

    fun clearCache() = audio.clearAll()

    fun cachePath(): String = audio.root.absolutePath

    fun release() {
        paused = true
        ++session
        cancelCurrentSession()
        abandonFocus()
        loader.shutdownNow()
        sleepRunnable?.let { main.removeCallbacks(it) }
    }

    var onActiveChanged: ((Boolean) -> Unit)? = null

    // ---------------- 位置/会话 ----------------

    private fun rebuildChunks(book: Book) {
        val size = settings.chunkChars()
        chunks = SpeechChunker.build(book, size)
        chunkCharsBuilt = size
        weightCache.clear()
    }

    /** 设置页改过块长后，下一次播放/跳转时重新切块并把当前位置映射回去。 */
    private fun ensureChunksFresh() {
        val b = book ?: return
        val size = settings.chunkChars()
        if (size == chunkCharsBuilt && chunks.isNotEmpty()) return
        val oldChar = currentCharOffset()
        rebuildChunks(b)
        val ci = chunks.indexOfFirst {
            it.chapterIndex == chapterIndex && oldChar >= it.startChar && oldChar < it.endChar
        }.let { if (it >= 0) it else chunks.indexOfFirst { it.chapterIndex == chapterIndex } }
        if (ci >= 0) {
            currentChunkIndex = ci
            currentChunkFraction = fractionForChar(b, chunks[ci], oldChar)
        }
    }

    private fun currentCharOffset(): Int {
        val b = book ?: return 0
        val chunk = chunks.getOrNull(currentChunkIndex)
        if (chunk != null) return charForChunkFraction(chunk, currentChunkFraction)
        return b.chapters.getOrNull(chapterIndex)?.sentences?.getOrNull(sentenceIndex)?.start ?: 0
    }

    /** 从旧进度/字符偏移定位到 chunk 与块内比例。 */
    private fun resolveStart(book: Book, chapter: Int, sentence: Int, pos: Position): Pair<Int, Float> {
        if (chunks.isEmpty()) return 0 to 0f
        val ch = book.chapters.getOrNull(chapter)
        val targetChar = when {
            pos.charOffset >= 0 -> pos.charOffset.coerceIn(0, ch?.text?.length ?: 0)
            ch != null -> ch.sentences.getOrNull(sentence)?.start ?: 0
            else -> 0
        }
        val ci = chunks.indexOfFirst {
            it.chapterIndex == chapter && targetChar >= it.startChar && targetChar < it.endChar
        }.let { if (it >= 0) it else chunks.indexOfFirst { it.chapterIndex == chapter } }
        if (ci < 0) return 0 to 0f
        val chunk = chunks[ci]
        val frac = fractionForChar(book, chunk, targetChar)
        return ci to frac.coerceIn(0f, 0.999f)
    }

    private fun chunkForSentence(chapter: Int, sentence: Int): SpeechChunk? =
        chunks.firstOrNull {
            it.chapterIndex == chapter && sentence >= it.startSentence && sentence <= it.endSentence
        }

    private fun fractionForSentence(book: Book, chunk: SpeechChunk, sentence: Int): Float {
        val ch = book.chapters.getOrNull(chunk.chapterIndex) ?: return 0f
        val s = ch.sentences.getOrNull(sentence) ?: return 0f
        return fractionForChar(book, chunk, s.start)
    }

    private fun charForChunkFraction(chunk: SpeechChunk, fraction: Float): Int {
        val w = weightsFor(chunk.chapterIndex)
        val lo = w.getOrElse(chunk.startChar) { 0f }
        val hi = w.getOrElse(chunk.endChar) { lo }
        return weightIndex(w, lo + (hi - lo) * fraction.coerceIn(0f, 1f))
    }

    private fun fractionForChar(book: Book, chunk: SpeechChunk, char: Int): Float {
        val ch = book.chapters.getOrNull(chunk.chapterIndex) ?: return 0f
        val w = weightsFor(chunk.chapterIndex)
        val lo = w.getOrElse(chunk.startChar) { 0f }
        val hi = w.getOrElse(chunk.endChar) { lo }
        if (hi <= lo) return 0f
        val target = w.getOrElse(char.coerceIn(chunk.startChar, chunk.endChar)) { lo }
        return ((target - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    private fun moveTo(chapter: Int, sentence: Int, play: Boolean) {
        val b = book ?: return
        ensureChunksFresh()
        val c = chapter.coerceIn(0, (b.chapters.size - 1).coerceAtLeast(0))
        val ch = b.chapters.getOrNull(c) ?: return
        val s = sentence.coerceIn(0, (ch.sentences.size - 1).coerceAtLeast(0))
        paused = true
        ++session
        cancelCurrentSession()
        saveProgress(force = true)
        chapterIndex = c
        sentenceIndex = s
        sentenceFraction = 0f
        val chunk = chunkForSentence(c, s)
        currentChunkIndex = chunk?.let { chunks.indexOf(it) } ?: 0
        currentChunkFraction = chunk?.let { fractionForSentence(b, it, s) } ?: 0f
        state = state.copy(
            chapterIndex = c,
            chapterTitle = ch.title,
            sentenceIndex = s,
            sentenceCount = ch.sentences.size,
            playing = false,
            preparing = false,
            speaking = false,
            preloading = false,
            buffering = false,
            chunkIndex = currentChunkIndex,
            chunkCount = chunks.size,
            error = null,
        )
        publish()
        notifyHighlight()
        saveProgress(force = true)
        if (play) startSession(currentChunkIndex, currentChunkFraction)
        else onActiveChanged?.invoke(false)
    }

    private fun startSession(startChunk: Int, startFraction: Float) {
        val b = book ?: return
        if (chunks.isEmpty() || startChunk >= chunks.size) {
            finishBook()
            return
        }
        if (!requestFocus()) {
            state = state.copy(error = "无法获取音频焦点")
            publish()
            onActiveChanged?.invoke(false)
            return
        }
        var idx = startChunk
        var frac = startFraction.coerceIn(0f, 1f)
        if (frac >= 0.985f && idx + 1 < chunks.size) {
            idx++
            frac = 0f
        }
        paused = false
        val token = ++session
        val s = Session(token, b.id, idx, frac)
        currentSession = s
        currentChunkIndex = idx
        currentChunkFraction = frac
        val chunk = chunks[idx]
        val ch = b.chapters[chunk.chapterIndex]
        val startChar = charForChunkFraction(chunk, frac)
        val startSentence = ch.sentenceAt(startChar)
        val startSent = ch.sentences.getOrNull(startSentence)
        val startSentFraction = if (startSent != null) {
            val w = weightsFor(chunk.chapterIndex)
            val ws = w.getOrElse(startSent.start) { 0f }
            val we = w.getOrElse(startSent.end) { ws }
            val tw = w.getOrElse(startChar) { ws }
            if (we > ws) ((tw - ws) / (we - ws)).coerceIn(0f, 1f) else 0f
        } else 0f
        chapterIndex = chunk.chapterIndex
        sentenceIndex = startSentence
        sentenceFraction = startSentFraction
        state = state.copy(
            bookId = b.id,
            bookTitle = b.title,
            chapterIndex = chunk.chapterIndex,
            chapterTitle = ch.title,
            chapterCount = b.chapters.size,
            sentenceIndex = startSentence,
            sentenceCount = ch.sentences.size,
            playing = true,
            preparing = true,
            speaking = false,
            preloading = true,
            buffering = false,
            preloadBufferedMs = 0L,
            preloadTargetMs = INITIAL_PRELOAD_MS,
            bufferedAheadMs = 0L,
            chunkIndex = idx,
            chunkCount = chunks.size,
            error = null,
        )
        publish()
        notifyHighlight()
        onActiveChanged?.invoke(true)
        s.prefetchThread = Thread({ prefetchLoop(s) }, "chunk-prefetch").also { it.start() }
        s.feedThread = Thread({ feedLoop(s) }, "chunk-feeder").also { it.start() }
    }

    private fun cancelCurrentSession() {
        val s = currentSession ?: return
        currentSession = null
        s.cancelled = true
        s.canceller?.cancel()
        s.canceller = null
        synchronized(s.lock) { s.lock.notifyAll() }
        s.prefetchThread?.interrupt()
        s.feedThread?.interrupt()
        s.speaker?.cancel()
        player.stop()
        s.archive?.close()
        s.archive = null
    }

    private fun stopPlayback(clearBook: Boolean) {
        paused = true
        ++session
        cancelCurrentSession()
        saveProgress(force = true)
        abandonFocus()
        if (clearBook) {
            book = null
            chunks = emptyList()
            chunkCharsBuilt = -1
            chapterIndex = 0
            sentenceIndex = 0
            currentChunkIndex = 0
            currentChunkFraction = 0f
            state = PlayerState()
            publish()
        }
    }

    // ---------------- 合成线程 ----------------

    private inner class Session(
        val token: Int,
        val bookId: String,
        val startChunk: Int,
        val startFraction: Float,
    ) {
        val lock = Object()
        @Volatile var synthIndex = startChunk
        @Volatile var feedIndex = startChunk
        @Volatile var curChunk = startChunk
        @Volatile var curFraction = startFraction
        @Volatile var bufferAheadMs = 0L
        @Volatile var error: String? = null
        @Volatile var preloadReady = false
        @Volatile var cancelled = false
        val durations = HashMap<Int, Long>()
        val baseFractions = HashMap<Int, Float>()
        @Volatile var canceller: TtsClient.Canceller? = null
        @Volatile var speaker: AudioPlayer.Speaker? = null
        @Volatile var speakerRate = 0
        @Volatile var archive: BookAudioStore.SessionArchive? = null
        @Volatile var archiveModel = ""
        @Volatile var prefetchThread: Thread? = null
        @Volatile var feedThread: Thread? = null
    }

    private fun isCurrent(s: Session): Boolean =
        !s.cancelled && !paused && s.token == session

    private fun targetPreloadMs(): Long =
        settings.preloadMinutes().coerceIn(1, 60) * 60_000L

    private fun prefetchLoop(s: Session) {
        try {
            while (isCurrent(s)) {
                val idx = s.synthIndex
                if (idx >= chunks.size) {
                    synchronized(s.lock) { s.lock.notifyAll() }
                    return
                }
                if (idx > s.startChunk) {
                    val wait = synchronized(s.lock) {
                        while (isCurrent(s) && s.bufferAheadMs >= targetPreloadMs() && s.bufferAheadMs > 0) {
                            s.lock.wait(400)
                        }
                        !isCurrent(s)
                    }
                    if (wait) return
                }
                val chunk = chunks[idx]
                val entry = fetchChunk(s, idx, chunk) ?: return
                synchronized(s.lock) {
                    s.durations[idx] = entry.info.durationMs.takeIf { it > 0 }
                        ?: estimateChunkMs(chunk)
                    s.synthIndex = idx + 1
                    s.bufferAheadMs = computeBuffer(s)
                    s.lock.notifyAll()
                }
                publishPreload(s)
            }
        } catch (e: Exception) {
            if (isCurrent(s)) fail(s, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun feedLoop(s: Session) {
        try {
            // 先等一小段音频，避免一开口就 underrun；之后预取会在后台继续跑。
            while (isCurrent(s) && !s.preloadReady) {
                val enough = synchronized(s.lock) {
                    s.bufferAheadMs >= INITIAL_PRELOAD_MS ||
                        s.synthIndex >= chunks.size ||
                        (s.synthIndex > s.startChunk && s.bufferAheadMs > 0 &&
                            s.bufferAheadMs >= min(INITIAL_PRELOAD_MS, 6_000L))
                }
                if (enough || s.error != null) {
                    s.preloadReady = true
                    break
                }
                synchronized(s.lock) { s.lock.wait(200) }
            }
            if (!isCurrent(s)) return
            s.preloadReady = true
            main.post {
                if (!isCurrent(s)) return@post
                state = state.copy(
                    playing = true,
                    preparing = false,
                    preloading = false,
                    buffering = false,
                    preloadBufferedMs = s.bufferAheadMs,
                    bufferedAheadMs = s.bufferAheadMs,
                )
                publish()
            }

            while (isCurrent(s)) {
                // 最多只把当前播放块之后的 1 块推进播放通道，避免大块把内存撑爆。
                synchronized(s.lock) {
                    while (isCurrent(s) && s.feedIndex - s.curChunk >= 2) {
                        s.lock.wait(300)
                    }
                }
                if (!isCurrent(s)) return
                val idx = s.feedIndex
                if (idx >= chunks.size) break
                synchronized(s.lock) {
                    while (isCurrent(s) && idx >= s.synthIndex && s.error == null) {
                        s.lock.wait(250)
                    }
                }
                if (!isCurrent(s)) return
                s.error?.let { throw IllegalStateException(it) }
                val chunk = chunks[idx]
                val entry = getCached(s, idx, chunk)
                    ?: throw IllegalStateException("音频块未就绪")
                appendArchive(s, entry)
                if (AudioMeta.isPcm(entry.contentType)) {
                    ensureSpeaker(s, entry)
                    val speaker = s.speaker ?: throw IllegalStateException("播放通道未就绪")
                    var data = AudioMeta.pcmBytes(entry.contentType, entry.bytes)
                    var base = 0f
                    if (idx == s.startChunk && s.startFraction > 0.001f) {
                        val frame = (entry.info.channels * (entry.info.bits / 8)).coerceAtLeast(2)
                        var off = (data.size.toLong() * s.startFraction).toInt()
                        off = (off / frame) * frame
                        off = off.coerceIn(0, (data.size / frame) * frame)
                        data = data.copyOfRange(off, data.size)
                        base = s.startFraction
                    }
                    s.baseFractions[idx] = base
                    if (!speaker.push(idx, data, true, data.size)) return
                } else {
                    playCompressedChunk(s, idx, entry)
                    if (!isCurrent(s)) return
                }
                s.feedIndex = idx + 1
                synchronized(s.lock) {
                    s.bufferAheadMs = computeBuffer(s)
                    s.lock.notifyAll()
                }
                publishPreload(s)
            }
            if (!isCurrent(s)) return
            s.speaker?.finish()
            if (isCurrent(s)) finishBook()
        } catch (e: Exception) {
            if (isCurrent(s)) fail(s, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fetchChunk(s: Session, idx: Int, chunk: SpeechChunk): BookAudioStore.Entry? {
        val b = book ?: return null
        val text = chunk.text(b).trim()
        if (text.isEmpty()) return BookAudioStore.Entry(
            "audio/pcm;rate=24000;channels=1", ByteArray(0),
            AudioMeta.inspect("audio/pcm;rate=24000;channels=1", ByteArray(0))
        )
        val cfg = settings.tts()
        if (!cfg.configured) throw IllegalStateException("请先在设置里配置 TTS 服务")
        val key = audio.chunkKey(cfg.baseUrl, cfg.model, cfg.voice, text)
        audio.get(s.bookId, key)?.let { return it }
        if (!isCurrent(s)) return null
        val canceller = TtsClient.Canceller()
        s.canceller = canceller
        try {
            val out = TtsClient.synthesize(
                base = cfg.baseUrl,
                key = cfg.apiKey,
                model = cfg.model,
                voice = cfg.voice,
                text = text,
                speed = cfg.speed,
                readTimeoutMs = timeoutFor(cfg.timeoutSec, text.length),
                canceller = canceller,
            )
            if (!isCurrent(s)) return null
            val info = AudioMeta.inspect(out.contentType, out.bytes)
            audio.put(
                s.bookId, key, out.contentType, out.bytes,
                BookAudioStore.ChunkMeta(
                    contentType = out.contentType,
                    durationMs = info.durationMs,
                    sampleRate = info.sampleRate,
                    channels = info.channels,
                    bits = info.bits,
                    pcm = info.pcm,
                    chapterIndex = chunk.chapterIndex,
                    indexInChapter = chunk.indexInChapter,
                    startChar = chunk.startChar,
                    endChar = chunk.endChar,
                ),
            )
            return audio.get(s.bookId, key)
                ?: BookAudioStore.Entry(out.contentType, out.bytes, info)
        } finally {
            s.canceller = null
        }
    }

    private fun getCached(s: Session, idx: Int, chunk: SpeechChunk): BookAudioStore.Entry? {
        val b = book ?: return null
        val cfg = settings.tts()
        val key = audio.chunkKey(cfg.baseUrl, cfg.model, cfg.voice, chunk.text(b).trim())
        return audio.get(s.bookId, key)
    }

    private fun ensureSpeaker(s: Session, entry: BookAudioStore.Entry) {
        val rate = entry.info.sampleRate.takeIf { it in 8000..192000 } ?: 24000
        if (s.speaker != null && s.speakerRate == rate) return
        s.speaker?.cancel()
        s.speakerRate = rate
        s.speaker = player.speaker(
            sampleRate = rate,
            onLevel = {},
            onStarted = {
                main.post {
                    if (!isCurrent(s)) return@post
                    state = state.copy(playing = true, preparing = false, preloading = false)
                    publish()
                }
            },
            onProgress = { seg, frac -> onChunkProgress(s, seg, frac) },
            onDone = {},
            speed = settings.tts().speed,
            onBuffering = { buf ->
                main.post {
                    if (!isCurrent(s)) return@post
                    if (state.buffering != buf) {
                        state = state.copy(buffering = buf)
                        publish()
                    }
                }
            },
        )
    }

    private fun playCompressedChunk(s: Session, idx: Int, entry: BookAudioStore.Entry) {
        var base = 0f
        if (idx == s.startChunk) base = s.startFraction
        s.baseFractions[idx] = base
        player.play(
            contentType = entry.contentType,
            bytes = entry.bytes,
            onLevel = {},
            onProgress = { played, total ->
                if (total > 0) {
                    onChunkProgress(s, idx, (played.toFloat() / total).coerceIn(0f, 1f))
                }
            },
            onDone = {},
            speed = settings.tts().speed,
        )
    }

    private fun onChunkProgress(s: Session, seg: Int, frac: Float) {
        if (!isCurrent(s)) return
        val b = book ?: return
        val chunk = chunks.getOrNull(seg) ?: return
        val base = s.baseFractions[seg] ?: 0f
        val effective = (base + frac.coerceIn(0f, 1f) * (1f - base)).coerceIn(0f, 1f)
        val ch = b.chapters.getOrNull(chunk.chapterIndex) ?: return
        val w = weightsFor(chunk.chapterIndex)
        val lo = w.getOrElse(chunk.startChar) { 0f }
        val hi = w.getOrElse(chunk.endChar) { lo }
        val targetWeight = lo + (hi - lo) * effective
        val targetChar = weightIndex(w, targetWeight).coerceIn(0, ch.text.length)
        val si = ch.sentenceAt(targetChar)
        val sent = ch.sentences.getOrNull(si)
        val sf = if (sent != null) {
            val ws = w.getOrElse(sent.start) { targetWeight }
            val we = w.getOrElse(sent.end) { ws }
            if (we > ws) ((targetWeight - ws) / (we - ws)).coerceIn(0f, 1f) else 0f
        } else 0f

        chapterIndex = chunk.chapterIndex
        sentenceIndex = si
        sentenceFraction = sf
        currentChunkIndex = seg
        currentChunkFraction = effective
        s.curChunk = seg
        s.curFraction = effective
        synchronized(s.lock) {
            s.bufferAheadMs = computeBuffer(s)
            s.lock.notifyAll()
        }
        main.post {
            if (!isCurrent(s)) return@post
            val changed = state.chapterIndex != chunk.chapterIndex ||
                state.sentenceIndex != si || state.chunkIndex != seg
            if (changed) {
                state = state.copy(
                    chapterIndex = chunk.chapterIndex,
                    chapterTitle = ch.title,
                    sentenceIndex = si,
                    sentenceCount = ch.sentences.size,
                    chunkIndex = seg,
                    chunkCount = chunks.size,
                )
                publish()
            }
            listeners.forEach { it.onHighlight(chunk.chapterIndex, si, sf) }
            val now = System.currentTimeMillis()
            if (now - lastSaved > 4000L) {
                lastSaved = now
                saveProgress(force = false)
            }
        }
    }

    private fun computeBuffer(s: Session): Long {
        var ms = 0L
        val cur = s.curChunk
        if (cur < s.synthIndex) {
            val dur = durationOf(s, cur)
            ms += ((1f - s.curFraction.coerceIn(0f, 1f)) * dur).toLong()
        }
        for (i in (cur + 1) until s.synthIndex) ms += durationOf(s, i)
        return ms.coerceAtLeast(0L)
    }

    private fun durationOf(s: Session, idx: Int): Long =
        s.durations[idx] ?: chunks.getOrNull(idx)?.let { estimateChunkMs(it) } ?: 0L

    private fun estimateChunkMs(chunk: SpeechChunk): Long {
        val len = (chunk.endChar - chunk.startChar).coerceAtLeast(0)
        return max(800L, len * 90L)
    }

    private fun publishPreload(s: Session) {
        main.post {
            if (!isCurrent(s)) return@post
            state = state.copy(
                preloadBufferedMs = s.bufferAheadMs,
                bufferedAheadMs = s.bufferAheadMs,
                preloadTargetMs = if (!s.preloadReady) INITIAL_PRELOAD_MS else targetPreloadMs(),
                chunkIndex = s.curChunk,
                chunkCount = chunks.size,
            )
            publish()
        }
    }

    private fun appendArchive(s: Session, entry: BookAudioStore.Entry) {
        val cfg = settings.tts()
        val model = cfg.model.ifBlank { "unknown" }
        var ar = s.archive
        if (ar == null || s.archiveModel != model || !ar.compatible(entry)) {
            ar?.close()
            ar = audio.newArchive(s.bookId, model, book?.title.orEmpty(), entry)
            s.archive = ar
            s.archiveModel = model
        }
        ar.append(entry)
    }

    private fun timeoutFor(configSec: Int, chars: Int): Int {
        val byChars = 30_000L + chars.toLong() * 150L
        return max(configSec * 1000L, byChars).coerceAtMost(900_000L).toInt()
    }

    private fun fail(s: Session, message: String) {
        main.post {
            if (s.token != session) return@post
            paused = true
            ++session
            cancelCurrentSession()
            state = state.copy(
                playing = false, preparing = false, speaking = false,
                preloading = false, buffering = false, error = message,
            )
            publish()
            saveProgress(force = true)
            onActiveChanged?.invoke(false)
        }
    }

    private fun finishBook() {
        paused = true
        ++session
        cancelCurrentSession()
        chapterIndex = (book?.chapters?.size ?: 1).coerceAtLeast(1) - 1
        sentenceIndex = book?.chapters?.getOrNull(chapterIndex)?.sentences?.lastIndex?.coerceAtLeast(0) ?: 0
        val b = book
        if (b != null) {
            store.saveProgress(
                b.id,
                Position(
                    chapterIndex, sentenceIndex, 1f, System.currentTimeMillis(), 100,
                    charOffset = b.chapters.getOrNull(chapterIndex)?.text?.length ?: 0,
                ),
            )
        }
        abandonFocus()
        state = state.copy(
            playing = false, preparing = false, speaking = false,
            preloading = false, buffering = false, preloadBufferedMs = 0L,
        )
        publish()
        listeners.forEach { it.onFinished() }
        onActiveChanged?.invoke(false)
    }

    // ---------------- 进度/权重 ----------------

    private fun weightsFor(chapter: Int): FloatArray =
        weightCache.getOrPut(chapter) {
            val text = book?.chapters?.getOrNull(chapter)?.text.orEmpty()
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
            cum
        }

    private fun weightIndex(cum: FloatArray, target: Float): Int {
        val n = cum.size - 1
        if (n <= 0) return 0
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun saveProgress(force: Boolean) {
        val b = book ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 3500L) return
        lastSaved = now
        val chunk = chunks.getOrNull(currentChunkIndex)
        val ch = b.chapters.getOrNull(chapterIndex)
        val charOffset = if (chunk != null && ch != null) {
            val w = weightsFor(chunk.chapterIndex)
            val lo = w.getOrElse(chunk.startChar) { 0f }
            val hi = w.getOrElse(chunk.endChar) { lo }
            val target = lo + (hi - lo) * currentChunkFraction
            weightIndex(w, target)
        } else ch?.sentences?.getOrNull(sentenceIndex)?.start ?: 0
        store.saveProgress(
            b.id,
            Position(
                chapter = chapterIndex,
                sentence = sentenceIndex,
                fraction = sentenceFraction,
                updatedAt = now,
                percent = b.percent(chapterIndex, sentenceIndex),
                charOffset = charOffset,
            ),
        )
    }

    private fun notifyHighlight() {
        val c = chapterIndex
        val s = sentenceIndex
        val f = sentenceFraction
        listeners.forEach { it.onHighlight(c, s, f) }
    }

    private fun publish() {
        val snap = state
        listeners.forEach { it.onState(snap) }
    }

    // ---------------- 音频焦点 ----------------

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    companion object {
        /** 开口前至少缓冲这么多；预取目标在后台继续跑。 */
        private const val INITIAL_PRELOAD_MS = 20_000L
    }
}
