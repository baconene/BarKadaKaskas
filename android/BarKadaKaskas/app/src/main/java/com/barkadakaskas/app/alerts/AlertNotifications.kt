package com.barkadakaskas.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.barkadakaskas.app.MainActivity
import com.barkadakaskas.app.R
import com.barkadakaskas.app.data.LaravelRepository
import com.barkadakaskas.app.data.TokenStore
import com.barkadakaskas.app.domain.Alert
import java.util.concurrent.TimeUnit

/**
 * Phone notifications for trip alerts. Without a push service, a background job checks the server about every
 * 15 minutes (Android's minimum) and shows alerts newer than the last one seen. Tapping one opens the trip on the
 * right tab. The app updates "last seen" whenever it loads alerts itself, so nothing is shown twice.
 */
object AlertNotifications {
    const val EXTRA_TRIP = "alert_trip_id"; const val EXTRA_TAB = "alert_tab"
    private const val CHANNEL = "trip-alerts"
    private const val PREFS = "alerts"; private const val LAST_SEEN = "last_seen_id"
    private const val WORK = "trip-alerts-check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlertsWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK) }

    fun lastSeen(context: Context): Long? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_SEEN, -1L).takeIf { it >= 0 }
    fun markSeen(context: Context, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (id > prefs.getLong(LAST_SEEN, -1L)) prefs.edit().putLong(LAST_SEEN, id).apply()
    }

    fun show(context: Context, alerts: List<Alert>) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Trip alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Comments, photos, expenses and itinerary changes on your trips" })
        }
        alerts.take(5).forEach { alert ->
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TRIP, alert.tripId).putExtra(EXTRA_TAB, alert.tab)
            val pending = PendingIntent.getActivity(context, alert.id.toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(listOfNotNull(alert.body, alert.tripName).joinToString(" · "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(alert.body, alert.tripName).joinToString("\n")))
                .setContentIntent(pending).setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(context).notify(alert.id.toInt(), notification) }
        }
    }
}

/** The background check behind [AlertNotifications]. */
class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = LaravelRepository(TokenStore(applicationContext))
        if (!repository.hasSession) return Result.success()
        return try {
            val seen = AlertNotifications.lastSeen(applicationContext)
            val page = repository.alerts(seen)
            val newest = page.alerts.maxOfOrNull { it.id }
            // First run: remember where we are without flooding the phone with old alerts.
            if (seen != null) AlertNotifications.show(applicationContext, page.alerts.filter { !it.read }.sortedBy { it.id })
            newest?.let { AlertNotifications.markSeen(applicationContext, it) } ?: if (seen == null) AlertNotifications.markSeen(applicationContext, 0) else Unit
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
