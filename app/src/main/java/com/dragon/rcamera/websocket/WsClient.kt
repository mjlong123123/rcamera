package com.dragon.rcamera.websocket

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.crypto.SecretKey

enum class WsClientState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    AUTH_FAILED,
    ERROR
}

typealias WsClientMessageHandler = (message: WsMessage) -> Unit
typealias WsClientStateHandler = (state: WsClientState) -> Unit

class WsClient(
    private val serverUrl: String,
    private val password: String
) : WebSocketListener() {

    private val secretKey: SecretKey = CryptoUtil.deriveKey(password)
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    var onMessageReceived: WsClientMessageHandler? = null
    var onStateChanged: WsClientStateHandler? = null

    private var _state = WsClientState.DISCONNECTED
    val state: WsClientState get() = _state

    fun connect() {
        if (_state == WsClientState.CONNECTING || _state == WsClientState.AUTHENTICATING || _state == WsClientState.CONNECTED) {
            return
        }
        updateState(WsClientState.CONNECTING)
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, this)
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        updateState(WsClientState.DISCONNECTED)
    }

    fun sendCommand(action: String, payload: com.google.gson.JsonObject = com.google.gson.JsonObject()): Boolean {
        if (_state != WsClientState.CONNECTED) return false
        val message = WsMessage(
            type = WsMessage.TYPE_COMMAND,
            action = action,
            payload = payload
        )
        return sendEncrypted(message)
    }

    private fun sendEncrypted(message: WsMessage): Boolean {
        val ws = webSocket ?: return false
        val wire = message.toEncryptedWire(secretKey)
        return ws.send(wire)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "Connected to $serverUrl")
        updateState(WsClientState.AUTHENTICATING)
        // 发送加密的认证消息
        val authMsg = WsMessage(
            type = WsMessage.TYPE_COMMAND,
            action = WsMessage.ACTION_AUTH
        )
        sendEncrypted(authMsg)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val isEncrypted = text.startsWith("ENC:")
        val wsMessage = if (isEncrypted) {
            WsMessage.fromEncryptedWire(text, secretKey)
        } else {
            WsMessage.fromJson(text)
        }

        if (wsMessage == null) {
            Log.w(TAG, "Failed to parse message")
            return
        }

        when (wsMessage.action) {
            WsMessage.ACTION_AUTH_RESULT -> {
                val success = wsMessage.payload.get("success")?.asBoolean ?: false
                if (success) {
                    Log.d(TAG, "Authentication successful")
                    updateState(WsClientState.CONNECTED)
                } else {
                    Log.w(TAG, "Authentication failed")
                    updateState(WsClientState.AUTH_FAILED)
                    disconnect()
                }
            }
            else -> {
                onMessageReceived?.invoke(wsMessage)
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Closing: code=$code, reason=$reason")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Closed: code=$code, reason=$reason")
        updateState(WsClientState.DISCONNECTED)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "Connection failure", t)
        updateState(WsClientState.ERROR)
    }

    private fun updateState(newState: WsClientState) {
        _state = newState
        onStateChanged?.invoke(newState)
    }

    companion object {
        private const val TAG = "WsClient"
    }
}
