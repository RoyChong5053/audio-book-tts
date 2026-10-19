package com.roychong.audiobook.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.roychong.audiobook.MainActivity
import com.roychong.audiobook.R
import com.roychong.audiobook.ReaderActivity

/**
 * 前台播放服务：锁屏/通知栏控制、MediaSession、音频焦点与唤醒锁都在这里。
 * Activity 通过 [LocalBinder] 拿同一个 ReadingController，界面和服务不会各播各的。
 */
class PlaybackService : Service(), ReadingController.Listener {

    inner class LocalBinder : Binder() {
        fun controller(): ReadingController = reading
    }

    private val binder = LocalBinder()
    private lateinit var reading: ReadingController
    private lateinit var mediaSession: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock
    private var foreground = false
    private var lastNotifSentence = -1

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                reading.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        reading = ReadingController(this)
        reading.addListener(this)
        reading.onActiveChanged = { active ->
            if (active) {
                ensureForeground()
                acquireWakeLock(true)
            } else {
                acquireWakeLock(false)
                updateNotification(reading.state())
            }
        }

        mediaSession = MediaSession(this, "AudioBookTts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = reading.play()
                override fun onPause() = reading.pause()
                override fun onSkipToNext() = reading.nextSentence()
                override fun onSkipToPrevious() = reading.prevSentence()
                override fun onStop() {
                    reading.stop()
                    stopSelfSafely()
                }
            })
            isActive = true
        }

        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioBookTts:playback")
            .apply { setReferenceCounted(false) }

        runCatching {
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> reading.toggle()
            ACTION_PLAY -> reading.play()
            ACTION_PAUSE -> reading.pause()
            ACTION_NEXT -> reading.nextSentence()
            ACTION_PREV -> reading.prevSentence()
            ACTION_STOP -> {
                reading.stop()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_SLEEP_15 -> reading.setSleepTimer(15)
            ACTION_SLEEP_30 -> reading.setSleepTimer(30)
            ACTION_SLEEP_60 -> reading.setSleepTimer(60)
            ACTION_SLEEP_OFF -> reading.setSleepTimer(0)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        reading.removeListener(this)
        reading.release()
        mediaSession.release()
        acquireWakeLock(false)
        foreground = false
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        acquireWakeLock(false)
        if (foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foreground = false
        }
        stopSelf()
    }

    // ---------------- 状态同步 ----------------

    override fun onState(state: ReadingController.PlayerState) {
        updateMediaSession(state)
        updateNotification(state)
        if (state.bookId != null && !foreground) ensureForeground()
    }

    override fun onHighlight(chapter: Int, sentence: Int, fraction: Float) {
        if (sentence != lastNotifSentence) {
            lastNotifSentence = sentence
            updateNotification(reading.state())
        }
    }

    override fun onFinished() {
        updateNotification(reading.state())
    }

    private fun updateMediaSession(state: ReadingController.PlayerState) {
        val playing = state.playing || state.preparing
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                0L,
                if (playing) 1f else 0f,
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.chapterTitle.ifBlank { state.bookTitle })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.bookTitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "听书")
            .build()
        mediaSession.setMetadata(meta)
    }

    private fun ensureForeground() {
        val n = buildNotification(reading.state())
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.isSuccess
        if (ok) foreground = true
    }

    private fun updateNotification(state: ReadingController.PlayerState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = buildNotification(state)
        if (foreground) nm.notify(NOTIF_ID, n)
    }

    private fun buildNotification(state: ReadingController.PlayerState): Notification {
        val openIntent = if (state.bookId != null) {
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, state.bookId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, 1, openIntent, piFlags)
        val playing = state.playing || state.preparing

        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(state.bookTitle.ifBlank { "听书" })
            .setContentText(state.chapterTitle.ifBlank { "选择一本书开始朗读" })
            .setSubText(reading.currentSentenceText().take(64))
            .setContentIntent(contentPi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)

        val actions = ArrayList<Notification.Action>()
        actions.add(action(R.drawable.ic_prev, getString(R.string.notif_prev), ACTION_PREV, 11))
        actions.add(
            if (playing) action(R.drawable.ic_pause, getString(R.string.notif_pause), ACTION_PAUSE, 12)
            else action(R.drawable.ic_play, getString(R.string.notif_play), ACTION_PLAY, 12)
        )
        actions.add(action(R.drawable.ic_next, getString(R.string.notif_next), ACTION_NEXT, 13))
        actions.add(action(R.drawable.ic_stop, getString(R.string.notif_stop), ACTION_STOP, 14))
        actions.forEach { builder.addAction(it) }

        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        builder.style = style
        return builder.build()
    }

    private fun action(icon: Int, title: String, action: String, code: Int): Notification.Action {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        val pi = PendingIntent.getService(
            this, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = getString(R.string.notif_channel_desc)
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun acquireWakeLock(on: Boolean) {
        if (!this::wakeLock.isInitialized) return
        runCatching {
            if (on && !wakeLock.isHeld) wakeLock.acquire()
            if (!on && wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.roychong.audiobook.TOGGLE"
        const val ACTION_PLAY = "com.roychong.audiobook.PLAY"
        const val ACTION_PAUSE = "com.roychong.audiobook.PAUSE"
        const val ACTION_NEXT = "com.roychong.audiobook.NEXT"
        const val ACTION_PREV = "com.roychong.audiobook.PREV"
        const val ACTION_STOP = "com.roychong.audiobook.STOP"
        const val ACTION_SLEEP_15 = "com.roychong.audiobook.SLEEP15"
        const val ACTION_SLEEP_30 = "com.roychong.audiobook.SLEEP30"
        const val ACTION_SLEEP_60 = "com.roychong.audiobook.SLEEP60"
        const val ACTION_SLEEP_OFF = "com.roychong.audiobook.SLEEPOFF"

        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val i = Intent(context, PlaybackService::class.java)
            context.startService(i)
        }

        fun command(context: Context, action: String) {
            val i = Intent(context, PlaybackService::class.java).setAction(action)
            context.startService(i)
        }
    }
}
