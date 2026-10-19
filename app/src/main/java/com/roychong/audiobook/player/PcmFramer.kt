package com.roychong.audiobook.player

/**
 * 把「任意长度的字节块」整理成整帧 16-bit PCM。
 *
 * 为什么需要它：HTTP 流每次 `read()` 返回多少字节是**不确定的**，可能是
 * 1471、4095 这种奇数。旧写法对每个块各自做 `size / 2`，奇数块会静默丢掉
 * 最后半个样本；丢掉之后这一句剩下的所有字节都往前挪了一个字节，于是每个
 * 16-bit 样本都被拆错位 —— 听起来是持续的「沙沙/破音」，直到这一句结束。
 * （存档存的是原始字节、整段播放也是一次对齐，所以存档和重放都是干净的。）
 *
 * 这个类只做一件事：跨块把剩下的半个样本接力下去，保证送出去的永远是整帧。
 * 接力只在**同一片段内**有效；换片段必须清掉残留（每个片段是一次独立的
 * HTTP 流）。
 *
 * 刻意不依赖 Android：纯 Kotlin，方便单独验证。
 */
internal class PcmFramer(
    /** 攒够多少帧回调一次；不足的尾巴用 [flush] 送出。 */
    private val blockFrames: Int,
    private val sink: (ShortArray, Int) -> Unit,
) {
    private val buf = ShortArray(blockFrames)
    private var fill = 0

    /** 等着配对的低字节；-1 表示没有残留。 */
    private var carryLo = -1
    private var segment = -1

    /** 片段边界上丢掉的半个样本数（正常应恒为 0，异常时用于日志）。 */
    var droppedBytes: Int = 0
        private set

    /** 喂一块任意长度的字节；能凑成整帧的部分立刻经 [sink] 送出。 */
    fun feed(seg: Int, bytes: ByteArray) {
        if (seg != segment) {
            if (carryLo >= 0) droppedBytes++
            carryLo = -1
            segment = seg
        }
        var i = 0
        val n = bytes.size
        while (i < n) {
            val lo: Int
            if (carryLo >= 0) {
                lo = carryLo
                carryLo = -1
            } else {
                // 只剩 1 个字节：留到下一块配对
                if (i + 1 >= n) {
                    carryLo = bytes[i].toInt() and 0xFF
                    return
                }
                lo = bytes[i].toInt() and 0xFF
                i++
            }
            val hi = bytes[i].toInt()
            i++
            push(((hi shl 8) or lo).toShort())
        }
    }

    /** 一个片段结束：丢掉（并计数）可能残留的半个样本，再把尾巴送出去。 */
    fun endSegment(seg: Int) {
        if (seg == segment && carryLo >= 0) {
            droppedBytes++
            carryLo = -1
        }
        flush()
    }

    /** 把不足 [blockFrames] 的尾巴也送出去（保证与后续静音/下一块的顺序正确）。 */
    fun flush() {
        if (fill > 0) {
            sink(buf, fill)
            fill = 0
        }
    }

    private fun push(sample: Short) {
        buf[fill++] = sample
        if (fill == blockFrames) {
            sink(buf, fill)
            fill = 0
        }
    }
}
