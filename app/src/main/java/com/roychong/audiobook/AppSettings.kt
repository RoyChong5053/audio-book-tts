package com.roychong.audiobook

import android.content.Context

/** 阅读主题。颜色全部集中在这里，Activity 按需取用。 */
enum class ReaderTheme(
    val id: String,
    val label: String,
    val bg: Int,
    val panel: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val hot: Int,
    val sentenceBg: Int,
    val lightStatusBar: Boolean,
) {
    DARK(
        "dark", "暗色",
        0xFF101317.toInt(), 0xFF1B2027.toInt(), 0xFFD9E2EC.toInt(),
        0xFF6E7F93.toInt(), 0xFF7CC7FF.toInt(), 0xFFFFD166.toInt(),
        0x2235A0D8, false,
    ),
    PAPER(
        "paper", "纸质",
        0xFFF4ECD8.toInt(), 0xFFEADFC4.toInt(), 0xFF3B3126.toInt(),
        0xFF8C7B64.toInt(), 0xFF8C5A2B.toInt(), 0xFFA8320F.toInt(),
        0x33C9A227, true,
    ),
    NIGHT(
        "night", "夜间纯黑",
        0xFF000000.toInt(), 0xFF0B0B0B.toInt(), 0xFFC9CED6.toInt(),
        0xFF4A5058.toInt(), 0xFF5AA9E6.toInt(), 0xFFFFC857.toInt(),
        0x222C4A66, false,
    ),
    GREEN(
        "green", "护眼绿",
        0xFFDDEBDD.toInt(), 0xFFCFE3CF.toInt(), 0xFF26382A.toInt(),
        0xFF7B947F.toInt(), 0xFF2F6B45.toInt(), 0xFFB3541E.toInt(),
        0x33A7C957, true,
    );

    companion object {
        fun of(id: String?): ReaderTheme = entries.firstOrNull { it.id == id } ?: DARK
    }
}

/** TTS 配置。默认值直接沿用 DeskSpirit 的局域网 audio.cpp。 */
data class TtsConfig(
    val baseUrl: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val voice: String = DEFAULT_VOICE,
    val speed: Float = 1.0f,
    val timeoutSec: Int = 120,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE = "http://192.168.100.200:11439/v1"
        const val DEFAULT_MODEL = "Qwen3-TTS-12Hz-0.6B-CustomVoice"
        const val DEFAULT_VOICE = "serena"
    }
}

/** 本地设置：阅读外观 + TTS + 缓存。与书籍内容分开存。 */
class AppSettings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 阅读外观 ----------

    fun theme(): ReaderTheme = ReaderTheme.of(sp.getString(K_THEME, null))

    fun saveTheme(t: ReaderTheme) = sp.edit().putString(K_THEME, t.id).apply()

    /** 字号 sp，16..32。 */
    fun fontSize(): Int = sp.getInt(K_FONT_SIZE, 19).coerceIn(14, 34)

    fun saveFontSize(v: Int) = sp.edit().putInt(K_FONT_SIZE, v.coerceIn(14, 34)).apply()

    /** 行距倍数。 */
    fun lineSpacing(): Float = sp.getFloat(K_LINE_SPACING, 1.65f).coerceIn(1.0f, 2.4f)

    fun saveLineSpacing(v: Float) = sp.edit()
        .putFloat(K_LINE_SPACING, v.coerceIn(1.0f, 2.4f)).apply()

    /** 页边距 dp。 */
    fun marginDp(): Int = sp.getInt(K_MARGIN, 20).coerceIn(8, 48)

    fun saveMarginDp(v: Int) = sp.edit().putInt(K_MARGIN, v.coerceIn(8, 48)).apply()

    /** true = 衬线，false = 无衬线。 */
    fun serif(): Boolean = sp.getBoolean(K_SERIF, true)

    fun saveSerif(v: Boolean) = sp.edit().putBoolean(K_SERIF, v).apply()

    fun autoScroll(): Boolean = sp.getBoolean(K_AUTO_SCROLL, true)

    fun saveAutoScroll(v: Boolean) = sp.edit().putBoolean(K_AUTO_SCROLL, v).apply()

    /** 预取句数；越大越不怕网络抖动，内存/请求也越多。 */
    fun prefetch(): Int = sp.getInt(K_PREFETCH, 3).coerceIn(1, 8)

    fun savePrefetch(v: Int) = sp.edit().putInt(K_PREFETCH, v.coerceIn(1, 8)).apply()

    // ---------- TTS ----------

    fun tts(): TtsConfig = TtsConfig(
        baseUrl = sp.getString(K_TTS_BASE, TtsConfig.DEFAULT_BASE) ?: TtsConfig.DEFAULT_BASE,
        apiKey = sp.getString(K_TTS_KEY, "") ?: "",
        model = sp.getString(K_TTS_MODEL, TtsConfig.DEFAULT_MODEL) ?: TtsConfig.DEFAULT_MODEL,
        voice = sp.getString(K_TTS_VOICE, TtsConfig.DEFAULT_VOICE) ?: TtsConfig.DEFAULT_VOICE,
        speed = sp.getFloat(K_TTS_SPEED, 1.0f).coerceIn(0.5f, 2.0f),
        timeoutSec = sp.getInt(K_TTS_TIMEOUT, 120).coerceIn(15, 1800),
    )

    fun saveTts(c: TtsConfig) = sp.edit()
        .putString(K_TTS_BASE, c.baseUrl.trim())
        .putString(K_TTS_KEY, c.apiKey.trim())
        .putString(K_TTS_MODEL, c.model.trim())
        .putString(K_TTS_VOICE, c.voice.trim())
        .putFloat(K_TTS_SPEED, c.speed.coerceIn(0.5f, 2.0f))
        .putInt(K_TTS_TIMEOUT, c.timeoutSec.coerceIn(15, 1800))
        .apply()

    // ---------- 缓存 ----------

    fun cacheCapMb(): Int = sp.getInt(K_CACHE_MB, 600).coerceIn(0, 20_000)

    fun saveCacheCapMb(v: Int) = sp.edit().putInt(K_CACHE_MB, v.coerceIn(0, 20_000)).apply()

    // ---------- 书库 ----------

    fun lastBookId(): String? = sp.getString(K_LAST_BOOK, null)

    fun saveLastBookId(id: String?) = sp.edit().putString(K_LAST_BOOK, id).apply()

    companion object {
        private const val PREFS = "audiobook_settings"
        private const val K_THEME = "theme"
        private const val K_FONT_SIZE = "font_size"
        private const val K_LINE_SPACING = "line_spacing"
        private const val K_MARGIN = "margin"
        private const val K_SERIF = "serif"
        private const val K_AUTO_SCROLL = "auto_scroll"
        private const val K_PREFETCH = "prefetch"
        private const val K_TTS_BASE = "tts_base"
        private const val K_TTS_KEY = "tts_key"
        private const val K_TTS_MODEL = "tts_model"
        private const val K_TTS_VOICE = "tts_voice"
        private const val K_TTS_SPEED = "tts_speed"
        private const val K_TTS_TIMEOUT = "tts_timeout"
        private const val K_CACHE_MB = "cache_mb"
        private const val K_LAST_BOOK = "last_book"
    }
}
