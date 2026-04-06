package com.uzaktanbildirim.mobile

import android.content.Context

class DeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote-control-store", Context.MODE_PRIVATE)

    private fun normalizedPcScope(pcId: String?): String = pcId?.trim().takeIf { !it.isNullOrEmpty() } ?: "unpaired"

    private fun pcScopedKey(baseKey: String, pcId: String?): String = "pc:${normalizedPcScope(pcId)}:$baseKey"

    fun migrateLegacyPcScopedStateIfNeeded(pcId: String?) {
        val scopedPcId = pcId?.trim().takeIf { !it.isNullOrEmpty() } ?: return
        if (preferences.getBoolean("pc_scoped_settings_migrated", false)) {
            return
        }

        val editor = preferences.edit()
        if (preferences.contains("clipboard_sync_enabled")) {
            editor.putBoolean(
                pcScopedKey("clipboard_sync_enabled", scopedPcId),
                preferences.getBoolean("clipboard_sync_enabled", false),
            )
        }
        if (preferences.contains("live_preview_enabled")) {
            editor.putBoolean(
                pcScopedKey("live_preview_enabled", scopedPcId),
                preferences.getBoolean("live_preview_enabled", false),
            )
        }
        if (preferences.contains("shortcut_items_json")) {
            editor.putString(
                pcScopedKey("shortcut_items_json", scopedPcId),
                preferences.getString("shortcut_items_json", "[]") ?: "[]",
            )
        }
        if (preferences.contains("remote_path")) {
            editor.putString(
                pcScopedKey("remote_path", scopedPcId),
                preferences.getString("remote_path", "") ?: "",
            )
        }
        editor.putBoolean("pc_scoped_settings_migrated", true).apply()
    }

    var workerUrl: String
        get() = preferences.getString("worker_url", "") ?: ""
        set(value) = preferences.edit().putString("worker_url", value.trim()).apply()

    var ownerToken: String
        get() = preferences.getString("owner_token", "") ?: ""
        set(value) = preferences.edit().putString("owner_token", value).apply()

    var pairedPcId: String
        get() = preferences.getString("paired_pc_id", "") ?: ""
        set(value) = preferences.edit().putString("paired_pc_id", value).apply()

    var pairedPcName: String
        get() = preferences.getString("paired_pc_name", "") ?: ""
        set(value) = preferences.edit().putString("paired_pc_name", value).apply()

    var deviceName: String
        get() = preferences.getString("device_name", android.os.Build.MODEL ?: "Android") ?: "Android"
        set(value) = preferences.edit().putString("device_name", value).apply()

    var fcmToken: String
        get() = preferences.getString("fcm_token", "") ?: ""
        set(value) = preferences.edit().putString("fcm_token", value).apply()

    fun getRemotePath(pcId: String?): String {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getString(pcScopedKey("remote_path", pcId), "") ?: ""
    }

    fun setRemotePath(pcId: String?, value: String) {
        preferences.edit().putString(pcScopedKey("remote_path", pcId), value.trim()).apply()
    }

    fun getClipboardSyncEnabled(pcId: String?): Boolean {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getBoolean(pcScopedKey("clipboard_sync_enabled", pcId), false)
    }

    fun setClipboardSyncEnabled(pcId: String?, value: Boolean) {
        preferences.edit().putBoolean(pcScopedKey("clipboard_sync_enabled", pcId), value).apply()
    }

    fun getEnabledClipboardSyncPcIds(): Set<String> {
        return preferences.all.entries
            .asSequence()
            .filter { (key, value) -> key.startsWith("pc:") && key.endsWith(":clipboard_sync_enabled") && value == true }
            .map { (key, _) -> key.removePrefix("pc:").removeSuffix(":clipboard_sync_enabled") }
            .filter { it.isNotBlank() && it != "unpaired" }
            .toSet()
    }

    fun getLivePreviewEnabled(pcId: String?): Boolean {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getBoolean(pcScopedKey("live_preview_enabled", pcId), false)
    }

    fun setLivePreviewEnabled(pcId: String?, value: Boolean) {
        preferences.edit().putBoolean(pcScopedKey("live_preview_enabled", pcId), value).apply()
    }

    fun getLivePreviewMode(pcId: String?): String {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getString(pcScopedKey("live_preview_mode", pcId), "original") ?: "original"
    }

    fun setLivePreviewMode(pcId: String?, value: String) {
        preferences.edit().putString(pcScopedKey("live_preview_mode", pcId), value.trim()).apply()
    }

    fun getCameraPreviewEnabled(pcId: String?): Boolean {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getBoolean(pcScopedKey("camera_preview_enabled", pcId), false)
    }

    fun setCameraPreviewEnabled(pcId: String?, value: Boolean) {
        preferences.edit().putBoolean(pcScopedKey("camera_preview_enabled", pcId), value).apply()
    }

    fun getCameraQualityMode(pcId: String?): String {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getString(pcScopedKey("camera_quality_mode", pcId), "hd_720") ?: "hd_720"
    }

    fun setCameraQualityMode(pcId: String?, value: String) {
        preferences.edit().putString(pcScopedKey("camera_quality_mode", pcId), value.trim()).apply()
    }

    fun getCameraLiveMode(pcId: String?): String {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getString(pcScopedKey("camera_live_mode", pcId), "session") ?: "session"
    }

    fun setCameraLiveMode(pcId: String?, value: String) {
        preferences.edit().putString(pcScopedKey("camera_live_mode", pcId), value.trim()).apply()
    }

    fun getSelectedCameraId(pcId: String?): String {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getString(pcScopedKey("selected_camera_id", pcId), "") ?: ""
    }

    fun setSelectedCameraId(pcId: String?, value: String) {
        preferences.edit().putString(pcScopedKey("selected_camera_id", pcId), value.trim()).apply()
    }

    fun getCameraMirrorEnabled(pcId: String?): Boolean {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getBoolean(pcScopedKey("camera_mirror_enabled", pcId), false)
    }

    fun setCameraMirrorEnabled(pcId: String?, value: Boolean) {
        preferences.edit().putBoolean(pcScopedKey("camera_mirror_enabled", pcId), value).apply()
    }

    var backgroundPermissionGranted: Boolean
        get() = preferences.getBoolean("background_permission_granted", false)
        set(value) = preferences.edit().putBoolean("background_permission_granted", value).apply()

    var backgroundPermissionLastCheckedAt: Long
        get() = preferences.getLong("background_permission_last_checked_at", 0L)
        set(value) = preferences.edit().putLong("background_permission_last_checked_at", value).apply()

    var lastLocalClipboardSignature: String
        get() = preferences.getString("last_local_clipboard_signature", "") ?: ""
        set(value) = preferences.edit().putString("last_local_clipboard_signature", value).apply()

    var workerR2SupportState: Int
        get() = preferences.getInt("worker_r2_support_state", -1)
        set(value) = preferences.edit().putInt("worker_r2_support_state", value).apply()

    var hasSeenFirstRunPrompt: Boolean
        get() = preferences.getBoolean("has_seen_first_run_prompt", false)
        set(value) = preferences.edit().putBoolean("has_seen_first_run_prompt", value).apply()

    fun getLastRemoteClipboardEventAt(pcId: String?): Long {
        return preferences.getLong(pcScopedKey("last_remote_clipboard_event_at", pcId), 0L)
    }

    fun setLastRemoteClipboardEventAt(pcId: String?, value: Long) {
        preferences.edit().putLong(pcScopedKey("last_remote_clipboard_event_at", pcId), value).apply()
    }

    var notificationDisplayLimit: Int
        get() = preferences.getInt("notification_display_limit", 5).coerceIn(5, 50)
        set(value) = preferences.edit().putInt("notification_display_limit", value.coerceIn(5, 50)).apply()

    fun getShortcutItemsJson(pcId: String?): String {
        migrateLegacyPcScopedStateIfNeeded(pcId)
        return preferences.getString(pcScopedKey("shortcut_items_json", pcId), "[]") ?: "[]"
    }

    fun setShortcutItemsJson(pcId: String?, value: String) {
        preferences.edit().putString(pcScopedKey("shortcut_items_json", pcId), value).apply()
    }

    fun markIncomingClipboardText(pcId: String?, text: String) {
        preferences.edit()
            .putString(pcScopedKey("incoming_clipboard_text", pcId), text)
            .putLong(pcScopedKey("incoming_clipboard_at", pcId), System.currentTimeMillis())
            .apply()
    }

    fun shouldSuppressClipboardEcho(pcId: String?, text: String, windowMs: Long = 8_000L): Boolean {
        val incomingText = preferences.getString(pcScopedKey("incoming_clipboard_text", pcId), null) ?: return false
        val incomingAt = preferences.getLong(pcScopedKey("incoming_clipboard_at", pcId), 0L)
        if (System.currentTimeMillis() - incomingAt > windowMs) {
            return false
        }

        return incomingText == text
    }

    fun clearIncomingClipboardMarker(pcId: String?) {
        preferences.edit()
            .remove(pcScopedKey("incoming_clipboard_text", pcId))
            .remove(pcScopedKey("incoming_clipboard_at", pcId))
            .apply()
    }
}
