package com.linguareader.engine.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.linguareader.MainActivity
import com.linguareader.R
import com.linguareader.core.reader.ReaderAudioController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Mantiene viva la lectura en voz alta con la pantalla bloqueada: servicio en primer plano (tipo `mediaPlayback`)
 * con notificación y botón de pausa, y un wake lock parcial mientras suena. Se detiene solo cuando la lectura
 * lleva unos segundos parada (entre páginas el estado pasa un instante por «completado»).
 */
@AndroidEntryPoint
class ReadingService : Service() {

    @Inject lateinit var audio: ReaderAudioController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            scope.launch { audio.pause() }
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinguaReader:reading")
                .apply { acquire(MAX_WAKE_MS) }
        }
        if (!watching) {
            watching = true
            scope.launch {
                audio.playbackState.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                    if (!playing) {
                        delay(STOP_AFTER_IDLE_MS)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel_reading), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, ReadingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(getString(R.string.notification_reading_aloud))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_pause, getString(R.string.player_pause), pause)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "reading"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_PAUSE = "com.linguareader.action.PAUSE_READING"
        private const val EXTRA_TITLE = "title"
        private const val STOP_AFTER_IDLE_MS = 5_000L
        private const val MAX_WAKE_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context, title: String) {
            runCatching {
                context.startForegroundService(Intent(context, ReadingService::class.java).putExtra(EXTRA_TITLE, title))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadingService::class.java))
        }
    }
}
