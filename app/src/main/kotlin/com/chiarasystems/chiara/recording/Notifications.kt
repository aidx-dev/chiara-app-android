package com.chiarasystems.chiara.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chiarasystems.chiara.ChiaraApplication
import com.chiarasystems.chiara.MainActivity
import com.chiarasystems.chiara.R
import com.chiarasystems.chiara.ai.PipelineBackground
import com.chiarasystems.chiara.ai.PipelineEvent

/**
 * Notifications of the recording and processing. They never carry a patient's name: they show on a locked screen.
 * Texts are translated with the language in use (the same catalog as the UI).
 */
object Notifier {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_PIPELINE = "pipeline"
    const val CHANNEL_EVENTS = "events"
    const val ID_RECORDING = 1001
    const val ID_PIPELINE = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val app = context.applicationContext as ChiaraApplication
        fun tr(key: String) = app.container.translator(app.container.settings.code).tr(key)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING, tr("Grabando consulta"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PIPELINE, tr("Procesando consultas"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_EVENTS, tr("Consultas"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun translator(context: Context): (String) -> String {
        val app = context.applicationContext as ChiaraApplication
        val t = app.container.translator(app.container.settings.code)
        return { t.tr(it) }
    }

    fun recording(context: Context): Notification {
        val tr = translator(context)
        return NotificationCompat.Builder(context, CHANNEL_RECORDING).setSmallIcon(R.drawable.ic_stat_chiara)
            .setContentTitle(tr("Grabando consulta")).setContentText(tr("Toca para volver a Chiara."))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(context))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    fun pipeline(context: Context, fraction: Double?, subtitle: String): Notification {
        val tr = translator(context)
        return NotificationCompat.Builder(context, CHANNEL_PIPELINE).setSmallIcon(R.drawable.ic_stat_chiara)
            .setContentTitle(tr("Procesando consultas")).setContentText(tr(subtitle))
            .setProgress(100, ((fraction ?: 0.0) * 100).toInt(), fraction == null)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(context))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    }

    /** Only when the app is not in front: in front, the list already shows the state. */
    fun post(context: Context, event: PipelineEvent) {
        val tr = translator(context)
        val (title, body) = when (event) {
            PipelineEvent.Finished -> tr("Consulta lista") to tr("Ya puedes revisar la transcripción.")
            PipelineEvent.ReportReady -> tr("Informe listo") to tr("Ya puedes revisar el informe de la consulta.")
            PipelineEvent.Failed -> tr("No se ha podido procesar una consulta") to tr("Se reintentará al abrir la app.")
            PipelineEvent.Deferred -> tr("Consulta pendiente") to tr("Con la pantalla bloqueada no se ha podido terminar. Se terminará al abrir la app.")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS).setSmallIcon(R.drawable.ic_stat_chiara)
            .setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(open(context))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(System.nanoTime().toInt(), n) }
    }
}

/** Keeps the process alive while the microphone is open (foreground service of type `microphone`). */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifier.ID_RECORDING, Notifier.recording(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, RecordingService::class.java)) }
    }
}

/** Keeps transcribing and writing reports while the app is in the background or the screen is locked (type `dataSync`). */
class PipelineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifier.ID_PIPELINE, Notifier.pipeline(this, null, "En cola…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }
}

/** What keeps the pipeline going with the screen locked: a foreground service with a progress notification. */
class AndroidPipelineBackground(private val context: Context) : PipelineBackground {
    override fun begin() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, PipelineService::class.java)) }
    }

    override fun update(fraction: Double, subtitle: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(Notifier.ID_PIPELINE, Notifier.pipeline(context, fraction, subtitle))
        }
    }

    override fun end(success: Boolean) {
        context.stopService(Intent(context, PipelineService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(Notifier.ID_PIPELINE)
    }
}
