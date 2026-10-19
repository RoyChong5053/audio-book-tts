package com.roychong.audiobook.tts

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 音频格式信息（WAV / PCM / 压缩格式的简化解析）。 */
data class AudioInfo(
    val contentType: String,
    val pcm: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    val durationMs: Long,
    val dataOffset: Int,
    val dataLength: Int,
)

object AudioMeta {

    fun isPcm(contentType: String): Boolean {
        val t = contentType.lowercase()
        return t.contains("pcm") || t.contains("l16") || t.contains("wav") || t.contains("wave")
    }

    fun inspect(contentType: String, bytes: ByteArray): AudioInfo {
        val t = contentType.lowercase()
        if (isWav(bytes)) return inspectWav(contentType, bytes)
        if (isPcm(contentType)) {
            val rate = Regex("rate=(\\d+)").find(contentType)?.groupValues?.getOrNull(1)
                ?.toIntOrNull() ?: 24000
            val channels = Regex("channels=(\\d+)").find(contentType)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()?.coerceIn(1, 2) ?: 1
            val bits = 16
            val ms = bytes.size.toLong() * 1000L / (rate.toLong() * channels * (bits / 8))
            return AudioInfo(contentType, true, rate, channels, bits, ms, 0, bytes.size)
        }
        val ms = when {
            t.contains("mpeg") || t.contains("mp3") -> bytes.size.toLong() * 8L * 1000L / 128_000L
            else -> 0L
        }
        return AudioInfo(contentType, false, 0, 1, 16, ms, 0, bytes.size)
    }

    fun pcmBytes(contentType: String, bytes: ByteArray): ByteArray {
        if (!isWav(bytes)) return bytes
        val info = inspectWav(contentType, bytes)
        val end = (info.dataOffset + info.dataLength).coerceIn(0, bytes.size)
        if (info.dataOffset !in 0..bytes.size || info.dataOffset >= end) return bytes
        return bytes.copyOfRange(info.dataOffset, end)
    }

    private fun isWav(b: ByteArray): Boolean =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    private fun inspectWav(contentType: String, bytes: ByteArray): AudioInfo {
        var rate = 24000
        var channels = 1
        var bits = 16
        var dataOffset = 44
        var dataLength = (bytes.size - 44).coerceAtLeast(0)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = le32(bytes, p + 4)
            val body = p + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                channels = (le16(bytes, body + 2)).coerceIn(1, 8)
                rate = le32(bytes, body + 4).let { if (it in 8000..192000) it else rate }
                bits = le16(bytes, body + 14).let { if (it in 8..32) it else bits }
            }
            if (id == "data") {
                dataOffset = body
                dataLength = size.coerceIn(0, (bytes.size - body).coerceAtLeast(0))
                break
            }
            val step = 8 + size + (size and 1)
            if (step <= 0 || p + step <= p) break
            p += step
        }
        val frame = (channels * (bits / 8)).coerceAtLeast(1)
        val ms = dataLength.toLong() * 1000L / (rate.toLong() * frame)
        return AudioInfo(contentType, true, rate, channels, bits, ms, dataOffset, dataLength)
    }

    private fun le16(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
}

/**
 * 所有音频都落在 Android/media/com.roychong.audiobook：
 *
 *  books/<bookId>/chunks/<hash>.audio   句子块缓存，永不自动删除
 *  books/<bookId>/audio/<model>/...     每次播放/暂停一个可听的归档文件
 *
 * 云端 TTS 花钱生成的声音会一直保留，不会因为容量上限被清掉。
 */
class BookAudioStore(private val context: Context) {

    data class Entry(
        val contentType: String,
        val bytes: ByteArray,
        val info: AudioInfo,
    )

    data class ChunkMeta(
        val contentType: String,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        val pcm: Boolean,
        val chapterIndex: Int,
        val indexInChapter: Int,
        val startChar: Int,
        val endChar: Int,
    )

    val root: File = run {
        @Suppress("DEPRECATION")
        val media = context.getExternalMediaDirs().firstOrNull()
        val base = media ?: context.getExternalFilesDir("media")
        val d = base ?: File(context.filesDir, "media")
        d.mkdirs()
        d
    }

