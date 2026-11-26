package id.vern.antigravity.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import id.vern.antigravity.R
import id.vern.antigravity.ui.MainActivity

internal object ReplyNotification {
    private const val CHANNEL_ID = "agent_replies"
    private const val NOTIFICATION_ID = 1010
    private const val PREVIEW_LENGTH = 260
    private const val ACTION_OPEN_REPLY = "id.vern.antigravity.action.OPEN_REPLY"
    private const val CONVERSATION_EXTRA = "conversation_id"
    private val link = Regex("!?\\[([^]\\n]*)]\\([^\\n)]*\\)")
    private val lineMarker = Regex("(?m)^[ \\t]*(?:#{1,6}|[-+*>]|\\d+[.)])[ \\t]+")
    private val emphasis = Regex("(\\*{1,3}|_{1,3}|`{1,2})([^\\s].*?)\\1")
    private val whitespace = Regex("\\s+")

    fun conversationId(intent: Intent): String? =
        if (intent.action == ACTION_OPEN_REPLY) intent.getStringExtra(CONVERSATION_EXTRA) else null

    fun clearConversation(intent: Intent) {
        if (intent.action == ACTION_OPEN_REPLY) intent.removeExtra(CONVERSATION_EXTRA)
    }

    fun post(context: Context, conversationId: String, reply: String) {
        if (MainActivity.isVisible) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        val title = context.getString(R.string.reply_notification_title)
        val text = preview(reply).ifBlank { context.getString(R.string.reply_notification_fallback) }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_REPLY
            data = Uri.Builder().scheme("antigravity-reply").appendPath(conversationId).build()
            putExtra(CONVERSATION_EXTRA, conversationId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openConversation = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reply_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openConversation)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        try {
            manager.notify(conversationId, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification access can be revoked while a reply is being completed.
        }
    }

    private fun preview(reply: String): String {
        var fence: Char? = null
        val prose = reply.lineSequence().filter { line ->
            val trimmed = line.trimStart()
            val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
            if (marker != null && trimmed.take(3) == marker.toString().repeat(3)) {
                if (fence == null) fence = marker
                else if (fence == marker && trimmed.all { it == marker || it.isWhitespace() }) fence = null
                false
            } else {
                fence == null
            }
        }.joinToString("\n")
        val text = prose.replace(link, "$1")
            .replace(lineMarker, "")
            .replace(emphasis, "$2")
            .replace(whitespace, " ")
            .trim()
        if (text.length <= PREVIEW_LENGTH) return text
        val shortened = text.take(PREVIEW_LENGTH - 1)
        val wordEnd = shortened.lastIndexOf(' ')
        val prefix = if (wordEnd >= PREVIEW_LENGTH * 3 / 4) shortened.take(wordEnd) else shortened
        return prefix.trimEnd().trimEnd { it.isHighSurrogate() } + "…"
    }
}
