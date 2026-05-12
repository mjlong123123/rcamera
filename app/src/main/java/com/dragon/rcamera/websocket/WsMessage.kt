package com.dragon.rcamera.websocket

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID
import javax.crypto.SecretKey

data class WsMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val action: String,
    val payload: JsonObject = JsonObject(),
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_COMMAND = "command"
        const val TYPE_RESPONSE = "response"
        const val TYPE_EVENT = "event"

        const val ACTION_AUTH = "auth"
        const val ACTION_AUTH_RESULT = "auth_result"
        const val ACTION_OPEN_CAMERA = "open_camera"
        const val ACTION_CLOSE_CAMERA = "close_camera"
        const val ACTION_CAMERA_STATUS = "camera_status"
        const val ACTION_PING = "ping"
        const val ACTION_PONG = "pong"
        const val ACTION_START_RTP = "start_rtp"
        const val ACTION_STOP_RTP = "stop_rtp"
        const val ACTION_RTP_INFO = "rtp_info"
        const val ACTION_REQUEST_KEYFRAME = "request_keyframe"

        private val gson = Gson()

        fun fromJson(json: String): WsMessage? {
            return try {
                gson.fromJson(json, WsMessage::class.java)
            } catch (_: Exception) {
                null
            }
        }

        fun fromEncryptedWire(wireText: String, key: SecretKey): WsMessage? {
            if (!wireText.startsWith("ENC:")) return null
            val cipherText = wireText.substring(4)
            val decrypted = CryptoUtil.decrypt(cipherText, key) ?: return null
            return fromJson(decrypted)
        }

        fun isEncryptedWireFormat(text: String): Boolean {
            return text.startsWith("ENC:")
        }
    }

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun toEncryptedWire(key: SecretKey): String {
        val json = toJson()
        val encrypted = CryptoUtil.encrypt(json, key)
        return "ENC:$encrypted"
    }

    fun makeResponse(success: Boolean, data: Map<String, Any> = emptyMap()): WsMessage {
        val payload = JsonObject()
        payload.addProperty("success", success)
        data.forEach { (k, v) ->
            when (v) {
                is String -> payload.addProperty(k, v)
                is Number -> payload.addProperty(k, v)
                is Boolean -> payload.addProperty(k, v)
            }
        }
        return WsMessage(
            id = id,
            type = TYPE_RESPONSE,
            action = "${action}_result",
            payload = payload
        )
    }
}
