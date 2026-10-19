package com.roychong.audiobook.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 播放 TTS 返回的音频。
 *
 * OpenRouter 的 TTS 返回的是**原始 PCM**（`audio/pcm;rate=24000;channels=1`），
 * 不是 MP3，所以优先走 AudioTrack；万一是 mp3 之类就退回 MediaPlayer。
 *
 * 播放过程中做两件事：
 *  - 把音频能量回馈给渲染层，让球体跟着 AI 的声音跳；
 *  - 上报播放进度，给界面的 KTV 字幕用。
 *
 * **进程内单路播放**：这台手机上曾经出现过两段语音同时出声（两个 Activity 实例
 * 各有一个 player，旧实例的慢 TTS 合成完时新实例正在重播）。所以这里把"当前正在
 * 播放的那一路"放在 companion 里：新播放开始前会先掐掉旧的，任何情况下只有一路声音。
 *
 * 两种播放入口：
 *  - [play] 一次给一整段字节（重放、语音库、mp3 回退）；
 *  - [speaker] 开一条**跨片段不释放**的连续通道（逐句流式播报用），
 *    合成线程边算边 [Speaker.push]，播放线程按实时速度消费。
 */
class AudioPlayer(private val context: Context) {

    /** 一次播放的句柄：可由任意线程 cancel（另一个 player 开始播、或用户按停止）。 */
    private class Playback {
        @Volatile var cancelled = false
        @Volatile var track: AudioTrack? = null
        @Volatile var mp: MediaPlayer? = null

        /** 额外的手动清理（连续通道要用它唤醒阻塞在队列上的喂数线程）。 */
        @Volatile var onCancel: (() -> Unit)? = null

