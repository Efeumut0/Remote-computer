package com.uzaktanbildirim.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class WorkerApi {
    fun getHealth(workerUrl: String): JSONObject {
        return JSONObject(
            request(
                url = "$workerUrl/health",
                method = "GET",
            ),
        )
    }

    fun pairDevice(
        workerUrl: String,
        deviceName: String,
        pairingCode: String,
        fcmToken: String,
        existingOwnerToken: String? = null,
    ): PairResult {
        val response = request(
            url = "$workerUrl/api/mobile/pair",
            method = "POST",
            bearerToken = existingOwnerToken,
            body = JSONObject()
                .put("pairingCode", pairingCode)
                .put("deviceName", deviceName)
                .put("fcmToken", fcmToken)
                .toString(),
        )

        val payload = JSONObject(response)
        val pc = payload.getJSONObject("pc")
        return PairResult(
            ownerToken = payload.getString("ownerToken"),
            pcId = pc.getString("id"),
            pcName = pc.optString("name", "PC"),
            status = pc.optString("status", "unknown"),
        )
    }

    fun registerToken(workerUrl: String, ownerToken: String, deviceName: String, fcmToken: String) {
        request(
            url = "$workerUrl/api/mobile/token",
            method = "POST",
            bearerToken = ownerToken,
            body = JSONObject()
                .put("deviceName", deviceName)
                .put("fcmToken", fcmToken)
                .toString(),
        )
    }

    fun getNotificationSettings(workerUrl: String, ownerToken: String): JSONObject {
        val response = request(
            url = "$workerUrl/api/mobile/notification-settings",
            method = "GET",
            bearerToken = ownerToken,
        )

        return JSONObject(response).getJSONObject("settings")
    }

    fun updateNotificationSettings(workerUrl: String, ownerToken: String, settings: JSONObject): JSONObject {
        val response = request(
            url = "$workerUrl/api/mobile/notification-settings",
            method = "POST",
            bearerToken = ownerToken,
            body = settings.toString(),
        )

        return JSONObject(response).getJSONObject("settings")
    }

    fun getNotificationCenter(workerUrl: String, ownerToken: String, limit: Int = 40): JSONObject {
        return JSONObject(
            request(
                url = "$workerUrl/api/mobile/notification-center?limit=$limit",
                method = "GET",
                bearerToken = ownerToken,
            ),
        )
    }

    fun markNotificationRead(workerUrl: String, ownerToken: String, notificationId: String) {
        request(
            url = "$workerUrl/api/mobile/notification-center/read",
            method = "POST",
            bearerToken = ownerToken,
            body = JSONObject().put("notificationId", notificationId).toString(),
        )
    }

    fun markAllNotificationsRead(workerUrl: String, ownerToken: String) {
        request(
            url = "$workerUrl/api/mobile/notification-center/read",
            method = "POST",
            bearerToken = ownerToken,
            body = JSONObject().put("markAll", true).toString(),
        )
    }

    fun listPcs(workerUrl: String, ownerToken: String): JSONArray {
        val response = request(
            url = "$workerUrl/api/mobile/pcs",
            method = "GET",
            bearerToken = ownerToken,
        )

        return JSONObject(response).getJSONArray("pcs")
    }

    fun getUsageSummary(workerUrl: String, ownerToken: String): JSONObject {
        val response = request(
            url = "$workerUrl/api/mobile/usage-summary",
            method = "GET",
            bearerToken = ownerToken,
        )

        return JSONObject(response).getJSONObject("usage")
    }

    fun unpairPc(workerUrl: String, ownerToken: String, pcId: String): JSONObject {
        val response = request(
            url = "$workerUrl/api/mobile/pc/unpair",
            method = "POST",
            bearerToken = ownerToken,
            body = JSONObject().put("pcId", pcId).toString(),
        )

        return JSONObject(response).getJSONObject("pc")
    }

    fun sendCommand(workerUrl: String, ownerToken: String, pcId: String, type: String, payload: JSONObject = JSONObject()): CommandDispatchResult {
        val response = request(
            url = "$workerUrl/api/mobile/commands",
            method = "POST",
            bearerToken = ownerToken,
            body = JSONObject()
                .put("pcId", pcId)
                .put("type", type)
                .put("payload", payload)
                .toString(),
        )

        val json = JSONObject(response)
        return CommandDispatchResult(
            commandId = json.getString("commandId"),
            status = json.optString("status", "queued"),
        )
    }

    fun getCommand(workerUrl: String, ownerToken: String, commandId: String): JSONObject {
        val response = request(
            url = "$workerUrl/api/mobile/commands/$commandId",
            method = "GET",
            bearerToken = ownerToken,
        )

        return JSONObject(response).getJSONObject("command")
    }

    fun sendCommandAndAwaitResult(
        workerUrl: String,
        ownerToken: String,
        pcId: String,
        type: String,
        payload: JSONObject = JSONObject(),
        timeoutMs: Long = 20_000,
        pollIntervalMs: Long = 600,
    ): JSONObject {
        val dispatch = sendCommand(workerUrl, ownerToken, pcId, type, payload)
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val command = getCommand(workerUrl, ownerToken, dispatch.commandId)
            when (command.optString("status", "queued")) {
                "completed", "failed", "cancelled" -> return command
            }

            Thread.sleep(pollIntervalMs)
        }

        throw IllegalStateException("Komut sonucu zamaninda donmedi: $type")
    }

    fun reserveFile(workerUrl: String, ownerToken: String, pcId: String, direction: String, fileName: String): FileReservation {
        val response = request(
            url = "$workerUrl/api/mobile/files/reserve",
            method = "POST",
            bearerToken = ownerToken,
            body = JSONObject()
                .put("pcId", pcId)
                .put("direction", direction)
                .put("fileName", fileName)
                .toString(),
        )

        val payload = JSONObject(response)
        return FileReservation(
            objectKey = payload.getString("objectKey"),
            fileName = payload.optString("fileName", fileName),
            uploadUrl = payload.optString("uploadUrl", ""),
            downloadUrl = payload.optString("downloadUrl", ""),
        )
    }

    fun uploadReservedFile(
        workerUrl: String,
        ownerToken: String,
        objectKey: String,
        bytes: ByteArray,
        contentType: String,
        progressListener: ((Long, Long) -> Unit)? = null,
    ) {
        binaryRequest(
            url = "$workerUrl/api/mobile/files/object/${encodePath(objectKey)}",
            method = "PUT",
            bearerToken = ownerToken,
            contentType = contentType,
            body = bytes,
            progressListener = progressListener,
        )
    }

    fun downloadReservedFile(
        workerUrl: String,
        ownerToken: String,
        objectKey: String,
        progressListener: ((Long, Long) -> Unit)? = null,
    ): DownloadedObject {
        return binaryRequest(
            url = "$workerUrl/api/mobile/files/object/${encodePath(objectKey)}",
            method = "GET",
            bearerToken = ownerToken,
            progressListener = progressListener,
        )
    }

    fun deleteReservedFile(workerUrl: String, ownerToken: String, objectKey: String) {
        request(
            url = "$workerUrl/api/mobile/files/object/${encodePath(objectKey)}",
            method = "DELETE",
            bearerToken = ownerToken,
        )
    }

    private fun request(
        url: String,
        method: String,
        bearerToken: String? = null,
        body: String? = null,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")

            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val response = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    reader.readText()
                }
            } ?: ""

            if (responseCode !in 200..299) {
                throw IllegalStateException(buildFriendlyHttpError(responseCode, response))
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun binaryRequest(
        url: String,
        method: String,
        bearerToken: String? = null,
        contentType: String? = null,
        body: ByteArray? = null,
        progressListener: ((Long, Long) -> Unit)? = null,
    ): DownloadedObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "*/*")

            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                connection.outputStream.use { output ->
                    val bufferSize = 16 * 1024
                    var written = 0
                    while (written < body.size) {
                        val chunkSize = minOf(bufferSize, body.size - written)
                        output.write(body, written, chunkSize)
                        written += chunkSize
                        progressListener?.invoke(written.toLong(), body.size.toLong())
                    }
                }
            }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            val responseBytes = stream?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var read = input.read(chunk)
                var totalRead = 0L
                while (read >= 0) {
                    if (read > 0) {
                        buffer.write(chunk, 0, read)
                        totalRead += read
                        progressListener?.invoke(totalRead, contentLength)
                    }
                    read = input.read(chunk)
                }
                buffer.toByteArray()
            } ?: ByteArray(0)

            if (connection.responseCode !in 200..299) {
                val errorText = responseBytes.toString(Charsets.UTF_8)
                throw IllegalStateException(buildFriendlyHttpError(connection.responseCode, errorText))
            }

            return DownloadedObject(
                bytes = responseBytes,
                contentType = connection.contentType ?: "application/octet-stream",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private fun buildFriendlyHttpError(responseCode: Int, response: String): String {
        val trimmed = response.trim()
        if (trimmed.isBlank()) {
            return "HTTP $responseCode"
        }

        val parsed = runCatching { JSONObject(trimmed) }.getOrNull()
        if (parsed != null) {
            val errorCode = parsed.optInt("error_code", -1)
            val title = parsed.optString("title")

            return when {
                errorCode == 1101 ->
                    "Worker hatasi (1101): backend kurulumunda eksik adim olabilir. d1-tablolari-onar-yardimcisi.bat calistirip Worker'i yeniden deploy et."
                responseCode == 401 || responseCode == 403 ->
                    "Yetki hatasi: oturum bilgisi gecersiz veya sure dolmus olabilir. PC ile yeniden esles."
                responseCode == 404 ->
                    "Kaynak bulunamadi: Worker URL, secili PC veya ilgili veri eksik olabilir."
                title.isNotBlank() ->
                    "$title (HTTP $responseCode)"
                else ->
                    "HTTP $responseCode"
            }
        }

        return when (responseCode) {
            401, 403 -> "Yetki hatasi: oturum bilgisi gecersiz veya sure dolmus olabilir. PC ile yeniden esles."
            404 -> "Kaynak bulunamadi: Worker URL, secili PC veya ilgili veri eksik olabilir."
            in 500..599 -> "Worker tarafinda sunucu hatasi var. Backend kurulumunu ve deploy adimlarini kontrol et."
            else -> trimmed
        }
    }

    data class PairResult(
        val ownerToken: String,
        val pcId: String,
        val pcName: String,
        val status: String,
    )

    data class CommandDispatchResult(
        val commandId: String,
        val status: String,
    )

    data class FileReservation(
        val objectKey: String,
        val fileName: String,
        val uploadUrl: String,
        val downloadUrl: String,
    )

    data class DownloadedObject(
        val bytes: ByteArray,
        val contentType: String,
    )
}
