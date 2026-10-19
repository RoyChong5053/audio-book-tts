package com.roychong.audiobook.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** 一句 TTS 的磁盘缓存。key = model|voice|speed|text 的 SHA-1。 */
class TtsCache(private val context: Context, private val capMb: Int) {

    data class Entry(val contentType: String, val bytes: ByteArray)

    val maxMb: Int get() = capMb

    private val dir: File = run {
        val ext = context.getExternalFilesDir("tts_cache")
        val d = if (ext != null) File(ext, "segments") else File(context.filesDir, "tts_cache")
        d.mkdirs()
        d
    }

    fun key(model: String, voice: String, speed: Float, text: String): String {
        val raw = "$model|$voice|${String.format(java.util.Locale.US, "%.2f", speed)}|$text"
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(key: String): Entry? {
        if (capMb <= 0) return null
        val f = File(dir, "$key.bin")
        val m = File(dir, "$key.meta")
        if (!f.exists() || !m.exists()) return null
        return runCatching {
            val ct = m.readText().trim().ifBlank { "audio/pcm;rate=24000;channels=1" }
            val bytes = f.readBytes()
            if (bytes.isEmpty()) {
                f.delete(); m.delete()
                null
            } else {
                f.setLastModified(System.currentTimeMillis())
                Entry(ct, bytes)
            }
        }.getOrNull()
    }

    @Synchronized
    fun put(key: String, contentType: String, bytes: ByteArray) {
        if (capMb <= 0 || bytes.isEmpty()) return
        runCatching {
            File(dir, "$key.bin.tmp").writeBytes(bytes)
            File(dir, "$key.meta.tmp").writeText(contentType)
            File(dir, "$key.bin.tmp").renameTo(File(dir, "$key.bin"))
            File(dir, "$key.meta.tmp").renameTo(File(dir, "$key.meta"))
            trim()
        }
    }

    @Synchronized
    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val cap = capMb.toLong() * 1024 * 1024
        if (cap <= 0) return
        var total = totalBytes()
        if (total <= cap) return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            if (total <= cap) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
