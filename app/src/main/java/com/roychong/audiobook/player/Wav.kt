package com.roychong.audiobook.player

/** 把 PCM16 单声道包成 WAV 字节流，发给 ASR 接口。 */
object Wav {

    fun fromPcm16(pcm: ShortArray, count: Int, sampleRate: Int): ByteArray {
        val dataLen = count * 2
        val out = ByteArray(44 + dataLen)

        putAscii(out, 0, "RIFF")
        putIntLe(out, 4, 36 + dataLen)
        putAscii(out, 8, "WAVE")
        putAscii(out, 12, "fmt ")
        putIntLe(out, 16, 16)                 // PCM chunk size
        putShortLe(out, 20, 1)                // audio format = PCM
        putShortLe(out, 22, 1)                // mono
        putIntLe(out, 24, sampleRate)
        putIntLe(out, 28, sampleRate * 2)     // byte rate
        putShortLe(out, 32, 2)                // block align
        putShortLe(out, 34, 16)               // bits per sample
        putAscii(out, 36, "data")
        putIntLe(out, 40, dataLen)

        var p = 44
        for (i in 0 until count) {
            out[p++] = (pcm[i].toInt() and 0xFF).toByte()
            out[p++] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun putAscii(b: ByteArray, off: Int, s: String) {
        for (i in s.indices) b[off + i] = s[i].code.toByte()
    }

    private fun putIntLe(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putShortLe(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