    fun chunkKey(baseUrl: String, model: String, voice: String, text: String): String {
        val raw = "$baseUrl|$model|$voice|$text"
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun chunkDir(bookId: String): File =
        File(root, "books/$bookId/chunks").apply { mkdirs() }

    fun get(bookId: String, key: String): Entry? {
        val dir = chunkDir(bookId)
        val audio = File(dir, "$key.audio")
        val metaFile = File(dir, "$key.json")
        if (!audio.exists() || !metaFile.exists()) return null
        return runCatching {
            val bytes = audio.readBytes()
            if (bytes.isEmpty()) return null
            val o = JSONObject(metaFile.readText())
            val contentType = o.optString("type", "audio/pcm;rate=24000;channels=1")
            Entry(contentType, bytes, AudioMeta.inspect(contentType, bytes))
        }.getOrNull()
    }

    fun put(bookId: String, key: String, contentType: String, bytes: ByteArray, chunk: ChunkMeta) {
        if (bytes.isEmpty()) return
        val dir = chunkDir(bookId)
        runCatching {
            val audio = File(dir, "$key.audio.tmp")
            val meta = File(dir, "$key.json.tmp")
            audio.writeBytes(bytes)
            meta.writeText(
                JSONObject()
                    .put("type", contentType)
                    .put("durationMs", chunk.durationMs)
                    .put("rate", chunk.sampleRate)
                    .put("channels", chunk.channels)
                    .put("bits", chunk.bits)
                    .put("pcm", chunk.pcm)
                    .put("chapter", chunk.chapterIndex)
                    .put("index", chunk.indexInChapter)
                    .put("start", chunk.startChar)
                    .put("end", chunk.endChar)
                    .toString()
            )
            File(dir, "$key.audio.tmp").renameTo(File(dir, "$key.audio"))
            File(dir, "$key.json.tmp").renameTo(File(dir, "$key.json"))
        }
    }

    fun totalBytes(): Long = runCatching {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun clearAll() {
        runCatching { root.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun archiveDir(bookId: String, model: String): File =
        File(root, "books/$bookId/audio/${safe(model)}").apply { mkdirs() }

    private fun safe(s: String): String =
        s.ifBlank { "unknown" }
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_', '.')
            .take(60)
            .ifBlank { "unknown" }

    /**
     * 一次播放 / 暂停周期对应一个归档文件。
     * PCM/WAV 会写标准 WAV 头并 append 数据；压缩格式直接 append 字节。
     */
    inner class SessionArchive(
        private val file: File,
        private val info: AudioInfo,
        private val wav: Boolean,
    ) {
        private val raf = RandomAccessFile(file, "rw")
        private var dataBytes = 0L
        private var closed = false

        init {
            if (wav) {
                raf.setLength(0)
                writeHeader()
            }
        }

        val path: String get() = file.absolutePath

        fun compatible(entry: Entry): Boolean {
            if (wav != entry.info.pcm) return false
            if (!wav) return true
            return entry.info.sampleRate == info.sampleRate &&
                entry.info.channels == info.channels &&
                entry.info.bits == info.bits
        }

        fun append(entry: Entry) {
            if (closed) return
            if (wav) {
                val pcm = AudioMeta.pcmBytes(entry.contentType, entry.bytes)
                raf.seek(raf.length())
                raf.write(pcm)
                dataBytes += pcm.size
                writeHeader()
            } else {
                raf.seek(raf.length())
                raf.write(entry.bytes)
                dataBytes += entry.bytes.size
            }
        }

        fun close() {
            if (closed) return
            closed = true
            if (wav) writeHeader()
            runCatching { raf.close() }
        }

        private fun writeHeader() {
            raf.seek(0)
            val byteRate = info.sampleRate * info.channels * (info.bits / 8)
            val blockAlign = info.channels * (info.bits / 8)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            le32((36 + dataBytes).toInt())
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            le32(16)
            le16(1)
            le16(info.channels)
            le32(info.sampleRate)
            le32(byteRate)
            le16(blockAlign)
            le16(info.bits)
            raf.write("data".toByteArray(Charsets.US_ASCII))
            le32(dataBytes.toInt())
        }

        private fun le16(v: Int) {
            raf.write(v and 0xff)
            raf.write((v ushr 8) and 0xff)
        }

        private fun le32(v: Int) {
            raf.write(v and 0xff)
            raf.write((v ushr 8) and 0xff)
            raf.write((v ushr 16) and 0xff)
            raf.write((v ushr 24) and 0xff)
        }
    }

    fun newArchive(bookId: String, model: String, label: String, entry: Entry): SessionArchive {
        val dir = archiveDir(bookId, model)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val prefix = safe(label).take(40).ifBlank { "audiobook" }
        val ext = when {
            entry.info.pcm -> "wav"
            entry.contentType.contains("mpeg") || entry.contentType.contains("mp3") -> "mp3"
            entry.contentType.contains("aac") || entry.contentType.contains("mp4") -> "m4a"
            else -> "bin"
        }
        var f = File(dir, "$prefix-session-$stamp.$ext")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$prefix-session-$stamp-$n.$ext")
            n++
        }
        return SessionArchive(f, entry.info, entry.info.pcm)
    }
}
