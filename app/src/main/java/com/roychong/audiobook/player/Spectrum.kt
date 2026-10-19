package com.roychong.audiobook.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频段能量分析：Hann 窗 + radix-2 FFT + 自适应峰值归一化。
 *
 * 麦克风收音（驱动 3D 圆球）和 TTS 播放回馈共用同一套，
 * 保证「我说话」和「AI 说话」两种状态下球体的反应是一致的。
 */
class Spectrum(private val size: Int = 1024) {

    data class Bands(val level: Float, val bass: Float, val mid: Float, val treble: Float)

    private val window = FloatArray(size) {
        0.5f * (1f - cos(2.0 * PI * it / (size - 1)).toFloat())
    }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val peak = FloatArray(4) { 1e-3f }

    /** 对 [samples] 的前 [count] 个样本做分析。 */
    fun analyze(samples: ShortArray, count: Int): Bands {
        val level = norm(0, rms(samples, count) * 3.0f)

        for (i in 0 until size) {
            val s = if (i < count) samples[i] / 32768f else 0f
            re[i] = s * window[i]
            im[i] = 0f
        }
        fft(re, im)

        return Bands(
            level = level,
            bass = norm(1, bandEnergy(2, 8)),
            mid = norm(2, bandEnergy(9, 32)),
            treble = norm(3, bandEnergy(33, 128) * 1.6f)
        )
    }

    /** 原始 RMS（0..1），用于 VAD 判断，不做归一化。 */
    fun rms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768f
            sum += s.toDouble() * s
        }
        return sqrt(sum / count).toFloat()
    }

    private fun bandEnergy(lo: Int, hi: Int): Float {
        var sum = 0f
        for (k in lo..hi) {
            if (k >= size / 2) break
            sum += sqrt(re[k] * re[k] + im[k] * im[k]) / (size / 4f)
        }
        return sum / (hi - lo + 1)
    }

    /** 自适应峰值归一化：让各频段稳定落在 0..1，不随环境音量漂移。 */
    private fun norm(index: Int, v: Float): Float {
        // 绝对底噪：安静时的电流声/风扇声不该被"归一化"放大成满格。
        val floor = if (index == 0) 0.006f else 0.004f
        val x = if (v < floor) 0f else v - floor

        // 峰值衰减必须慢：以前每帧 ×0.90，1 秒就掉到 0.5%，
        // 等于一直在拿"当下这点小声"当满格，球体自然跟着环境噪声乱抖。
        peak[index] = max(peak[index] * 0.994f, x)
        val p = if (peak[index] < 1e-3f) 1e-3f else peak[index]
        return (x / p).coerceIn(0f, 1f)
    }

    /** 迭代式 radix-2 FFT（原地）。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vR = re[b] * curR - im[b] * curI
                    val vI = re[b] * curI + im[b] * curR
                    re[b] = re[a] - vR
                    im[b] = im[a] - vI
                    re[a] += vR
                    im[a] += vI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** 小端 16-bit PCM 字节 → ShortArray（TTS 播放用）。 */
        fun le16ToShorts(bytes: ByteArray, count: Int): ShortArray =
            le16ToShortsAt(bytes, 0, count)

        /** 从第 [offsetSamples] 个样本起转换 [count] 个样本。 */
        fun le16ToShortsAt(bytes: ByteArray, offsetSamples: Int, count: Int): ShortArray {
            val out = ShortArray(count)
            le16ToShortsInto(bytes, offsetSamples, out, count)
            return out
        }

        /**
         * 同 [le16ToShortsAt]，但写进调用方给的缓冲。
         * 连续播放时每 ~43ms 就要转一块，复用缓冲省掉大量小对象分配。
         */
        fun le16ToShortsInto(
            src: ByteArray,
            offsetSamples: Int,
            dst: ShortArray,
            count: Int,
        ) {
            var p = offsetSamples * 2
            for (i in 0 until count) {
                val lo = src[p].toInt() and 0xFF
                val hi = src[p + 1].toInt()
                dst[i] = ((hi shl 8) or lo).toShort()
                p += 2
            }
        }
    }
}
