package com.roychong.audiobook.tts

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** TTS：OpenAI 兼容的 `/audio/speech`，返回音频字节。 */
object TtsClient {

    /** @param contentType 形如 `audio/pcm;rate=24000;channels=1` 或 `audio/mpeg` */
    data class Audio(val contentType: String, val bytes: ByteArray)

    /**
     * 在途请求的句柄：让**别的线程**（用户按了停止、这一轮被新的一轮顶掉）能把
     * 正在等结果的连接直接掐断。
     *
     * 为什么不能只靠一个 `cancelled` 标志：读 socket 是阻塞的，标志位要等
     * 「下一个字节」或超时才生效 —— 本地 TTS 一段要合成好几秒，用户按了停止
     * 却要等它合成完才安静下来，体感就是「按了没反应」。disconnect() 会立刻
     * 让阻塞的 read 抛错返回。
     */
    class Canceller {
        @Volatile private var conn: HttpURLConnection? = null

        internal fun attach(c: HttpURLConnection?) {
            conn = c
        }

        fun cancel() {
            runCatching { conn?.disconnect() }
        }
    }

    /** 每次读多大一块。8KB ≈ 0.17 秒 24kHz 单声道 PCM，块的大小只影响喂数粒度。 */
    private const val BLOCK = 8192

    fun synthesize(
        base: String,
        key: String,
        model: String,
        voice: String,
        text: String,
        speed: Float = 1.0f,
        readTimeoutMs: Int = Http.READ_TIMEOUT_MS,
        canceller: Canceller = Canceller(),
    ): Audio {
        val out = java.io.ByteArrayOutputStream()
        val type = synthesizeStreaming(
            base, key, model, voice, text, speed, readTimeoutMs,
            canceller = canceller,
            onBlock = { b, o, l -> out.write(b, o, l); true },
        )
        return Audio(type, out.toByteArray())
    }

    /**
     * 边收边喂：音频块一到就回调，不等整段合成完。
     *
     * 这不是「流式接口」专用的 —— 对 Kokoro 这种「算完一次性返回」的服务端，
     * 它自然退化成「拿到整段后一次喂完」；而对真的会分块下发的服务端，
     * 第一块到达时就能开始播。**调用方不需要为此换端点。**
     *
     * @param onHeader 拿到响应头时回调一次（含 contentType），用来判断 PCM / 压缩格式
     * @param onBlock  每收到一块回调；返回 false 表示本地不想再要了（取消）
     * @return 实际收到的音频字节数
     */
    fun synthesizeStreaming(
        base: String,
        key: String,
        model: String,
        voice: String,
        text: String,
        speed: Float = 1.0f,
        readTimeoutMs: Int = Http.READ_TIMEOUT_MS,
        canceller: Canceller = Canceller(),
        onHeader: (contentType: String) -> Unit = {},
        onBlock: (bytes: ByteArray, offset: Int, length: Int) -> Boolean,
    ): String {
        val url = Http.endpoint(base, "audio/speech", "/audio/speech")
        if (url.isEmpty()) throw IOException("TTS Base URL 为空")
        if (model.isBlank()) throw IOException("TTS Model 为空")

        // 传输层失败换新连接重试一次 —— 但**只在还没喂出任何一块的时候**：
        // 已经播出去半句了再重试，会把前半句重复念一遍。
        var delivered = 0
        var attempt = 0
        while (true) {
            try {
                return streamOnce(url, key, model, voice, text, speed, readTimeoutMs, canceller, onHeader) { b, o, l ->
                    val keep = onBlock(b, o, l)
                    if (keep) delivered += l
                    keep
                }
            } catch (e: IOException) {
                if (attempt >= 1 || delivered > 0 || !Http.isTransportError(e)) throw e
                attempt++
            }
        }
    }

    private fun streamOnce(
        url: String,
        key: String,
        model: String,
        voice: String,
        text: String,
        @Suppress("UNUSED_PARAMETER") speed: Float,
        readTimeoutMs: Int,
        canceller: Canceller,
        onHeader: (String) -> Unit,
        onBlock: (ByteArray, Int, Int) -> Boolean,
    ): String {
        val conn = Http.open(url, key, "POST", readTimeoutMs = readTimeoutMs)
        canceller.attach(conn)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            val payload = JSONObject()
                .put("model", model)
                .put("input", text)
            if (voice.isNotBlank()) payload.put("voice", voice)
            // 不在请求里带 speed：audio.cpp / qwen3-tts 会直接 400（unsupported_parameter）。
            // 语速在本地播放端处理，见 AudioPlayer。
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = try {
                conn.responseCode
            } catch (e: java.net.SocketTimeoutException) {
                // 请求已送达，只是还没合成完。重试会重新合成一遍，没有意义。
                throw IOException(
                    "等待合成结果超时（${readTimeoutMs / 1000} 秒）：本地 TTS 可能太慢，" +
                        "可在「设置 → TTS → 合成超时」把它调大",
                    e,
                )
            }
            val contentType = (conn.contentType ?: "").lowercase()
            if (code !in 200..299) {
                val err = Http.readText(conn.errorStream)
                throw IOException("TTS HTTP $code: ${err.take(200)}")
            }
            // 有些网关出错时也用 200 + JSON
            if (contentType.contains("json") || contentType.contains("text")) {
                val err = conn.inputStream.use { it.readBytes() }
                throw IOException("TTS 返回非音频: ${String(err).take(200)}")
            }
            onHeader(contentType)

            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    // 每块新分配：回调方（播放队列）可以放心把它留在自己那里，
                    // 不用担心被下一次 read 覆盖。
                    val buf = ByteArray(BLOCK)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    if (!onBlock(buf, 0, n)) break
                    total += n
                }
            }
            if (total == 0) throw IOException("TTS 返回空音频")
            return contentType
        } finally {
            canceller.attach(null)
            Http.close(conn)
        }
    }
}
