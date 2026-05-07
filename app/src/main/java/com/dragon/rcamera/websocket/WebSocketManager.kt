package com.dragon.rcamera.websocket

import android.util.Log
import org.java_websocket.WebSocket

enum class WsManagerMode {
    NONE,
    SERVER,
    CLIENT
}

class WebSocketManager {

    private var server: WsServer? = null
    private var client: WsClient? = null
    private var mode = WsManagerMode.NONE

    // Server callbacks
    var onServerMessageReceived: ((conn: WebSocket, message: WsMessage) -> Unit)? = null
    var onServerClientConnected: ((conn: WebSocket) -> Unit)? = null
    var onServerClientDisconnected: ((conn: WebSocket) -> Unit)? = null
    var onServerStateChanged: ((running: Boolean) -> Unit)? = null

    // Client callbacks
    var onClientMessageReceived: ((message: WsMessage) -> Unit)? = null
    var onClientStateChanged: ((state: WsClientState) -> Unit)? = null

    // ===== Server Mode =====

    fun startServer(port: Int, password: String): Boolean {
        if (mode == WsManagerMode.SERVER && server != null) return true
        stop()

        return try {
            val wsServer = WsServer(port, password)
            wsServer.onMessageReceived = { conn, msg ->
                onServerMessageReceived?.invoke(conn, msg)
            }
            wsServer.onClientConnected = { conn ->
                onServerClientConnected?.invoke(conn)
            }
            wsServer.onClientDisconnected = { conn ->
                onServerClientDisconnected?.invoke(conn)
            }
            wsServer.start()
            server = wsServer
            mode = WsManagerMode.SERVER
            onServerStateChanged?.invoke(true)
            Log.d(TAG, "Server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            false
        }
    }

    fun stopServer() {
        if (mode != WsManagerMode.SERVER) return
        val s = server ?: return
        try {
            s.stop(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Server stop interrupted, old thread may still be holding the port", e)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        server = null
        mode = WsManagerMode.NONE
        onServerStateChanged?.invoke(false)
        Log.d(TAG, "Server stopped")
    }

    fun isServerRunning(): Boolean = server != null

    fun getServerUrl(): String? = server?.getUrl()

    fun getServerUrlForAddress(addr: String): String? = server?.getUrlForAddress(addr)

    fun getServerIpInfo(): IpInfo? = server?.getIpInfo()

    fun getServerClientCount(): Int = server?.getConnectedClientCount() ?: 0

    fun getAuthenticatedConnections(): Set<WebSocket> = server?.getAuthenticatedConnections() ?: emptySet()

    fun setRtpPort(port: Int) {
        server?.rtpPort = port
    }

    fun sendToClient(conn: WebSocket, message: WsMessage) {
        server?.sendEncrypted(conn, message)
    }

    fun broadcastToClients(message: WsMessage) {
        server?.broadcastEncrypted(message)
    }

    // ===== Client Mode =====

    fun connectAsClient(serverUrl: String, password: String) {
        if (mode == WsManagerMode.CLIENT && client != null) {
            val existingClient = client!!
            if (existingClient.state == WsClientState.CONNECTED ||
                existingClient.state == WsClientState.CONNECTING ||
                existingClient.state == WsClientState.AUTHENTICATING
            ) {
                return
            }
        }
        stop()

        val wsClient = WsClient(serverUrl, password)
        wsClient.onMessageReceived = { msg ->
            onClientMessageReceived?.invoke(msg)
        }
        wsClient.onStateChanged = { state ->
            onClientStateChanged?.invoke(state)
        }
        client = wsClient
        mode = WsManagerMode.CLIENT
        wsClient.connect()
    }

    fun disconnectClient() {
        if (mode != WsManagerMode.CLIENT) return
        client?.disconnect()
        client = null
        mode = WsManagerMode.NONE
    }

    fun getClientState(): WsClientState = client?.state ?: WsClientState.DISCONNECTED

    fun sendCommand(action: String, payload: com.google.gson.JsonObject = com.google.gson.JsonObject()): Boolean {
        return client?.sendCommand(action, payload) ?: false
    }

    // ===== Common =====

    fun stop() {
        stopServer()
        disconnectClient()
    }

    companion object {
        private const val TAG = "WebSocketManager"
    }
}
