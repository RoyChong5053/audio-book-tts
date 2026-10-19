package com.roychong.audiobook

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.roychong.audiobook.book.BookInfo
import com.roychong.audiobook.book.BookStore
import com.roychong.audiobook.player.PlaybackService
import com.roychong.audiobook.player.ReadingController
import com.roychong.audiobook.ui.ThemeUi
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ReadingController.Listener {

    private lateinit var store: BookStore
    private lateinit var settings: AppSettings
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: BookAdapter
    private lateinit var miniPlayer: View
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniChapter: TextView
    private lateinit var btnMiniPlay: TextView
    private var controller: ReadingController? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var importing = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? PlaybackService.LocalBinder ?: return
            val c = b.controller()
            controller = c
            bound = true
            c.addListener(this@MainActivity)
            onState(c.state())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controller = null
            bound = false
        }
    }

    private val openDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importBook(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BookStore(this)
        settings = AppSettings(this)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.listBooks)
        empty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progressImport)
        miniPlayer = findViewById(R.id.miniPlayer)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniChapter = findViewById(R.id.tvMiniChapter)
        btnMiniPlay = findViewById(R.id.btnMiniPlay)
        adapter = BookAdapter()
        list.adapter = adapter
        list.emptyView = empty

        list.setOnItemClickListener { _, _, position, _ ->
            val info = adapter.items.getOrNull(position) ?: return@setOnItemClickListener
            openReader(info.id)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val info = adapter.items.getOrNull(position) ?: return@setOnItemLongClickListener true
            showBookMenu(info)
            true
        }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            openDoc.launch(
                arrayOf(
                    "application/pdf",
                    "application/epub+zip",
                    "text/plain",
                    "text/markdown",
                    "text/x-markdown",
                    "text/html",
                    "application/octet-stream",
                )
            )
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnMiniPlay.setOnClickListener { controller?.toggle() }
        findViewById<View>(R.id.btnMiniPrev).setOnClickListener { controller?.prevSentence() }
        findViewById<View>(R.id.btnMiniNext).setOnClickListener { controller?.nextSentence() }
        findViewById<View>(R.id.btnMiniStop).setOnClickListener { controller?.stop() }
        miniPlayer.setOnClickListener {
            val id = controller?.state()?.bookId ?: return@setOnClickListener
            openReader(id)
        }
        findViewById<View>(R.id.miniInfo).setOnClickListener {
            val id = controller?.state()?.bookId ?: return@setOnClickListener
            openReader(id)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            runCatching {
                bindService(
                    Intent(this, PlaybackService::class.java),
                    conn,
                    Context.BIND_AUTO_CREATE,
                )
            }
        }
    }

    override fun onStop() {
        controller?.removeListener(this)
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        refresh()
    }

    private fun applyTheme() {
        val t = settings.theme()
        ThemeUi.apply(this, t)
        findViewById<View>(R.id.root).setBackgroundColor(t.bg)
        findViewById<TextView>(R.id.tvLibraryTitle).setTextColor(t.text)
        empty.setTextColor(t.dim)
        adapter.theme = t
        adapter.notifyDataSetChanged()
        progress.progressTintList = android.content.res.ColorStateList.valueOf(t.accent)
        findViewById<Button>(R.id.btnImport).backgroundTintList =
            android.content.res.ColorStateList.valueOf(t.accent)
        findViewById<Button>(R.id.btnImport).setTextColor(t.bg)
        miniPlayer.setBackgroundColor(t.panel)
        tvMiniTitle.setTextColor(t.text)
        tvMiniChapter.setTextColor(t.dim)
        btnMiniPlay.setTextColor(t.accent)
        findViewById<TextView>(R.id.btnMiniPrev).setTextColor(t.dim)
        findViewById<TextView>(R.id.btnMiniNext).setTextColor(t.dim)
        findViewById<TextView>(R.id.btnMiniStop).setTextColor(t.dim)
    }

    private fun refresh() {
        io.execute {
            val items = runCatching { store.library() }.getOrDefault(emptyList())
            main.post {
                adapter.items = items
                adapter.notifyDataSetChanged()
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun importBook(uri: Uri) {
        if (importing) return
        importing = true
        progress.visibility = View.VISIBLE
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        io.execute {
            val result = runCatching { store.import(uri) }
            main.post {
                importing = false
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            this, getString(R.string.import_done, it.book.title),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    },
                    onFailure = {
                        Toast.makeText(
                            this, getString(R.string.import_failed, it.message ?: "未知错误"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun openReader(bookId: String) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId))
    }

    private fun showBookMenu(info: BookInfo) {
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setItems(arrayOf(getString(R.string.open), getString(R.string.delete_book))) { _, which ->
                if (which == 0) openReader(info.id)
                else confirmDelete(info)
            }
            .show()
    }

    private fun confirmDelete(info: BookInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_book))
            .setMessage(getString(R.string.delete_book_confirm, info.title))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                io.execute {
                    store.delete(info.id)
                    main.post { refresh() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onState(state: ReadingController.PlayerState) {
        val show = state.bookId != null && !state.loading
        miniPlayer.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        tvMiniTitle.text = state.bookTitle.ifBlank { getString(R.string.app_name) }
        val suffix = when {
            state.error != null -> " · 出错"
            state.preloading -> " · 预加载中"
            state.buffering -> " · 缓冲中"
            state.preparing -> " · 合成中"
            else -> ""
        }
        tvMiniChapter.text = state.chapterTitle + suffix
        btnMiniPlay.text = if (state.playing || state.preloading) "❚❚" else "▶"
    }

    override fun onHighlight(chapter: Int, sentence: Int, fraction: Float) {}

    private inner class BookAdapter : BaseAdapter() {
        var items: List<BookInfo> = emptyList()
        var theme: ReaderTheme = ReaderTheme.DARK
        private val covers = HashMap<String, Bitmap?>()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_book, parent, false)
            val info = items[position]
            val cover = v.findViewById<ImageView>(R.id.ivCover)
            val letter = v.findViewById<TextView>(R.id.tvCoverLetter)
            val title = v.findViewById<TextView>(R.id.tvTitle)
            val author = v.findViewById<TextView>(R.id.tvAuthor)
            val bar = v.findViewById<ProgressBar>(R.id.progressBook)
            val pctText = v.findViewById<TextView>(R.id.tvProgress)

            title.setTextColor(theme.text)
            author.setTextColor(theme.dim)
            pctText.setTextColor(theme.dim)
            bar.progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            title.text = info.title
            val who = info.author.ifBlank { info.format.uppercase() }
            author.text = who

            val bmp = coverFor(info)
            if (bmp != null) {
                cover.setImageBitmap(bmp)
                cover.visibility = View.VISIBLE
                letter.visibility = View.GONE
            } else {
                cover.setImageDrawable(null)
                cover.setBackgroundColor(ThemeUi.withAlpha(theme.accent, 0x33))
                cover.visibility = View.VISIBLE
                letter.text = info.title.firstOrNull()?.toString() ?: "书"
                letter.setTextColor(theme.accent)
                letter.visibility = View.VISIBLE
            }

            val pos = store.progress(info.id)
            val pct = if (pos.updatedAt > 0) pos.percent.coerceIn(0, 100) else 0
            bar.progress = pct
            pctText.text = when {
                pos.updatedAt == 0L -> getString(R.string.book_no_progress)
                pct >= 99 -> getString(R.string.book_finished)
                else -> {
                    getString(R.string.book_progress_fmt, pct, relativeTime(pos.updatedAt))
                }
            }
            return v
        }

        private fun relativeTime(ts: Long): String {
            val diff = (System.currentTimeMillis() - ts).coerceAtLeast(0)
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3_600_000 -> "${diff / 60_000} 分钟前"
                diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
                diff < 30L * 86_400_000 -> "${diff / 86_400_000} 天前"
                else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                    .format(java.util.Date(ts))
            }
        }

        private fun coverFor(info: BookInfo): Bitmap? {
            if (covers.containsKey(info.id)) return covers[info.id]
            val path = info.coverPath
            if (path == null) {
                covers[info.id] = null
                return null
            }
            val f = File(path)
            if (!f.exists()) {
                covers[info.id] = null
                return null
            }
            val bmp = runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / sample > 240 || opts.outHeight / sample > 320) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
            }.getOrNull()
            covers[info.id] = bmp
            return bmp
        }
    }
}
