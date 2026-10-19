package com.roychong.audiobook.tts

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** URL 拼接与通用 HTTP 小工具。 */
object Http {

    private const val TAG = "Http"

    /** 建连超时：局域网/本机都很快，慢到 20s 基本就是路径不通。 */
    const val CONNECT_TIMEOUT_MS = 20_000

    /** 默认读超时。以前写的是 5 分钟，死连接要等太久，收敛到 90s。 */
    const val READ_TIMEOUT_MS = 90_000

    /**
     * 智能拼端点。用户可能填三种形态中的任意一种：
     *
     *  - 纯域名         `https://api.xxx.com`
     *  - 带 /v1         `https://api.xxx.com/v1`
     *  - 完整端点       `https://openrouter.ai/api/v1/audio/speech`
     *
     * @param marker 用来判断“已经是完整端点”的关键字，例如 "audio/speech"
     * @param path   需要补的路径，例如 "/audio/speech"
     */
    fun endpoint(base: String, marker: String, path: String): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        if (b.contains(marker)) return b
        return if (b.endsWith("/v1")) b + path else "$b/v1$path"
    }

    /**
     * TavernLab 的 `/api/...` 根地址：把任意形态归一成 `http://host:port`。
     *
     * 用户可能填 `http://h:8888`、`http://h:8888/v1`、或完整端点
     * `http://h:8888/v1/chat/completions`，三种都要能拼出 `http://h:8888/api/characters`。
     * （直接拿 [endpoint] 去拼会得到 `/v1/api/characters`，是错的。）
     */
    fun apiRoot(base: String): String {
        val b = base.trim()
        if (b.isEmpty()) return ""
        val i = b.indexOf("/v1")
        val head = if (i >= 0) b.substring(0, i) else b
        return head.trimEnd('/')
    }

    /**
     * 建一个连接。统一做三件事：
     *
     *  - `Connection: close`：不让它进 keep-alive 池（本地模型服务多是回完就关，
     *    池里的连接很快就会变成"看着 ESTABLISHED、其实早死了"的僵尸）；
     *  - 超时收敛（读超时默认 90s，而不是 5 分钟）；
     *  - 带上 Authorization。
     *
     * 调用方务必在 `finally` 里 [close]，这样才不会像之前那样堆一地 CLOSE_WAIT。
     */
    fun open(
        url: String,
        key: String = "",
        method: String = "GET",
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("Connection", "close")
        if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
        return c
    }

    /** 关掉连接（幂等，吞异常）。 */
    fun close(c: HttpURLConnection?) {
        runCatching { c?.disconnect() }
    }

    /**
     * 是不是「传输层」的错（网络断了 / 超时 / 连接被对端重置）。
     *
     * 只有这类错才值得换一条连接重试；协议层的错（HTTP 404、返回非音频…）
     * 重试只会白跑一次。
     */
    fun isTransportError(e: IOException): Boolean =
        e is java.net.SocketException || e is java.net.SocketTimeoutException ||
            e is java.io.InterruptedIOException || e is java.io.EOFException ||
            e is java.net.UnknownHostException || e is java.net.NoRouteToHostException

    /**
     * 失败就换一条新连接重试一次。
     *
     * 只适合**连接建立/首包阶段就失败**的请求。已经拿到部分结果的场景
     * （例如 LLM 流式已经吐了字）不要在调用方外面套这个，否则会重复内容。
     */
    fun <T> retryOnce(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (!isTransportError(e)) throw e
        Log.w(TAG, "第一次失败（${e.javaClass.simpleName}: ${e.message}），换新连接重试")
        block()
    }

    /** 完整读取（大量字节场景用流式，这里给短响应）。 */
    fun readText(stream: InputStream?): String =
        stream?.bufferedReader()?.use { it.readText() } ?: ""
}
