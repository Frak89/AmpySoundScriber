package org.ampy.app.jobs

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
import org.ampy.app.MainActivity
import org.ampy.app.R
import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory

/** Servizio in primo piano (tipo mediaProcessing) che mantiene vivo il processo durante i job lunghi. */
class ProcessingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = build(this, "Elaborazione in corso", 0.0, "")
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else startForeground(NOTIFICATION_ID, n)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    /** Android 15+: il sistema limita la durata dei servizi mediaProcessing; si chiude in modo ordinato. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AmpyLog.w(LogCategory.GENERAL, "fgs", "Timeout del servizio mediaProcessing: l'elaborazione continua solo con l'app aperta")
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "ampy-processing"
        const val NOTIFICATION_ID = 4242

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.channel_processing_desc)
                })
            }
        }

        fun build(ctx: Context, title: String, progress: Double, message: String): Notification {
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(message)
                .setProgress(1000, (progress * 1000).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
        }

        fun updateProgress(ctx: Context, title: String, progress: Double, message: String) {
            if (Build.VERSION.SDK_INT < 35) return
            runCatching {
                ensureChannel(ctx)
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(ctx, title, progress, message))
            }
        }
    }
}
