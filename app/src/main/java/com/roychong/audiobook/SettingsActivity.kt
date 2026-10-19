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
import org.json.JSONObject
import com.roychong.audiobook.player.AudioPlayer
import com.roychong.audiobook.player.PlaybackService
import com.roychong.audiobook.tts.TtsCache
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
    private lateinit var etCacheMb: EditText
    private lateinit var spTheme: Spinner
    private lateinit var spFont: Spinner
    private lateinit var swAutoScroll: Switch
    private lateinit var tvTestStatus: TextView
    private lateinit var tvCache: TextView
    private lateinit var root: View

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
        etCacheMb = findViewById(R.id.etCacheMb)
        spTheme = findViewById(R.id.spTheme)
        spFont = findViewById(R.id.spFont)
        swAutoScroll = findViewById(R.id.swAutoScroll)
        tvTestStatus = findViewById(R.id.tvTestStatus)
        tvCache = findViewById(R.id.tvCache)

        spTheme.adapter = ThemedSpinnerAdapter(ReaderTheme.entries.map { it.label })
        spFont.adapter = ThemedSpinnerAdapter(
            listOf(getString(R.string.font_serif), getString(R.string.font_sans)),
        )

        findViewById<Button>(R.id.btnTest).setOnClickListener { testTts() }
        findViewById<Button>(R.id.btnPullModels).setOnClickListener { pullModels() }
        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            TtsCache(this, settings.cacheCapMb()).clear()
            toast(getString(R.string.cache_cleared))
            refreshCacheInfo()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetDefaults() }
    }

    private fun loadValues() {
        val c = settings.tts()
        etBase.setText(c.baseUrl)
        etKey.setText(c.apiKey)
        etModel.setText(c.model)
        etVoice.setText(c.voice)
        etSpeed.setText(String.format(Locale.US, "%.2f", c.speed))
        etTimeout.setText(c.timeoutSec.toString())
        etFontSize.setText(settings.fontSize().toString())
        etLineSpacing.setText(String.format(Locale.US, "%.2f", settings.lineSpacing()))
        etMargin.setText(settings.marginDp().toString())
        etCacheMb.setText(settings.cacheCapMb().toString())
        spTheme.setSelection(ReaderTheme.entries.indexOf(settings.theme()).coerceAtLeast(0))
        spFont.setSelection(if (settings.serif()) 0 else 1)
        swAutoScroll.isChecked = settings.autoScroll()
        refreshCacheInfo()
    }

    private fun refreshCacheInfo() {
        io.execute {
            val bytes = TtsCache(this, settings.cacheCapMb()).totalBytes()
            main.post {
                tvCache.text = getString(
                    R.string.cache_info_fmt,
                    formatSize(bytes),
                    "${settings.cacheCapMb()} MB",
                )
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
        settings.saveTts(readTts())
        theme = ReaderTheme.entries[spTheme.selectedItemPosition.coerceIn(0, 3)]
        settings.saveTheme(theme)
        settings.saveFontSize(etFontSize.text.toString().toIntOrNull() ?: 19)
        settings.saveLineSpacing(etLineSpacing.text.toString().toFloatOrNull() ?: 1.65f)
        settings.saveMarginDp(etMargin.text.toString().toIntOrNull() ?: 20)
        settings.saveSerif(spFont.selectedItemPosition == 0)
        settings.saveAutoScroll(swAutoScroll.isChecked)
        settings.saveCacheCapMb(etCacheMb.text.toString().toIntOrNull() ?: 600)
        applyColors()
        refreshCacheInfo()
        toast(getString(R.string.settings_saved))
    }

    private fun resetDefaults() {
        val t = TtsConfig()
        etBase.setText(t.baseUrl)
        etKey.setText("")
        etModel.setText(t.model)
        etVoice.setText(t.voice)
        etSpeed.setText(String.format(Locale.US, "%.2f", t.speed))
        etTimeout.setText(t.timeoutSec.toString())
        etFontSize.setText("19")
        etLineSpacing.setText("1.65")
        etMargin.setText("20")
        etCacheMb.setText("600")
        spTheme.setSelection(0)
        spFont.setSelection(0)
        swAutoScroll.isChecked = true
    }

    private fun applyColors() {
        ThemeUi.apply(this, theme)
        root.setBackgroundColor(theme.bg)
        tint(root)
        (spTheme.adapter as? ThemedSpinnerAdapter)?.notifyDataSetChanged()
        (spFont.adapter as? ThemedSpinnerAdapter)?.notifyDataSetChanged()
        spTheme.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.panel))
        spFont.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.panel))
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
            }
            is Button -> v.setTextColor(theme.accent)
            is TextView -> v.setTextColor(theme.text)
            is Spinner -> {}
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
