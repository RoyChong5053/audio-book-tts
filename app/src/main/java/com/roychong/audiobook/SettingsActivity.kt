package com.roychong.audiobook

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.AdapterView
import org.json.JSONObject
import com.roychong.audiobook.player.AudioPlayer
import com.roychong.audiobook.player.PlaybackService
import com.roychong.audiobook.tts.BookAudioStore
import com.roychong.audiobook.tts.Http
import com.roychong.audiobook.tts.TtsClient
import com.roychong.audiobook.ui.ThemeUi
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var player: AudioPlayer
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var testCanceller: TtsClient.Canceller? = null
    private var theme = ReaderTheme.DARK

    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etVoice: EditText
    private lateinit var etSpeed: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etFontSize: EditText
    private lateinit var etLineSpacing: EditText
    private lateinit var etMargin: EditText
    private lateinit var etChunkChars: EditText
    private lateinit var etPreloadMin: EditText
    private lateinit var spProfile: Spinner
    private lateinit var spTheme: Spinner
    private lateinit var spFont: Spinner
    private lateinit var spChunk: Spinner
    private lateinit var swAutoScroll: Switch
    private lateinit var tvTestStatus: TextView
    private lateinit var tvCache: TextView
    private lateinit var tvCachePath: TextView
    private lateinit var root: View

    private val profiles = ArrayList<TtsProfile>()
    private var activeProfile = 0
    private var renderingProfile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        player = AudioPlayer(this)
        theme = settings.theme()
        setContentView(R.layout.activity_settings)
        bind()
        loadValues()
        applyColors()
    }

    private fun bind() {
        root = findViewById(R.id.root)
        etBase = findViewById(R.id.etBase)
        etKey = findViewById(R.id.etKey)
        etModel = findViewById(R.id.etModel)
        etVoice = findViewById(R.id.etVoice)
        etSpeed = findViewById(R.id.etSpeed)
        etTimeout = findViewById(R.id.etTimeout)
        etFontSize = findViewById(R.id.etFontSize)
        etLineSpacing = findViewById(R.id.etLineSpacing)
        etMargin = findViewById(R.id.etMargin)
        etChunkChars = findViewById(R.id.etChunkChars)
        etPreloadMin = findViewById(R.id.etPreloadMin)
        spProfile = findViewById(R.id.spProfile)
        spTheme = findViewById(R.id.spTheme)
        spFont = findViewById(R.id.spFont)
        spChunk = findViewById(R.id.spChunk)
        swAutoScroll = findViewById(R.id.swAutoScroll)
        tvTestStatus = findViewById(R.id.tvTestStatus)
        tvCache = findViewById(R.id.tvCache)
        tvCachePath = findViewById(R.id.tvCachePath)

        spTheme.adapter = ThemedSpinnerAdapter(ReaderTheme.entries.map { it.label })
        spFont.adapter = ThemedSpinnerAdapter(
            listOf(getString(R.string.font_serif), getString(R.string.font_sans)),
        )
        spChunk.adapter = ThemedSpinnerAdapter(ChunkPreset.entries.map { it.label })
        spProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (renderingProfile) return
                if (position == activeProfile) return
                commitProfileFields()
                activeProfile = position.coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
                persistProfiles()
                renderProfileFields()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btnRenameProfile).setOnClickListener { renameProfile() }
        findViewById<Button>(R.id.btnAddProfile).setOnClickListener { addProfile() }
        findViewById<Button>(R.id.btnDupProfile).setOnClickListener { duplicateProfile() }
        findViewById<Button>(R.id.btnDelProfile).setOnClickListener { deleteProfile() }

        findViewById<Button>(R.id.btnTest).setOnClickListener { testTts() }
        findViewById<Button>(R.id.btnPullModels).setOnClickListener { pullModels() }
        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.cache_clear)
                .setMessage(R.string.cache_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    BookAudioStore(this).clearAll()
                    toast(getString(R.string.cache_cleared))
                    refreshCacheInfo()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetDefaults() }
    }

    private fun loadValues() {
        val c = settings.tts()
        profiles.clear()
        profiles.addAll(settings.ttsProfiles())
        activeProfile = settings.activeTtsProfileIndex()
        renderProfileList(activeProfile)
        etSpeed.setText(String.format(Locale.US, "%.2f", c.speed))
        etTimeout.setText(c.timeoutSec.toString())
        etFontSize.setText(settings.fontSize().toString())
        etLineSpacing.setText(String.format(Locale.US, "%.2f", settings.lineSpacing()))
        etMargin.setText(settings.marginDp().toString())
        spTheme.setSelection(ReaderTheme.entries.indexOf(settings.theme()).coerceAtLeast(0))
        spFont.setSelection(if (settings.serif()) 0 else 1)
        spChunk.setSelection(ChunkPreset.entries.indexOf(settings.chunkPreset()).coerceAtLeast(0))
        etChunkChars.setText(settings.customChunkChars().toString())
        etPreloadMin.setText(settings.preloadMinutes().toString())
        swAutoScroll.isChecked = settings.autoScroll()
        refreshCacheInfo()
    }

    private fun renderProfileList(select: Int) {
        if (profiles.isEmpty()) {
            profiles.add(TtsProfile())
        }
        activeProfile = select.coerceIn(0, profiles.size - 1)
        renderingProfile = true
        spProfile.adapter = ThemedSpinnerAdapter(profiles.map { it.name })
        spProfile.setSelection(activeProfile)
        renderingProfile = false
        renderProfileFields()
    }

    private fun renderProfileFields() {
        val p = profiles.getOrNull(activeProfile) ?: return
        etBase.setText(p.baseUrl)
        etKey.setText(p.apiKey)
        etModel.setText(p.model)
        etVoice.setText(p.voice)
    }

    private fun commitProfileFields() {
        val i = activeProfile.coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
        val old = profiles.getOrNull(i) ?: return
        profiles[i] = old.copy(
            baseUrl = etBase.text.toString().trim(),
            apiKey = etKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            voice = etVoice.text.toString().trim(),
        )
    }

    private fun uniqueProfileName(want: String, taken: List<TtsProfile>): String {
        val used = taken.map { it.name }.toSet()
        if (want !in used) return want
        var i = 2
        while ("$want ($i)" in used) i++
        return "$want ($i)"
    }

    private fun addProfile() {
        commitProfileFields()
        val name = uniqueProfileName(
            getString(R.string.profile_new, profiles.size + 1),
            profiles,
        )
        profiles.add(TtsProfile(name = name, baseUrl = "", apiKey = "", model = "", voice = ""))
        renderProfileList(profiles.size - 1)
        persistProfiles()
        etBase.requestFocus()
    }

    private fun duplicateProfile() {
        commitProfileFields()
        val src = profiles.getOrNull(activeProfile) ?: return
        val name = uniqueProfileName(getString(R.string.profile_copy, src.name), profiles)
        profiles.add(src.copy(name = name))
        renderProfileList(profiles.size - 1)
        persistProfiles()
    }

    private fun deleteProfile() {
        commitProfileFields()
        if (profiles.size <= 1) {
            toast(getString(R.string.profile_keep_one))
            return
        }
        profiles.removeAt(activeProfile.coerceIn(0, profiles.size - 1))
        renderProfileList((activeProfile - 1).coerceAtLeast(0))
        persistProfiles()
    }

    private fun renameProfile() {
        commitProfileFields()
        val current = profiles.getOrNull(activeProfile) ?: return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(current.name)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val others = profiles.filterIndexed { i, _ -> i != activeProfile }
                    profiles[activeProfile] = current.copy(
                        name = uniqueProfileName(raw, others),
                    )
                    renderProfileList(activeProfile)
                    persistProfiles()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persistProfiles() {
        settings.saveTtsProfiles(profiles, activeProfile)
    }

    private fun refreshCacheInfo() {
        io.execute {
            val store = BookAudioStore(this)
            val bytes = store.totalBytes()
            main.post {
                tvCache.text = "已用 ${formatSize(bytes)} · 不会自动删除"
                tvCachePath.text = "位置：${store.root.absolutePath}"
            }
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) {
            getString(R.string.unit_mb, bytes / 1024.0 / 1024.0)
        } else {
            getString(R.string.unit_kb, bytes / 1024.0)
        }

    private fun testTts() {
        val cfg = readTts()
        if (!cfg.configured) {
            toast(getString(R.string.tts_test_empty))
            return
        }
        testCanceller?.cancel()
        player.stop()
        // 正在听书时先暂停，避免测试音和朗读抢同一路 AudioTrack
        PlaybackService.command(this, PlaybackService.ACTION_PAUSE)
        val canceller = TtsClient.Canceller()
        testCanceller = canceller
        tvTestStatus.text = getString(R.string.tts_testing)
        io.execute {
            val result = runCatching {
                TtsClient.synthesize(
                    base = cfg.baseUrl,
                    key = cfg.apiKey,
                    model = cfg.model,
                    voice = cfg.voice,
                    text = "你好，这是一段测试语音。听书功能已经准备好了。",
                    speed = cfg.speed,
                    readTimeoutMs = cfg.timeoutSec * 1000,
                    canceller = canceller,
                )
            }
            main.post {
                testCanceller = null
                result.fold(
                    onSuccess = { audio ->
                        tvTestStatus.text = getString(R.string.tts_test_ok)
                        player.play(
                            contentType = audio.contentType,
                            bytes = audio.bytes,
                            onLevel = {},
                            onDone = { main.post { tvTestStatus.text = "" } },
                            speed = cfg.speed,
                        )
                    },
                    onFailure = {
                        val msg = getString(
                            R.string.tts_test_failed,
                            it.message ?: it.javaClass.simpleName,
                        )
                        tvTestStatus.text = msg
                        toast(msg)
                    }
                )
            }
        }
    }

    private fun pullModels() {
        val cfg = readTts()
        if (cfg.baseUrl.isBlank()) {
            toast(getString(R.string.tts_test_empty))
            return
        }
        tvTestStatus.text = getString(R.string.pulling_models)
        io.execute {
            val result = runCatching {
                val url = Http.endpoint(cfg.baseUrl, "models", "/models")
                val conn = Http.open(url, cfg.apiKey, "GET", readTimeoutMs = 20_000)
                try {
                    val code = conn.responseCode
                    val body = if (code in 200..299) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }
                    if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(160)}")
                    val arr = JSONObject(body).optJSONArray("data") ?: throw IllegalStateException("返回里没有 data")
                    val ids = ArrayList<String>()
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) ids.add(id)
                    }
                    ids
                } finally {
                    Http.close(conn)
                }
            }
            main.post {
                tvTestStatus.text = ""
                result.fold(
                    onSuccess = { ids ->
                        if (ids.isEmpty()) {
                            toast("没有可用模型")
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.pull_models))
                                .setItems(ids.toTypedArray()) { _, which ->
                                    etModel.setText(ids[which])
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    },
                    onFailure = {
                        val msg = getString(
                            R.string.pull_models_failed,
                            it.message ?: it.javaClass.simpleName,
                        )
                        tvTestStatus.text = msg
                        toast(msg)
                    }
                )
            }
        }
    }

    private fun readTts(): TtsConfig {
        val speed = etSpeed.text.toString().toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f
        val timeout = etTimeout.text.toString().toIntOrNull()?.coerceIn(15, 1800) ?: 120
        return TtsConfig(
            baseUrl = etBase.text.toString().trim(),
            apiKey = etKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            voice = etVoice.text.toString().trim(),
            speed = speed,
            timeoutSec = timeout,
        )
    }

    private fun saveAll() {
        commitProfileFields()
        val c = readTts()
        settings.saveTtsProfiles(profiles, activeProfile)
        settings.saveTtsOptions(c)
        theme = ReaderTheme.entries[spTheme.selectedItemPosition.coerceIn(0, 3)]
        settings.saveTheme(theme)
        settings.saveFontSize(etFontSize.text.toString().toIntOrNull() ?: 19)
        settings.saveLineSpacing(etLineSpacing.text.toString().toFloatOrNull() ?: 1.65f)
        settings.saveMarginDp(etMargin.text.toString().toIntOrNull() ?: 20)
        settings.saveSerif(spFont.selectedItemPosition == 0)
        settings.saveAutoScroll(swAutoScroll.isChecked)
        settings.saveChunkPreset(
            ChunkPreset.entries[spChunk.selectedItemPosition.coerceIn(0, ChunkPreset.entries.size - 1)]
        )
        settings.saveCustomChunkChars(etChunkChars.text.toString().toIntOrNull() ?: 2000)
        settings.savePreloadMinutes(etPreloadMin.text.toString().toIntOrNull() ?: 5)
        applyColors()
        refreshCacheInfo()
        toast(getString(R.string.settings_saved))
    }

    private fun resetDefaults() {
        val t = TtsConfig()
        profiles.clear()
        profiles.add(TtsProfile("默认"))
        renderProfileList(0)
        etSpeed.setText(String.format(Locale.US, "%.2f", t.speed))
        etTimeout.setText(t.timeoutSec.toString())
        etFontSize.setText("19")
        etLineSpacing.setText("1.65")
        etMargin.setText("20")
        spTheme.setSelection(0)
        spFont.setSelection(0)
        swAutoScroll.isChecked = true
        spChunk.setSelection(ChunkPreset.entries.indexOf(ChunkPreset.MEDIUM))
        etChunkChars.setText("2000")
        etPreloadMin.setText("5")
    }

    private fun applyColors() {
        ThemeUi.apply(this, theme)
        root.setBackgroundColor(theme.bg)
        tint(root)
        (spProfile.adapter as? ThemedSpinnerAdapter)?.notifyDataSetChanged()
        (spTheme.adapter as? ThemedSpinnerAdapter)?.notifyDataSetChanged()
        (spFont.adapter as? ThemedSpinnerAdapter)?.notifyDataSetChanged()
        (spChunk.adapter as? ThemedSpinnerAdapter)?.notifyDataSetChanged()
        spProfile.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.panel))
        spTheme.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.panel))
        spFont.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.panel))
        spChunk.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.panel))
        tvCachePath.setTextColor(theme.dim)
        findViewById<View>(R.id.btnSave).backgroundTintList = ColorStateList.valueOf(theme.accent)
        findViewById<Button>(R.id.btnSave).setTextColor(theme.bg)
        tvTestStatus.setTextColor(theme.dim)
        tvCache.setTextColor(theme.dim)
        val thumb = ColorStateList.valueOf(theme.accent)
        swAutoScroll.thumbTintList = thumb
        swAutoScroll.trackTintList = ColorStateList.valueOf(ThemeUi.withAlpha(theme.accent, 0x66))
    }

    private fun tint(v: View) {
        when (v) {
            is EditText -> {
                v.setTextColor(theme.text)
                v.setHintTextColor(theme.dim)
                v.background = fieldDrawable(false)
                v.minHeight = dp(50)
                v.setPadding(dp(14), 0, dp(14), 0)
                v.gravity = android.view.Gravity.CENTER_VERTICAL
                v.setOnFocusChangeListener { view, focused ->
                    view.background = fieldDrawable(focused)
                }
            }
            is Button -> v.setTextColor(theme.accent)
            is TextView -> v.setTextColor(theme.text)
            is Spinner -> {
                v.background = fieldDrawable(false)
                v.minimumHeight = dp(50)
                v.setPadding(dp(14), 0, dp(14), 0)
            }
            is ViewGroup -> {
                for (i in 0 until v.childCount) tint(v.getChildAt(i))
            }
        }
    }

    private inner class ThemedSpinnerAdapter(items: List<String>) :
        ArrayAdapter<String>(this, R.layout.item_spinner, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also {
                (it as TextView).setTextColor(theme.text)
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).also {
                (it as TextView).setTextColor(theme.text)
                it.setBackgroundColor(theme.panel)
            }
    }

    private fun fieldDrawable(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(theme.fieldBg)
        setStroke(dp(1), if (focused) theme.accent else theme.fieldBorder)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        testCanceller?.cancel()
        player.stop()
        // 正在听书时先暂停，避免测试音和朗读抢同一路 AudioTrack
        PlaybackService.command(this, PlaybackService.ACTION_PAUSE)
        io.shutdownNow()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