        fun cancel() {
            cancelled = true
            onCancel?.invoke()
            onCancel = null
            val t = track
            track = null
            if (t != null) {
                runCatching { t.pause() }
                runCatching { t.flush() }
                runCatching { t.stop() }
                runCatching { t.release() }
            }
            val m = mp
            mp = null
            if (m != null) {
                runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }

    /**
     * 连续通道里的一块音频。（嵌套类不能声明在 inner class 里，所以放在这一层。）
     *
     * @param last 这一片段的最后一块；此时 [segmentTotal] 才有意义
     * @param segmentTotal 该片段的音频总字节数（0 = 还不知道）
     */
    private class Chunk(
        val segment: Int,
        val bytes: ByteArray,
        val last: Boolean,
        val segmentTotal: Int,
    )

    /** 一段音频在「已写入帧」坐标里的位置，用来把播放位置反查成片段进度。 */
    private class Mark(val segment: Int, val startFrame: Int) {
        var totalFrames = 0
    }

    /**
     * 连续播放通道：一个 AudioTrack + 一个喂数线程，**跨片段不释放**。
     *
     * 为什么不能直接用 [play] 一段一段播：`play` 每次都会把"上一路"掐掉
     * （进程内单路互斥），连着调就是每句都把前一句打断。所以逐句播报必须
     * 有一条常驻通道，片段之间只换数据、不换播放器，也就没有段间咔哒和
     * 重开 AudioTrack 的延迟。
     *
     * 背压：内部队列有界。[push] 在队列满时会阻塞 —— 合成跑得比播放快时，
     * 合成线程自然停在 `push` 上等播放追上，不会把整篇音频堆在内存里。
     */
    inner class Speaker internal constructor(
        private val sampleRate: Int,
        private val speed: Float,
        private val onLevel: (Spectrum.Bands) -> Unit,
        private val onStarted: () -> Unit,
        private val onProgress: (segment: Int, fraction: Float) -> Unit,
        private val onDone: () -> Unit,
    ) {
        private val queue = LinkedBlockingQueue<Chunk>(QUEUE_CHUNKS)
        private val pill = Chunk(-1, ByteArray(0), true, 0)

        /** 队列里还压着多少字节（用来做「预垫」判断）。 */
        private val queuedBytes = java.util.concurrent.atomic.AtomicInteger()

        @Volatile private var cancelled = false
        @Volatile private var thread: Thread? = null
        @Volatile private var done = false

        /** 所有片段都已经喂完（[finish] 被调用）。 */
        @Volatile private var allPushed = false
        private val pb = Playback()

        internal fun start() {
            // 注册成"当前这一路"，顺带掐掉上一条 —— 和 play() 用的是同一套互斥
            pb.onCancel = {
                cancelled = true
                runCatching { thread?.interrupt() }
            }
            val previous = synchronized(LOCK) {
                val old = active
                active = pb
                old
            }
            previous?.cancel()
            thread = Thread({ runLoop() }, "speech-speaker").also { it.start() }
        }

        /** 喂一块音频；队列满时阻塞。取消后立刻返回 false。 */
        fun push(segment: Int, bytes: ByteArray, last: Boolean, segmentTotal: Int): Boolean {
            if (cancelled || done) return false
            val c = Chunk(segment, bytes, last, segmentTotal)
            while (!cancelled) {
                val queued = try {
                    queue.offer(c, 200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    return false
                }
                if (queued) {
                    queuedBytes.addAndGet(bytes.size)
                    return true
                }
            }
            return false
        }

        /** 所有片段都喂完了：等缓冲播完再返回。 */
        fun finish() {
            allPushed = true
            if (cancelled) {
                runCatching { thread?.interrupt() }
                return
            }
            // 用带超时的 offer 而不是 put：万一喂数线程已经死了，put 会永久阻塞，
            // 把整轮对话（连同麦克风）一起吊住。
            while (!done && !cancelled) {
                if (runCatching { queue.offer(pill, 200, TimeUnit.MILLISECONDS) }
                        .getOrDefault(false)
                ) {
                    break
                }
            }
            runCatching { thread?.join(300_000) }
        }

        /** 立刻停止（用户按了停止 / 这一轮被顶掉）。 */
        fun cancel() {
            cancelled = true
            runCatching { thread?.interrupt() }
            queue.clear()
            pb.cancel()
        }

        private fun runLoop() {
            // 喂数线程要抢在音频硬件前面。合成（Kokoro）在别的进程里把 CPU 吃满时，
            // 普通优先级线程可能几百毫秒拿不到时间片，缓冲一空就 underrun。
            runCatching {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                )
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // 缓冲区要足够深（这里至少 2 秒）。理由：
            // 这台手机的 HAL 在 underrun 之后**会把输出流整个拆掉再重建**
            // （logcat: AudioFlinger "remove track ... due to underrun" →
            // AudioALSAStreamOut "NoAudioTrack=1" → AudioTrack "disabled due to
            // previous underrun, restarting"，重建要 1.4 秒），那一下就是听到的爆音/杂音。
            // Kokoro 是「一整片算完才发回来」的，缓冲越深，越能跨过上一片播完、
            // 下一片还没算好那段空窗。缓冲大小只决定「能提前垫多深」，不影响开口延迟。
            val bufferSize = if (minBuf > 0) maxOf(minBuf * 8, sampleRate * 4) else 65536
            Log.i(TAG, "speaker buffer: minBuf=$minBuf bufferSize=$bufferSize rate=$sampleRate")
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            pb.track = track
            if (speed > 0f && kotlin.math.abs(speed - 1f) > 0.001f) {
                runCatching {
                    track.playbackParams = track.playbackParams.setSpeed(speed.coerceIn(0.5f, 2f))
                }
            }

            val spectrum = Spectrum(1024)
            val silence = ShortArray(WRITE_CHUNK)
            var started = false
            var writtenFrames = 0
            /** 为了避免 underrun 而被迫垫进去的静音帧数（诊断用）。 */
            var paddedFrames = 0
            /** 长度为奇数的网络块数（诊断用：归一由 PcmFramer 处理，这里只统计）。 */
            var oddChunks = 0
            var lastReport = 0L
            var sawPill = false

            // 轨道余量低于这个值才允许垫静音。要盖过这条链路的固有延迟
            // （AudioFlinger mAfLatency≈42ms + HAL mLatency≈214ms）再留些余量。
            val padArmFrames = sampleRate * PAD_ARM_MS / 1000

            // 每段音频在「已写入帧」坐标里的起点/长度。只有喂数线程访问，不需要同步。
            val marks = ArrayList<Mark>()

            /**
             * 进度按**实际播到的帧**反查片段，而不是按「喂到哪一块」。
             *
             * 喂数总是跑在播放前面（AudioTrack 缓冲 + 上层预取），跟着喂数走的话
             * 字幕会提前跳到下一句。playbackHeadPosition 才是「耳朵听到哪了」。
             */
            fun report(force: Boolean = false) {
                if (marks.isEmpty()) return
                val now = System.currentTimeMillis()
                if (!force && now - lastReport < 50) return
                lastReport = now
                val played = track.playbackHeadPosition
                var m = marks[0]
                for (mark in marks) {
                    if (played >= mark.startFrame) m = mark else break
                }
                val denom = if (m.totalFrames > 0) m.totalFrames
                else max(1, writtenFrames - m.startFrame)
                val frac = (played - m.startFrame).toFloat() / denom
                onProgress(m.segment, frac.coerceIn(0f, 1f))
            }

            // 帧对齐：HTTP 每次 read 的长度是任意的（可能是奇数），直接按
            // `size / 2` 逐块转换会丢掉半个样本，害这一句后面所有样本错位。
            // 交给 PcmFramer 跨块接力，保证写进轨道的永远是整帧。
            val frames = PcmFramer(WRITE_CHUNK) { buf, count ->
                track.write(buf, 0, count)
                writtenFrames += count
                // 先写第一块、再 play()：不要拿一条空轨道去开播，
                // 空轨道同样会 underrun，白白触发一次输出流拆建。
                if (!started) {
                    started = true
                    runCatching { track.play() }
                    onStarted()
                }
                onLevel(spectrum.analyze(buf, count))
                report()
            }

            try {
                // 预垫：先攒够 [PREROLL_MS] 毫秒再开口。Kokoro 一次算一整片才发回来，
                // 一片要 1~8 秒；如果第一片刚回来就开口，第二片还没算好缓冲就见底了。
                //
                // 但队列是按「块」限长的（QUEUE_CHUNKS），而云端 TTS（OpenRouter /
                // Cloudflare）一次 read() 往往只有 1~2 KB：64 块加起来可能还不到
                // 2.5 秒。这时如果只等字节数，push 会先把队列塞满并永久阻塞，
                // 喂数线程又永远等不到门槛 —— 双向死锁，界面一直卡在「合成中」。
                // 所以「队列已满」也算攒够了：再多也装不下，先开口让合成继续跑。
                val preRollBytes = sampleRate * 2 * PREROLL_MS / 1000
                while (!cancelled && !allPushed && queuedBytes.get() < preRollBytes &&
                    queue.remainingCapacity() > 0
                ) {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                Log.i(
                    TAG,
                    "preroll: ${queuedBytes.get()}B / ${preRollBytes}B, " +
                        "queued=${QUEUE_CHUNKS - queue.remainingCapacity()}/$QUEUE_CHUNKS"
                )

                while (!cancelled) {
                    var c: Chunk? = null
                    while (!cancelled) {
                        c = queue.poll()
                        if (c != null) break
                        if (!started) {
                            // 还没开口：安静等第一块，别把一条空轨道先开起来
                            try {
                                Thread.sleep(10)
                            } catch (e: InterruptedException) {
                                break
                            }
                            continue
                        }
                        // 已经开播、但下一块还没到。**不能一看队列空就填静音**：
                        // 队列只是「网络块 → 轨道」的中转站，本线程几微秒就把它抽干，
                        // 而下一块要几十毫秒后才到。旧写法等于在几乎每个网络块之间
                        // 都插一段静音，整句话被啃成一截一截，每处还各带一次 DC 阶跃 ——
                        // 这就是听到的沙沙/爆音。存档和重放（整块连续写）不走这条路，
                        // 所以同样的字节重放是干净的。
                        //
                        // 余量要看**轨道里已写未播的帧数**：
                        //   buffered = writtenFrames - playbackHeadPosition
                        // 只要它还远高于这条路的固有延迟（mLatency≈214ms），就安静
                        // 等下一块，什么都不写 —— 音频到货后自己续上，无需任何填充。
                        val buffered = writtenFrames - track.playbackHeadPosition
                        if (buffered > padArmFrames) {
                            try {
                                Thread.sleep(5)
                            } catch (e: InterruptedException) {
                                break
                            }
                            continue
                        }
                        // 余量真的见底（合成/网络卡住）：垫静音总好过 underrun。
                        // underrun 会被这台手机的 HAL 拆掉输出流再重建（1.4 秒 + 爆音），
                        // 垫静音只是把这一小段变成「稍等一下」。write 在缓冲满时会阻塞，
                        // 天然按实时速度垫。
                        track.write(silence, 0, WRITE_CHUNK)
                        writtenFrames += WRITE_CHUNK
                        paddedFrames += WRITE_CHUNK
                    }
                    if (cancelled) break
                    val chunk = c ?: continue
                    if (chunk === pill) {
                        sawPill = true
                        break
                    }
                    queuedBytes.addAndGet(-chunk.bytes.size)
                    // 换片段：记下它的起点（此刻已写入多少帧）
                    if (marks.isEmpty() || marks.last().segment != chunk.segment) {
                        marks.add(Mark(chunk.segment, writtenFrames))
                        lastReport = 0L
                    }
                    if (chunk.last && chunk.segmentTotal > 0) {
                        marks.last().totalFrames = chunk.segmentTotal / 2
                    }
                    if (chunk.bytes.isNotEmpty()) {
                        if (chunk.bytes.size and 1 == 1) oddChunks++
                        frames.feed(chunk.segment, chunk.bytes)
                        // 每块收尾就把不足一帧的尾巴送出去：既保持顺序，
                        // 也让 `paddedFrames` 的静音不会插到已缓冲的样本前面。
                        frames.flush()
                    }
                    if (chunk.last) frames.endSegment(chunk.segment)
                }

                // 正常收尾：等缓冲里的声音真正播完（而不是等一个固定秒数）
                if (!cancelled && sawPill) {
                    val deadline = System.currentTimeMillis() +
                        writtenFrames * 1000L / sampleRate + 3000L
                    while (!cancelled && track.playbackHeadPosition < writtenFrames &&
                        System.currentTimeMillis() < deadline
                    ) {
                        report()
                        Thread.sleep(20)
                    }
                    report(force = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "speaker failed", e)
            } finally {
                // 诊断：垫静音 = 合成没跟上播放。正常应接近 0；不为 0 就说明
                // 这轮里句子中段被塞了空隙（听起来就是沙沙/一截一截）。
                if (paddedFrames > 0) {
                    Log.w(
                        TAG,
                        "speaker: padded silence $paddedFrames frames (" +
                            paddedFrames * 1000L / sampleRate + " ms) — synthesis lagged playback"
                    )
                }
                // 帧对齐诊断：oddChunks > 0 说明 HTTP 读块确实有奇数长度
                // （旧代码正是在这里丢掉半个样本）；droppedBytes > 0 说明某个
                // 片段总字节数是奇数，末尾半个样本无法成帧。
                Log.i(TAG, "speaker: oddChunks=$oddChunks droppedHalfSamples=${frames.droppedBytes}")
                done = true
                pb.track = null
                runCatching { track.stop() }
                runCatching { track.release() }
                finishPlayback(pb)
                onDone()
            }
        }
    }

    /**
     * 开一条连续播放通道。若已有别的播放在进行，会**先停掉旧的**。
     *
     * @param onStarted 音频真正开始出声时回调一次（界面据此从「合成中」切到「说话中」）
     * @param onProgress 每个片段的播放进度（片段序号 + 0..1）
     */
    fun speaker(
        sampleRate: Int,
        onLevel: (Spectrum.Bands) -> Unit,
        onStarted: () -> Unit,
        onProgress: (segment: Int, fraction: Float) -> Unit,
        onDone: () -> Unit,
        speed: Float = 1f,
    ): Speaker {
        val sp = Speaker(sampleRate, speed, onLevel, onStarted, onProgress, onDone)
        sp.start()
        return sp
    }

    /**
     * 开始播放。若已有别的播放在进行，会**先停掉旧的**再开始这一路。
     *
     * @param onStarted 音频真正开始出声时回调（界面据此从「合成中」切到「说话中」）
     */
    fun play(
        contentType: String,
        bytes: ByteArray,
        onLevel: (Spectrum.Bands) -> Unit,
        onProgress: (playedMs: Int, totalMs: Int) -> Unit = { _, _ -> },
        onDone: () -> Unit,
        onStarted: () -> Unit = {},
        speed: Float = 1f,
    ) {
        val pb = Playback()
        val previous = synchronized(LOCK) {
            val old = active
            active = pb
            old
        }
        // 旧的一路必须先停：既避免叠音，也把它占着的 AudioTrack 释放掉
        previous?.cancel()

        val type = contentType.lowercase()
        if (type.contains("pcm") || type.contains("l16") || type.contains("wave")) {
            val rate = parseRate(contentType) ?: 24000
            playPcm(pb, bytes, rate, onLevel, onProgress, onDone, onStarted)
        } else {
            playFile(pb, bytes, onProgress, onDone, onStarted, speed)
        }
    }

    /** 简版：只要播完的通知（语音库重放用）。 */
    fun play(contentType: String, bytes: ByteArray, onDone: () -> Unit) =
        play(contentType, bytes, {}, { _, _ -> }, onDone)

    fun stop() {
        val pb = synchronized(LOCK) {
            val p = active
            active = null
            p
        }
        pb?.cancel()
    }

    /** 供外部把 PCM 包成 WAV 时复用（语音库落盘用）。 */
    fun isPcm(contentType: String): Boolean {
        val t = contentType.lowercase()
        // WAV 是 PCM 容器：连续通道只喂 "data" 之后的数据块（头由 Turn 剥掉）。
        // 注意 play() 里的整段判定刻意不含 "wav"，让整段 WAV 仍走 MediaPlayer。
        return t.contains("pcm") || t.contains("l16") || t.contains("wav")
    }

    private fun playPcm(
        pb: Playback,
        bytes: ByteArray,
        sampleRate: Int,
        onLevel: (Spectrum.Bands) -> Unit,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit,
        onStarted: () -> Unit,
    ) {
        val total = bytes.size / 2
        if (total <= 0) {
            finishPlayback(pb)
            onDone()
            return
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 关键：缓冲区只留几帧即可。开太大 write() 会瞬间返回，
        // 声音就变成“一次性灌完”，后面必然被截断。
        val bufferSize = if (minBuf > 0) max(minBuf * 4, 8192) else 65536

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        pb.track = track

        val spectrum = Spectrum(1024)
        val chunk = 1024
        val totalMs = (total * 1000L / sampleRate).toInt()

        // 进度回调发声很密（每 1024 帧 ≈ 43ms），节流到 ~50ms 一次
        var lastReport = 0L
        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastReport < 50) return
            lastReport = now
            // 注意用 Long 乘：playbackHeadPosition 是「帧数」，24kHz 下 89 秒后就
            // 超过 Int 除以 1000 的安全范围，用 Int 乘 1000 会溢出成负数，
            // 界面收到的进度就变成负的 → 高亮重置回开头然后永远不动（长文本的假故障）。
            val playedMs = (track.playbackHeadPosition.toLong() * 1000L / sampleRate).toInt()
            onProgress(playedMs.coerceAtMost(totalMs), totalMs)
        }

        try {
            track.play()
            onStarted()
            var offset = 0
            while (offset < total && !pb.cancelled) {
                val n = if (total - offset < chunk) total - offset else chunk
                val pcm = Spectrum.le16ToShortsAt(bytes, offset, n)
                // MODE_STREAM：缓冲区满时 write 会阻塞，天然按实时速度喂数据
                track.write(pcm, 0, n)
                onLevel(spectrum.analyze(pcm, n))
                report()
                offset += n
            }

            // 等缓冲区里的声音真正播完，而不是等固定几秒
            val deadline = System.currentTimeMillis() + totalMs + 3000L
            while (!pb.cancelled && track.playbackHeadPosition < total &&
                System.currentTimeMillis() < deadline
            ) {
                report()
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playPcm failed", e)
        } finally {
            pb.track = null
            runCatching { track.stop() }
            runCatching { track.release() }
            finishPlayback(pb)
        }
        onDone()
    }

    private fun playFile(
        pb: Playback,
        bytes: ByteArray,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit,
        onStarted: () -> Unit,
        speed: Float,
    ) {
        val file = File(context.cacheDir, "tts_reply.tmp")
        val player = MediaPlayer()
        pb.mp = player
        try {
            file.writeBytes(bytes)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.prepare()
            if (speed > 0f && kotlin.math.abs(speed - 1f) > 0.001f) {
                runCatching {
                    player.playbackParams = player.playbackParams.setSpeed(speed.coerceIn(0.5f, 2f))
                }
            }
            player.start()
            onStarted()
            val dur = runCatching { player.duration }.getOrDefault(0)
            while (player.isPlaying && !pb.cancelled) {
                onProgress(player.currentPosition, if (dur > 0) dur else 0)
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
        } finally {
            pb.mp = null
            runCatching { player.release() }
            finishPlayback(pb)
        }
        onDone()
    }

    /** 播放结束（正常或被掐断）后把自己从"当前播放"里摘掉。 */
    private fun finishPlayback(pb: Playback) {
        synchronized(LOCK) {
            if (active === pb) active = null
        }
    }

    fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioPlayer"

        /** 写 AudioTrack 的块大小（帧）。小一点取消更跟手，球体也跳得细。 */
        private const val WRITE_CHUNK = 1024

        /**
         * 连续通道的队列深度（块，每块约 0.17 秒 24kHz PCM）。
         * 64 块 ≈ 11 秒 —— 这就是「合成能跑到播放前面多远」的上限。
         */
        private const val QUEUE_CHUNKS = 64

        /**
         * 开口前先攒够多少毫秒的音频。Kokoro 是「整片算完才发」的，这片预垫
         * 用来跨过「上一片播完、下一片还没算好」的空窗；攒够就开口，
         * 所以它同时也是首字延迟的增长量。
         */
        private const val PREROLL_MS = 2500

        /**
         * 允许垫静音的轨道余量门槛（帧余量低于它才开始垫）。必须远小于
         * [PREROLL_MS]（那是正常预垫的量），又要盖过链路固有延迟
         * （AudioFlinger ~42ms + HAL ~214ms），所以取 500ms。
         */
        private const val PAD_ARM_MS = 500

        private val LOCK = Any()

        /** 进程内当前正在播放的那一路（跨 Activity 实例共享）。 */
        private var active: Playback? = null

        /** 从 `audio/pcm;rate=24000` 这类 contentType 里取采样率。 */
        fun parseRate(contentType: String): Int? {
            val m = Regex("rate=(\\d+)").find(contentType) ?: return null
            return m.groupValues[1].toIntOrNull()
        }
    }
}
