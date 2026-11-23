package id.vern.antigravity.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import id.vern.antigravity.R
import id.vern.antigravity.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

object ForegroundNotification {

    const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "agy_core_service_channel"

    internal class Execution(val text: String)

    private data class ExecutionStatus(val execution: Execution, val text: String)
    private val executionStatuses = MutableStateFlow<List<ExecutionStatus>>(emptyList())

    internal fun beginExecution(text: String): Execution {
        val execution = Execution(text)
        executionStatuses.update { it + ExecutionStatus(execution, text) }
        return execution
    }

    internal fun updateExecution(execution: Execution, text: String = execution.text) {
        executionStatuses.update { statuses ->
            val previous = statuses.find { it.execution === execution }
            if (previous == null || previous.text == text) return@update statuses
            statuses.filterNot { it.execution === execution } + ExecutionStatus(execution, text)
        }
    }

    internal fun endExecution(execution: Execution) {
        executionStatuses.update { statuses -> statuses.filterNot { it.execution === execution } }
    }

    internal suspend fun observeExecution(service: Service) {
        executionStatuses.map { it.lastOrNull()?.text }.distinctUntilChanged().collect { text ->
            update(service, text)
            // Let StateFlow keep the latest status during bursts of tool events.
            delay(300)
        }
    }

    internal suspend fun <T> trackCommand(context: Context, command: String, run: suspend () -> T): T {
        val execution = beginExecution(context.getString(R.string.notification_agent_command, command))
        try {
            return run()
        } finally {
            endExecution(execution)
        }
    }

    fun start(service: Service, statusText: String? = null) {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = service.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val text = statusText ?: service.getString(R.string.notification_engine_active)
        val notification = buildNotification(service, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun update(context: Context, statusText: String?) {
        val text = statusText?.let {
            if (it.length > 400) it.take(399) + "…" else it
        } ?: context.getString(R.string.notification_engine_running)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(context, text))
    }

    fun buildNotification(context: Context, statusText: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, CoreServerService::class.java).apply {
            action = CoreServerService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            101,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val restartIntent = Intent(context, CoreServerService::class.java).apply {
            action = CoreServerService.ACTION_RESTART
        }
        val restartPendingIntent = PendingIntent.getService(
            context,
            102,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_action_stop,
                context.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .addAction(
                R.drawable.ic_action_restart,
                context.getString(R.string.notification_action_restart),
                restartPendingIntent
            )
            .build()
    }
}

