package com.roychong.audiobook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    val fieldBg: Int,
    val fieldBorder: Int,
    val lightStatusBar: Boolean,
) {
    DARK(
        "dark", "暗色",
        0xFF101317.toInt(), 0xFF1B2027.toInt(), 0xFFD9E2EC.toInt(),
        0xFF6E7F93.toInt(), 0xFF7CC7FF.toInt(), 0xFFFFD166.toInt(),
        0x2235A0D8, 0x1AFFFFFF, 0x40FFFFFF, false,
    ),
    PAPER(
        "paper", "纸质",
        0xFFF4ECD8.toInt(), 0xFFEADFC4.toInt(), 0xFF3B3126.toInt(),
        0xFF8C7B64.toInt(), 0xFF8C5A2B.toInt(), 0xFFA8320F.toInt(),
        0x33C9A227, 0xFFEFE3C8.toInt(), 0xFFCDBD9E.toInt(), true,
    ),
    NIGHT(
        "night", "夜间纯黑",
        0xFF000000.toInt(), 0xFF0B0B0B.toInt(), 0xFFC9CED6.toInt(),
        0xFF4A5058.toInt(), 0xFF5AA9E6.toInt(), 0xFFFFC857.toInt(),
        0x222C4A66, 0xFF111111.toInt(), 0xFF333333.toInt(), false,
    ),
    GREEN(
        "green", "护眼绿",
        0xFFDDEBDD.toInt(), 0xFFCFE3CF.toInt(), 0xFF26382A.toInt(),
        0xFF7B947F.toInt(), 0xFF2F6B45.toInt(), 0xFFB3541E.toInt(),
        0x33A7C957, 0xFFE7F2E4.toInt(), 0xFFA9C6AA.toInt(), true,
    );

    companion object {
        fun of(id: String?): ReaderTheme = entries.firstOrNull { it.id == id } ?: DARK
    }
}

/** TTS 分块粒度。首块会额外压到 80 字以内，保证快速开口。 */
enum class ChunkPreset(val id: String, val label: String, val chars: Int) {
    SMALL("small", "小 · 80 字（CPU / Kokoro）", 80),
    MEDIUM("medium", "中 · 300 字（本地 GPU）", 300),
    LARGE("large", "大 · 2000 字（云端大模型）", 2000),
    CUSTOM("custom", "自定义", 0),
    ;

    companion object {
        fun of(id: String?): ChunkPreset = entries.firstOrNull { it.id == id } ?: MEDIUM
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

/** 一套命名的 TTS 端点。切换 profile 时 Base URL / Key / Model / Voice 一起切换。 */
data class TtsProfile(
    val name: String = "默认",
    val baseUrl: String = TtsConfig.DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = TtsConfig.DEFAULT_MODEL,
    val voice: String = TtsConfig.DEFAULT_VOICE,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("base", baseUrl)
        .put("key", apiKey)
        .put("model", model)
        .put("voice", voice)

    companion object {
        fun fromJson(o: JSONObject): TtsProfile = TtsProfile(
            name = o.optString("name").ifBlank { "默认" },
            baseUrl = o.optString("base", TtsConfig.DEFAULT_BASE),
            apiKey = o.optString("key", ""),
            model = o.optString("model", TtsConfig.DEFAULT_MODEL),
            voice = o.optString("voice", TtsConfig.DEFAULT_VOICE),
        )
    }
}

/** 本地设置：阅读外观 + TTS + 缓存。与书籍内容分开存。 */
class AppSettings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateTtsProfilesIfNeeded()
    }

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

    // ---------- TTS 分块 / 预取 ----------

    fun chunkPreset(): ChunkPreset = ChunkPreset.of(sp.getString(K_CHUNK_PRESET, null))

    fun saveChunkPreset(p: ChunkPreset) =
        sp.edit().putString(K_CHUNK_PRESET, p.id).apply()

    fun customChunkChars(): Int = sp.getInt(K_CHUNK_CHARS, 2000).coerceIn(40, 5000)

    fun saveCustomChunkChars(v: Int) =
        sp.edit().putInt(K_CHUNK_CHARS, v.coerceIn(40, 5000)).apply()

    fun chunkChars(): Int = chunkPreset().let {
        if (it == ChunkPreset.CUSTOM) customChunkChars() else it.chars
    }

    /** 预读取目标：后台最多领先播放进度多少分钟，防止云端无限调用。 */
    fun preloadMinutes(): Int = sp.getInt(K_PRELOAD_MIN, 5).coerceIn(1, 60)

    fun savePreloadMinutes(v: Int) =
        sp.edit().putInt(K_PRELOAD_MIN, v.coerceIn(1, 60)).apply()

    // ---------- TTS ----------

