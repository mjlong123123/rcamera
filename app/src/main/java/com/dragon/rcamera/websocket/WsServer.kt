package com.dragon.rcamera.websocket

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import javax.crypto.SecretKey

typealias WsServerMessageHandler = (conn: WebSocket, message: WsMessage) -> Unit
typealias WsServerConnectionHandler = (conn: WebSocket) -> Unit

data class IpInfo(
    val ipv6Addresses: List<String>,      // All IPv6 addresses
    val ipv4Addresses: List<String>,      // All IPv4 addresses
    val preferredAddress: String,         // The preferred address (first global IPv6, or first non-global IPv6, or first IPv4)
    val isLan: Boolean                    // Whether only LAN addresses are available
) {
    /**
     * Generate WebSocket URL for the given address and port.
     */
    fun buildWsUrl(address: String, port: Int): String {
        val host = if (address.contains(":")) "[$address]" else address
        return "ws://$host:$port"
    }
}

class WsServer(
    port: Int,
    private val password: String
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        setReuseAddr(true)
    }

    private val secretKey: SecretKey = CryptoUtil.deriveKey(password)
    private val authenticatedConnections = mutableSetOf<WebSocket>()

    var onMessageReceived: WsServerMessageHandler? = null
    var onClientConnected: WsServerConnectionHandler? = null
    var onClientDisconnected: WsServerConnectionHandler? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}, code=$code, reason=$reason")
        authenticatedConnections.remove(conn)
        onClientDisconnected?.invoke(conn)
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        if (message == null) return

        val isEncrypted = message.startsWith("ENC:")
        val wsMessage = if (isEncrypted) {
            WsMessage.fromEncryptedWire(message, secretKey)
        } else {
            WsMessage.fromJson(message)
        }

        if (wsMessage == null) {
            Log.w(TAG, "Failed to parse message from ${conn.remoteSocketAddress}")
            if (isEncrypted) {
                sendPlain(conn, WsMessage(
                    type = WsMessage.TYPE_RESPONSE,
                    action = WsMessage.ACTION_AUTH_RESULT,
                    payload = com.google.gson.JsonObject().apply {
                        addProperty("success", false)
                        addProperty("error", "auth_failed")
                    }
                ))
                conn.close(4003, "Auth failed")
            }
            return
        }

        when (wsMessage.action) {
            WsMessage.ACTION_AUTH -> {
                if (isEncrypted) {
                    authenticatedConnections.add(conn)
                    onClientConnected?.invoke(conn)
                    // Include RTP port info in auth response
                    val response = wsMessage.makeResponse(true, mapOf(
                        "message" to "Authenticated",
                        "rtp_port" to (rtpPort ?: 0)
                    ))
                    sendEncrypted(conn, response)
                } else {
                    val response = wsMessage.makeResponse(false, mapOf("error" to "Encrypted auth required"))
                    sendPlain(conn, response)
                    conn.close(4003, "Auth failed")
                }
            }
            else -> {
                if (!authenticatedConnections.contains(conn)) {
                    sendPlain(conn, wsMessage.makeResponse(false, mapOf("error" to "Not authenticated")))
                    conn.close(4001, "Not authenticated")
                    return
                }
                onMessageReceived?.invoke(conn, wsMessage)
            }
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // 不处理二进制消息
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server started on port ${address.port}")
    }

    fun sendEncrypted(conn: WebSocket, message: WsMessage) {
        val wire = message.toEncryptedWire(secretKey)
        conn.send(wire)
    }

    private fun sendPlain(conn: WebSocket, message: WsMessage) {
        conn.send(message.toJson())
    }

    fun broadcastEncrypted(message: WsMessage) {
        for (conn in authenticatedConnections) {
            sendEncrypted(conn, message)
        }
    }

    fun getConnectedClientCount(): Int = authenticatedConnections.size

    fun getAuthenticatedConnections(): Set<WebSocket> = authenticatedConnections.toSet()

    // RTP port to include in auth response
    var rtpPort: Int? = null

    fun getUrl(): String {
        val port = address.port
        val ipInfo = getIpInfo()
        val addr = ipInfo.preferredAddress
        // IPv6 address needs brackets in URL
        val host = if (addr.contains(":")) "[$addr]" else addr
        return "ws://$host:$port"
    }

    /**
     * Get WebSocket URL for a specific IP address.
     */
    fun getUrlForAddress(addr: String): String {
        val port = address.port
        val host = if (addr.contains(":")) "[$addr]" else addr
        return "ws://$host:$port"
    }

    fun getIpInfo(): IpInfo {
        val ipv6List = mutableListOf<String>()
        val ipv4List = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return IpInfo(
                emptyList(), emptyList(), "0.0.0.0", false
            )
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    when (addr) {
                        is Inet6Address -> {
                            val cleanAddr = addr.hostAddress?.substringBefore("%") ?: continue
                            // Only collect global (non-LAN) IPv6 addresses
                            if (!isIpv6LanAddress(addr)) {
                                ipv6List.add(cleanAddr)
                            }
                        }
                        is Inet4Address -> {
                            addr.hostAddress?.let { ipv4List.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val preferred = when {
            ipv6List.isNotEmpty() -> ipv6List.first()
            ipv4List.isNotEmpty() -> ipv4List.first()
            else -> "0.0.0.0"
        }
        val isLan = ipv6List.isEmpty()

        return IpInfo(
            ipv6Addresses = ipv6List,
            ipv4Addresses = ipv4List,
            preferredAddress = preferred,
            isLan = isLan
        )
    }

    /**
     * Check if an IPv6 address is a LAN/local address.
     * Link-local (fe80::), Unique local (fc00::/7), etc.
     */
    private fun isIpv6LanAddress(addr: Inet6Address): Boolean {
        // Link-local addresses: fe80::/10
        // Unique local addresses: fc00::/7 (fc00:: and fd00::)
        // Site-local (deprecated): fec0::/10
        val bytes = addr.address
        val firstByte = bytes[0].toInt() and 0xFF

        // fe80::/10 - link local
        if (firstByte == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80) return true
        // fc00::/7 - unique local (fc00:: and fd00::)
        if ((firstByte and 0xFE) == 0xFC) return true
        // fec0::/10 - site local (deprecated but still used)
        if (firstByte == 0xFE && (bytes[1].toInt() and 0xC0) == 0xC0) return true

        return false
    }


    companion object {
        private const val TAG = "WsServer"
    }
}
