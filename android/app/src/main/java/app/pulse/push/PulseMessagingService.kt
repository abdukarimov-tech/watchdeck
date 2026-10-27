package app.pulse.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import app.pulse.MainActivity

/**
 * Прием пушей от релея через FCM (упал контейнер / OOM). Для реальной работы
 * нужен Firebase-проект и google-services.json (см. README) — до этого класс
 * компилируется, но Firebase не инициализируется.
 */
class PulseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // TODO: отправить токен на релей: POST /api/devices {fcm_token, agent_id}
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val container = message.data["container"] ?: "контейнер"
        val kind = message.data["kind"] ?: ""
        val title = message.notification?.title
            ?: when (kind) {
                "CONTAINER_DIED" -> "Контейнер $container упал"
                "OOM" -> "Контейнер $container: нехватка памяти"
                else -> "Событие на сервере"
            }
        val body = message.notification?.body ?: message.data["message"].orEmpty()
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "События серверов", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "server_alerts"
        const val NOTIFICATION_ID = 42
    }
}