    /** 所有命名端点；至少返回一个默认项。 */
    fun ttsProfiles(): List<TtsProfile> {
        val raw = sp.getString(K_TTS_PROFILES, null)
        if (raw.isNullOrBlank()) return listOf(defaultTtsProfile())
        val arr = runCatching { JSONArray(raw) }.getOrNull()
            ?: return listOf(defaultTtsProfile())
        val list = ArrayList<TtsProfile>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(TtsProfile.fromJson(it)) }
        }
        return list.ifEmpty { listOf(defaultTtsProfile()) }
    }

    fun activeTtsProfileIndex(): Int {
        val n = ttsProfiles().size
        return sp.getInt(K_TTS_ACTIVE, 0).coerceIn(0, (n - 1).coerceAtLeast(0))
    }

    fun activeTtsProfile(): TtsProfile = ttsProfiles()[activeTtsProfileIndex()]

    fun saveTtsProfiles(profiles: List<TtsProfile>, activeIndex: Int) {
        val list = profiles.ifEmpty { listOf(defaultTtsProfile()) }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val active = activeIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        sp.edit()
            .putString(K_TTS_PROFILES, arr.toString())
            .putInt(K_TTS_ACTIVE, active)
            .apply()
    }

    fun tts(): TtsConfig {
        val p = activeTtsProfile()
        return TtsConfig(
            baseUrl = p.baseUrl,
            apiKey = p.apiKey,
            model = p.model,
            voice = p.voice,
            speed = sp.getFloat(K_TTS_SPEED, 1.0f).coerceIn(0.5f, 2.0f),
            timeoutSec = sp.getInt(K_TTS_TIMEOUT, 120).coerceIn(15, 1800),
        )
    }

    /** 兼容旧调用：更新当前 profile，并保存全局语速 / 超时。 */
    fun saveTts(c: TtsConfig) {
        val list = ttsProfiles().toMutableList()
        val i = activeTtsProfileIndex().coerceIn(0, list.size - 1)
        list[i] = list[i].copy(
            baseUrl = c.baseUrl.trim(),
            apiKey = c.apiKey.trim(),
            model = c.model.trim(),
            voice = c.voice.trim(),
        )
        saveTtsProfiles(list, i)
        saveTtsOptions(c)
    }

    fun saveTtsOptions(c: TtsConfig) = sp.edit()
        .putFloat(K_TTS_SPEED, c.speed.coerceIn(0.5f, 2.0f))
        .putInt(K_TTS_TIMEOUT, c.timeoutSec.coerceIn(15, 1800))
        .apply()

    fun defaultTtsProfile(): TtsProfile = TtsProfile(
        name = "默认",
        baseUrl = sp.getString(K_TTS_BASE, TtsConfig.DEFAULT_BASE) ?: TtsConfig.DEFAULT_BASE,
        apiKey = sp.getString(K_TTS_KEY, "") ?: "",
        model = sp.getString(K_TTS_MODEL, TtsConfig.DEFAULT_MODEL) ?: TtsConfig.DEFAULT_MODEL,
        voice = sp.getString(K_TTS_VOICE, TtsConfig.DEFAULT_VOICE) ?: TtsConfig.DEFAULT_VOICE,
    )

    /** 旧版本是单套 TTS 配置；首次升级时迁成一个名为「默认」的 profile。 */
    private fun migrateTtsProfilesIfNeeded() {
        if (sp.contains(K_TTS_PROFILES)) return
        val profile = TtsProfile(
            name = "默认",
            baseUrl = sp.getString(K_TTS_BASE, TtsConfig.DEFAULT_BASE) ?: TtsConfig.DEFAULT_BASE,
            apiKey = sp.getString(K_TTS_KEY, "") ?: "",
            model = sp.getString(K_TTS_MODEL, TtsConfig.DEFAULT_MODEL) ?: TtsConfig.DEFAULT_MODEL,
            voice = sp.getString(K_TTS_VOICE, TtsConfig.DEFAULT_VOICE) ?: TtsConfig.DEFAULT_VOICE,
        )
        saveTtsProfiles(listOf(profile), 0)
    }

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
        private const val K_CHUNK_PRESET = "chunk_preset"
        private const val K_CHUNK_CHARS = "chunk_chars"
        private const val K_PRELOAD_MIN = "preload_min"
        private const val K_TTS_PROFILES = "tts_profiles"
        private const val K_TTS_ACTIVE = "tts_active"
        private const val K_TTS_BASE = "tts_base"
        private const val K_TTS_KEY = "tts_key"
        private const val K_TTS_MODEL = "tts_model"
        private const val K_TTS_VOICE = "tts_voice"
        private const val K_TTS_SPEED = "tts_speed"
        private const val K_TTS_TIMEOUT = "tts_timeout"
        private const val K_LAST_BOOK = "last_book"
    }
}
