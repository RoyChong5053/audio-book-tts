package com.roychong.audiobook.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.roychong.audiobook.AppSettings
import com.roychong.audiobook.TtsConfig
import com.roychong.audiobook.book.Book
import com.roychong.audiobook.book.BookStore
import com.roychong.audiobook.book.Position
import com.roychong.audiobook.tts.TtsCache
import com.roychong.audiobook.tts.TtsClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 朗读控制器：把「书名/章节/句子」变成 TTS 请求，再交给 AudioPlayer 的连续通道播放。
 *
 * 线程模型：
 *  - 对外方法（play/pause/seek…）都在主线程调用；
 *  - 合成跑在单独的 producer 线程里，只通过 [post] 回主线程更新状态；
 *  - AudioPlayer 的播放进度来自 AudioTrack 的实际播放位置，所以 KTV 高亮跟耳朵一致。
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
    private val loader = Executors.newSingleThreadExecutor()

    private var cache = TtsCache(app, settings.cacheCapMb())

    @Volatile private var book: Book? = null
    @Volatile private var chapterIndex = 0
    @Volatile private var sentenceIndex = 0
    @Volatile private var fraction = 0f
    @Volatile private var session = 0
    @Volatile private var paused = true
    @Volatile private var currentCanceller: TtsClient.Canceller? = null
    @Volatile private var speaker: AudioPlayer.Speaker? = null
    @Volatile private var mode = 0              // 0 未知 / 1 PCM / 2 压缩
    @Volatile private var speakerRate = 24000
    @Volatile private var lastSaved = 0L
    @Volatile private var openToken = 0
    @Volatile private var state = PlayerState()

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
        return b.chapters.getOrNull(chapterIndex)
            ?.sentences?.getOrNull(sentenceIndex)?.text.orEmpty()
    }

    fun currentChapter(): com.roychong.audiobook.book.Chapter? =
        book?.chapters?.getOrNull(chapterIndex)

    fun openBook(bookId: String, chapter: Int = -1, sentence: Int = -1, autoPlay: Boolean = false) {
        val b = book
        if (b != null && b.id == bookId) {
            // 已经在读这本：只同步位置，不打断正在播放的音频
            if (chapter >= 0) {
                val c = chapter.coerceIn(0, (b.chapters.size - 1).coerceAtLeast(0))
                val s = sentence.coerceIn(0, (b.chapters.getOrNull(c)?.sentences?.size?.minus(1) ?: 0).coerceAtLeast(0))
                if (state.playing && (c != chapterIndex || s != sentenceIndex)) {
                    seekTo(c, s, play = true)
                } else if (!state.playing) {
                    chapterIndex = c
                    sentenceIndex = s
                    fraction = 0f
                    publishState(playing = false, preparing = false, speaking = false, error = null)
                    notifyHighlight()
                }
            }
            if (autoPlay) play()
            return
        }
        val token = ++openToken
        stopInternal(clearBook = true)
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
                val c = (if (chapter >= 0) chapter else pos.chapter)
                    .coerceIn(0, (loaded.chapters.size - 1).coerceAtLeast(0))
                val maxS = (loaded.chapters.getOrNull(c)?.sentences?.size?.minus(1) ?: 0).coerceAtLeast(0)
                val s = (if (sentence >= 0) sentence else pos.sentence).coerceIn(0, maxS)
                chapterIndex = c
                sentenceIndex = s
                fraction = if (chapter < 0 && sentence < 0) pos.fraction else 0f
                settings.saveLastBookId(bookId)
                state = PlayerState(
                    bookId = loaded.id, bookTitle = loaded.title,
                    chapterIndex = c, chapterTitle = loaded.chapters[c].title,
                    chapterCount = loaded.chapters.size,
                    sentenceIndex = s, sentenceCount = loaded.chapters[c].sentences.size,
                    playing = false, preparing = false, speaking = false,
                    sleepAt = state.sleepAt,
                )
                publish()
                notifyHighlight()
                if (autoPlay) startPlayback()
            }
        }
    }

    fun play() {
        if (book == null) {
            settings.lastBookId()?.let { openBook(it, autoPlay = true) }
            return
        }
        if (state.playing || state.preparing) return
        startPlayback()
    }

    fun pause() {
        if (!state.playing && !state.preparing) return
        paused = true
        ++session
        currentCanceller?.cancel()
        currentCanceller = null
        speaker?.cancel()
        speaker = null
        player.stop()
        mode = 0
        saveProgress(force = true)
        abandonFocus()
        publishState(playing = false, preparing = false, speaking = false)
        notifyHighlight()
        onActiveChanged?.invoke(false)
    }

    fun toggle() {
        if (state.playing || state.preparing) pause() else play()
    }

    fun stop() {
        stopInternal(clearBook = false)
        publishState(playing = false, preparing = false, speaking = false)
        abandonFocus()
        onActiveChanged?.invoke(false)
    }

    fun nextSentence() {
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapterIndex) ?: return
        var c = chapterIndex
        var s = sentenceIndex + 1
        if (s >= ch.sentences.size) {
            c += 1
            s = 0
        }
        if (c >= b.chapters.size) return
        moveTo(c, s, play = state.playing || state.preparing)
    }

    fun prevSentence() {
        val b = book ?: return
        var c = chapterIndex
        var s = sentenceIndex - 1
        if (s < 0) {
            c -= 1
            if (c < 0) return
            s = (b.chapters[c].sentences.size - 1).coerceAtLeast(0)
        }
        moveTo(c, s, play = state.playing || state.preparing)
    }

    fun nextChapter() {
        val b = book ?: return
        if (chapterIndex + 1 >= b.chapters.size) return
        moveTo(chapterIndex + 1, 0, play = state.playing || state.preparing)
    }

    fun prevChapter() {
        book ?: return
        if (chapterIndex <= 0) return
        moveTo(chapterIndex - 1, 0, play = state.playing || state.preparing)
    }

    /** 从目录跳章；正在播就继续播。 */
    fun goToChapter(chapter: Int) = moveTo(chapter, 0, play = state.playing || state.preparing)

    /** 从某一句开始朗读（长按文字）。 */
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

    /** 缓存对象的容量可能被设置页改过。 */
    fun refreshCache() {
        cache = TtsCache(app, settings.cacheCapMb())
    }

    fun cacheBytes(): Long = cache.totalBytes()

    fun clearCache() = cache.clear()

    fun release() {
        stopInternal(clearBook = true)
        abandonFocus()
        loader.shutdownNow()
        sleepRunnable?.let { main.removeCallbacks(it) }
    }

    // 由 Service 注入：播放/暂停时申请释放前台状态
    var onActiveChanged: ((Boolean) -> Unit)? = null

    // ---------------- 内部：位置变化 ----------------

    private fun moveTo(chapter: Int, sentence: Int, play: Boolean) {
        val b = book ?: return
        val c = chapter.coerceIn(0, (b.chapters.size - 1).coerceAtLeast(0))
        val ch = b.chapters.getOrNull(c) ?: return
        val s = sentence.coerceIn(0, (ch.sentences.size - 1).coerceAtLeast(0))
        stopPlaybackOnly()
        chapterIndex = c
        sentenceIndex = s
        fraction = 0f
        state = state.copy(
            chapterIndex = c, chapterTitle = ch.title,
            sentenceIndex = s, sentenceCount = ch.sentences.size,
            playing = false, preparing = false, speaking = false, error = null,
        )
        publish()
        notifyHighlight()
        saveProgress(force = true)
        if (play) startPlayback() else onActiveChanged?.invoke(false)
    }

    private fun startPlayback() {
        val b = book ?: return
        val ch = b.chapters.getOrNull(chapterIndex) ?: return
        if (ch.sentences.isEmpty()) {
            if (chapterIndex + 1 < b.chapters.size) moveTo(chapterIndex + 1, 0, play = true)
            return
        }
        if (!requestFocus()) {
            publishState(playing = false, error = "无法获取音频焦点")
            onActiveChanged?.invoke(false)
            return
        }
        val start = sentenceIndex.coerceIn(0, ch.sentences.size - 1)
        paused = false
        val token = ++session
        mode = 0
        speaker = null
        publishState(playing = true, preparing = true, speaking = false, error = null,
            sentenceIndex = start, sentenceCount = ch.sentences.size,
            chapterIndex = chapterIndex, chapterTitle = ch.title)
        notifyHighlight()
        onActiveChanged?.invoke(true)
        Thread({ producer(token, b, chapterIndex, start) }, "reading-producer").start()
    }

    /** 只停止声音和 producer，不动当前书/位置。 */
    private fun stopPlaybackOnly() {
        paused = true
        ++session
        currentCanceller?.cancel()
        currentCanceller = null
        speaker?.cancel()
        speaker = null
        player.stop()
        mode = 0
    }

    private fun stopInternal(clearBook: Boolean) {
        stopPlaybackOnly()
        saveProgress(force = true)
        abandonFocus()
        if (clearBook) {
            book = null
            chapterIndex = 0
            sentenceIndex = 0
            fraction = 0f
            state = PlayerState()
            publish()
        }
    }

    // ---------------- 内部：合成/播放 ----------------

    private fun producer(token: Int, b: Book, chIdx: Int, start: Int) {
        val chapter = b.chapters.getOrNull(chIdx) ?: return
        var first = true
        try {
            for (i in start until chapter.sentences.size) {
                if (!isCurrent(token)) return
                val text = chapter.sentences[i].text.trim()
                if (text.isEmpty()) continue
                main.post {
                    if (!isCurrent(token)) return@post
                    sentenceIndex = i
                    fraction = 0f
                    state = state.copy(sentenceIndex = i, preparing = first, speaking = state.speaking)
                    publish()
                    notifyHighlight()
                }
                val audio = fetchAudio(token, text) ?: return
                if (!isCurrent(token)) return
                ensureOutput(token, audio)
                if (!isCurrent(token)) return

                if (mode == 2) {
                    // mp3/aac：按段交给 MediaPlayer，段间不可避免有一点间隙
                    val latch = CountDownLatch(1)
                    player.play(
                        contentType = audio.contentType,
                        bytes = audio.bytes,
                        onLevel = {},
                        onProgress = { played, total ->
                            if (total > 0) reportProgress(token, i, played.toFloat() / total)
                        },
                        onStarted = {
                            main.post {
                                if (!isCurrent(token)) return@post
                                state = state.copy(preparing = false, speaking = true, playing = true)
                                publish()
                            }
                        },
                        onDone = { latch.countDown() },
                        speed = settings.tts().speed,
                    )
                    while (isCurrent(token) && latch.count > 0L) {
                        runCatching { latch.await(200, TimeUnit.MILLISECONDS) }
                    }
                    if (!isCurrent(token)) return
                    reportProgress(token, i, 1f)
                } else {
                    val sp = speaker ?: return
                    val pcm = pcmData(audio.bytes, audio.contentType)
                    if (pcm.isEmpty()) continue
                    val pushed = sp.push(i, pcm, true, pcm.size)
                    if (!pushed) return
                    first = false
                }
            }
            if (!isCurrent(token)) return
            if (mode == 1) speaker?.finish()
            if (!isCurrent(token)) return
            main.post { if (isCurrent(token)) onChapterFinished(token, b, chIdx) }
        } catch (e: Exception) {
            if (!isCurrent(token)) return
            val msg = e.message ?: e.javaClass.simpleName
            main.post {
                if (!isCurrent(token)) return@post
                paused = true
                ++session
                speaker = null
                player.stop()
                state = state.copy(
                    playing = false, preparing = false, speaking = false,
                    error = msg,
                )
                publish()
                saveProgress(force = true)
                onActiveChanged?.invoke(false)
            }
        }
    }

    private fun ensureOutput(token: Int, audio: TtsCache.Entry) {
        val pcm = isPcm(audio.contentType)
        if (mode == 0) {
            if (pcm) {
                mode = 1
                speakerRate = pcmRate(audio.contentType, audio.bytes)
                speaker = newSpeaker(token, speakerRate)
            } else {
                mode = 2
            }
        } else if (mode == 1 && pcm) {
            val rate = pcmRate(audio.contentType, audio.bytes)
            if (rate != speakerRate) {
                // 极少数服务端会换采样率；重开通道，避免变速/爆音
                speaker?.cancel()
                speakerRate = rate
                speaker = newSpeaker(token, rate)
            }
        }
    }

    private fun newSpeaker(token: Int, rate: Int): AudioPlayer.Speaker {
        return player.speaker(
            sampleRate = rate,
            onLevel = {},
            onStarted = {
                main.post {
                    if (!isCurrent(token)) return@post
                    state = state.copy(preparing = false, speaking = true, playing = true)
                    publish()
                }
            },
            onProgress = { seg, frac -> reportProgress(token, seg, frac) },
            onDone = {},
            speed = settings.tts().speed,
        )
    }

    private fun fetchAudio(token: Int, text: String): TtsCache.Entry? {
        val cfg = settings.tts()
        if (!cfg.configured) throw IllegalStateException("请先在设置里配置 TTS 服务")
        if (cache.maxMb != settings.cacheCapMb()) cache = TtsCache(app, settings.cacheCapMb())
        val key = cache.key("${cfg.baseUrl}|${cfg.model}", cfg.voice, 1f, text)
        cache.get(key)?.let { return it }
        if (!isCurrent(token)) return null
        val canceller = TtsClient.Canceller()
        currentCanceller = canceller
        return try {
            val audio = TtsClient.synthesize(
                base = cfg.baseUrl,
                key = cfg.apiKey,
                model = cfg.model,
                voice = cfg.voice,
                text = text,
                speed = cfg.speed,
                readTimeoutMs = cfg.timeoutSec * 1000,
                canceller = canceller,
            )
            if (!isCurrent(token)) return null
            cache.put(key, audio.contentType, audio.bytes)
            TtsCache.Entry(audio.contentType, audio.bytes)
        } finally {
            currentCanceller = null
        }
    }

    private fun onChapterFinished(token: Int, b: Book, chIdx: Int) {
        if (!isCurrent(token)) return
        saveProgress(force = true)
        if (chIdx + 1 < b.chapters.size) {
            chapterIndex = chIdx + 1
            sentenceIndex = 0
            fraction = 0f
            val next = b.chapters[chapterIndex]
            state = state.copy(
                chapterIndex = chapterIndex, chapterTitle = next.title,
                sentenceIndex = 0, sentenceCount = next.sentences.size,
                playing = false, preparing = false, speaking = false,
            )
            publish()
            notifyHighlight()
            startPlayback()
        } else {
            paused = true
            state = state.copy(playing = false, preparing = false, speaking = false)
            publish()
            listeners.forEach { it.onFinished() }
            onActiveChanged?.invoke(false)
        }
    }

    private fun reportProgress(token: Int, seg: Int, frac: Float) {
        main.post {
            if (!isCurrent(token)) return@post
            val f = frac.coerceIn(0f, 1f)
            val changed = seg != sentenceIndex || abs(f - fraction) > 0.005f
            sentenceIndex = seg
            fraction = f
            if (state.sentenceIndex != seg) {
                state = state.copy(sentenceIndex = seg)
                publish()
            }
            if (changed) listeners.forEach { it.onHighlight(chapterIndex, seg, f) }
            val now = System.currentTimeMillis()
            if (now - lastSaved > 4000L) {
                lastSaved = now
                saveProgress(force = false)
            }
        }
    }

    private fun saveProgress(force: Boolean) {
        val b = book ?: return
        if (!force && b.id.isEmpty()) return
        store.saveProgress(
            b.id,
            Position(
                chapterIndex, sentenceIndex, fraction, System.currentTimeMillis(),
                percent = b.percent(chapterIndex, sentenceIndex),
            ),
        )
    }

    private fun isCurrent(token: Int): Boolean = token == session && !paused

    // ---------------- 音频格式 ----------------

    private fun isPcm(ct: String): Boolean {
        val t = ct.lowercase()
        return t.contains("pcm") || t.contains("l16") || t.contains("wav") || t.contains("wave")
    }

    private fun pcmRate(ct: String, bytes: ByteArray): Int {
        Regex("rate=(\\d+)").find(ct)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        // WAV：从 fmt 块读采样率
        if (ct.contains("wav") || (bytes.size > 12 && bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte())
        ) {
            var p = 12
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4, Charsets.US_ASCII)
                val size = (bytes[p + 4].toInt() and 0xff) or
                    ((bytes[p + 5].toInt() and 0xff) shl 8) or
                    ((bytes[p + 6].toInt() and 0xff) shl 16) or
                    ((bytes[p + 7].toInt() and 0xff) shl 24)
                if (id == "fmt " && p + 16 <= bytes.size) {
                    val r = (bytes[p + 12].toInt() and 0xff) or
                        ((bytes[p + 13].toInt() and 0xff) shl 8) or
                        ((bytes[p + 14].toInt() and 0xff) shl 16) or
                        ((bytes[p + 15].toInt() and 0xff) shl 24)
                    if (r in 8000..192000) return r
                }
                p += 8 + size + (size and 1)
            }
        }
        return 24000
    }

    private fun pcmData(bytes: ByteArray, ct: String): ByteArray {
        val isWav = ct.contains("wav") || (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte())
        if (!isWav) return bytes
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = (bytes[p + 4].toInt() and 0xff) or
                ((bytes[p + 5].toInt() and 0xff) shl 8) or
                ((bytes[p + 6].toInt() and 0xff) shl 16) or
                ((bytes[p + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                val start = (p + 8).coerceAtMost(bytes.size)
                return bytes.copyOfRange(start, bytes.size)
            }
            p += 8 + size + (size and 1)
        }
        return bytes
    }

    // ---------------- 状态/焦点 ----------------

    private fun publishState(
        playing: Boolean = state.playing,
        preparing: Boolean = state.preparing,
        speaking: Boolean = state.speaking,
        error: String? = state.error,
        chapterIndex: Int = this.chapterIndex,
        chapterTitle: String = book?.chapters?.getOrNull(chapterIndex)?.title.orEmpty(),
        sentenceIndex: Int = this.sentenceIndex,
        sentenceCount: Int = book?.chapters?.getOrNull(chapterIndex)?.sentences?.size ?: 0,
    ) {
        val b = book
        state = state.copy(
            bookId = b?.id ?: state.bookId,
            bookTitle = b?.title ?: state.bookTitle,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            chapterCount = b?.chapters?.size ?: state.chapterCount,
            sentenceIndex = sentenceIndex,
            sentenceCount = sentenceCount,
            playing = playing,
            preparing = preparing,
            speaking = speaking,
            error = error,
            loading = false,
        )
        publish()
    }

    private fun publish() {
        val s = state
        listeners.forEach { it.onState(s) }
    }

    private fun notifyHighlight() {
        val c = chapterIndex
        val s = sentenceIndex
        val f = fraction
        listeners.forEach { it.onHighlight(c, s, f) }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

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
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
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
}
