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
import app.pulse.R

/**
 * Прием пушей от релея через FCM. Релей отправляет data-only сообщение
 * {kind, container, agent_id, message} — текст уведомления собирается
 * на устройстве по локали пользователя (сервер не знает язык клиента).
 *
 * Для реальной работы нужен Firebase-проект и google-services.json (см. RELEASE.md).
 */
class PulseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrationJob.schedule(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val container = data["container"] ?: return
        val kind = data["kind"] ?: ""
        val agentId = data["agent_id"].orEmpty()
        val detail = data["message"].orEmpty()

        val title = when (kind) {
            "CONTAINER_DIED" -> getString(R.string.alert_container_died, container)
            "OOM" -> getString(R.string.alert_oom, container)
            "RESOURCE_HIGH" -> container
            "RESOURCE_RECOVERED" -> getString(R.string.incidents_resolved)
            "CONTAINER_UNHEALTHY" -> getString(R.string.incident_unhealthy)
            "RESTART_LOOP" -> getString(R.string.incident_restart_loop)
            "SERVER_OFFLINE" -> getString(R.string.alert_server_offline, agentId.ifEmpty { container })
            "SERVER_ONLINE" -> getString(R.string.alert_server_online, agentId.ifEmpty { container })
            else -> getString(R.string.alert_generic, agentId.ifEmpty { container })
        }
        val body = if (detail.isEmpty() || agentId.isEmpty()) {
            detail
        } else {
            getString(R.string.alert_body_suffix, detail, agentId)
        }
        app.pulse.widget.WidgetSnapshots.recordIncident(this, agentId, body)
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH),
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
