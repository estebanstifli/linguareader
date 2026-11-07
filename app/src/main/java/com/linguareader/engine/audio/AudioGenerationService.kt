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
import com.linguareader.MainActivity
import com.linguareader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Mantiene viva la generación del audio de un libro ([BookAudioService]) con la pantalla apagada: servicio en primer
 * plano con notificación de progreso y botón «Detener», y un wake lock parcial. Solo se arranca si el usuario aceptó
 * seguir con la pantalla apagada (diálogo de la ficha o Ajustes › Voz). Se detiene solo cuando no queda ninguna
 * generación en marcha (terminada, cancelada o fallida).
 *
 * Tipo `mediaProcessing` desde Android 15 (síntesis de audio) y `dataSync` antes; ambos con límite de 6 h diarias en
 * Android 15+: al agotarse ([onTimeout]) se detiene la generación, que se continúa desde la ficha.
 */
class AudioGenerationService : Service() {

    private val generation: BookAudioService by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false
    private var title = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            generation.running()?.let(generation::cancel)
            stopSelf()
            return START_NOT_STICKY
        }
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        // Directamente la API de plataforma: ServiceCompat.startForeground enmascara los tipos que no conoce
        // (mediaProcessing) y acaba pidiendo «type none», prohibido desde Android 14.
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                startForeground(NOTIFICATION_ID, notification(progress()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification(progress()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, notification(progress()))
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinguaReader:audio-generation")
                .apply { acquire(MAX_WAKE_MS) }
        }
        if (!watching) {
            watching = true
            scope.launch {
                generation.states
                    .map { states -> states.values.firstOrNull { it !is BookAudioState.Failed } }
                    .distinctUntilChanged()
                    .collect { state ->
                        if (state == null) stopSelf()
                        else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: se agotó el tiempo permitido al servicio; hay que parar en segundos. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        generation.running()?.let(generation::cancel)
        stopSelf()
    }

    private fun progress(): BookAudioState? = generation.states.value.values.firstOrNull { it !is BookAudioState.Failed }

    private fun notification(state: BookAudioState?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel_audio_generation), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AudioGenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record_voice_over)
            .setContentTitle(getString(R.string.notification_generating_audio, title.ifBlank { getString(R.string.app_name) }))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_pause, getString(R.string.book_audio_cancel), stop)
        when (state) {
            is BookAudioState.Running -> builder
                .setContentText(
                    if (state.waiting) getString(R.string.notification_generating_audio_waiting)
                    else getString(R.string.notification_generating_audio_progress, state.done, state.total),
                )
                .setProgress(state.total.coerceAtLeast(1), state.done, state.total == 0)
            else -> builder.setContentText(getString(R.string.book_audio_preparing)).setProgress(0, 0, true)
        }
        return builder.build()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "audio-generation"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "com.linguareader.action.STOP_AUDIO_GENERATION"
        private const val EXTRA_TITLE = "title"
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context, title: String) {
            runCatching {
                context.startForegroundService(Intent(context, AudioGenerationService::class.java).putExtra(EXTRA_TITLE, title))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioGenerationService::class.java))
        }
    }
}
