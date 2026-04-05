package com.uzaktanbildirim.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class RemoteFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val store = DeviceStore(applicationContext)
        store.fcmToken = token

        if (store.workerUrl.isNotBlank() && store.ownerToken.isNotBlank()) {
            runCatching {
                WorkerApi().registerToken(store.workerUrl, store.ownerToken, store.deviceName, token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val dataType = message.data["type"]
        if (dataType == "clipboard-sync") {
            val changedAt = message.data["changedAt"]?.toLongOrNull()
                ?: message.sentTime.takeIf { it > 0L }
            handleClipboardSyncMessage(message.data["pcId"], changedAt)
            return
        }

        ensureChannel()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_brand_logo_v2_mono)
            .setLargeIcon(createLogoBitmap())
            .setContentTitle(message.notification?.title ?: "Uzaktan Bildirim")
            .setContentText(message.notification?.body ?: "Yeni bir olay alindi.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun handleClipboardSyncMessage(pcId: String?, changedAt: Long?) {
        val store = DeviceStore(applicationContext)
        val targetPcId = pcId?.takeIf { it.isNotBlank() }
            ?: store.pairedPcId.takeIf { it.isNotBlank() }
            ?: store.getEnabledClipboardSyncPcIds().singleOrNull()
        if (targetPcId == null || !store.getClipboardSyncEnabled(targetPcId) || store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            return
        }

        val eventAt = changedAt ?: System.currentTimeMillis()
        if (eventAt <= store.getLastRemoteClipboardEventAt(targetPcId)) {
            return
        }

        runCatching {
            val command = WorkerApi().sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = targetPcId,
                type = "clipboard-get",
                payload = JSONObject(),
                timeoutMs = 8_000,
                pollIntervalMs = 400,
            )
            val payload = command.optJSONObject("result")
                ?.optJSONObject("payload")
                ?: command.optJSONObject("payload")
                ?: JSONObject()
            val text = payload.optString("text", "")
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val currentText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            store.setLastRemoteClipboardEventAt(targetPcId, eventAt)
            if (text == currentText) {
                store.markIncomingClipboardText(targetPcId, text)
                return@runCatching
            }
            store.markIncomingClipboardText(targetPcId, text)
            if (text.isBlank()) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("remote-clipboard", ""))
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("remote-clipboard", text))
            }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote Notifications",
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }

    private fun createLogoBitmap(): Bitmap? {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_app_brand_logo_v2) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private const val CHANNEL_ID = "remote_notifications"
    }
}
