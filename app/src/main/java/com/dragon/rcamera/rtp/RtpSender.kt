package com.dragon.rcamera.rtp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Sends H264 NAL units as RTP packets over UDP.
 * Handles NALU fragmentation for payloads > MAX_PAYLOAD_SIZE (FU-A).
 */
class RtpSender {

    private var socket: DatagramSocket? = null
    private val ssrc = (Math.random() * Int.MAX_VALUE).toInt()
    private val seqNum = AtomicInteger((Math.random() * 65535).toInt())
    private var timestampIncrement: Long = 0
    private val clockRate = 90000 // 90kHz for video

    // Destination addresses: clientId -> InetSocketAddress (host, rtpPort)
    private val destinations = ConcurrentHashMap<String, java.net.InetSocketAddress>()

    // Cached SPS/PPS config data - sent to new destinations on join
    @Volatile
    private var cachedConfigData: ByteArray? = null
    @Volatile
    private var cachedConfigPresentationTimeUs: Long = 0

    private val packetCount = AtomicInteger(0)

    fun start() {
        Log.d(TAG, "RtpSender starting")
        try {
            // Bind to IPv6 wildcard "::" for dual-stack (IPv4+IPv6) support.
            // On Android, a socket bound to "::" accepts both IPv4 and IPv6 traffic.
            socket = DatagramSocket(null)
            socket?.reuseAddress = true
            socket?.bind(InetSocketAddress("::", 0))
            Log.d(TAG, "RtpSender started on port ${socket?.localPort}, bound to :: (dual-stack)")
        } catch (e: SocketException) {
            Log.e(TAG, "RtpSender failed to start on ::, falling back", e)
            // Fallback to default (IPv4 only) if IPv6 binding fails
            try {
                socket = DatagramSocket()
                Log.d(TAG, "RtpSender started on port ${socket?.localPort}, bound to 0.0.0.0 (IPv4 only)")
            } catch (e2: SocketException) {
                Log.e(TAG, "RtpSender failed to start", e2)
            }
        }
    }

    fun stop() {
        Log.d(TAG, "RtpSender stopping")
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        destinations.clear()
        cachedConfigData = null
        packetCount.set(0)
        Log.d(TAG, "RtpSender stopped")
    }

    fun addDestination(clientId: String, host: String, rtpPort: Int) {
        try {
            val address = InetAddress.getByName(host)
            val socketAddr = java.net.InetSocketAddress(address, rtpPort)

            // Send cached SPS/PPS to the new destination BEFORE adding to destinations map.
            // This ensures the new client receives SPS/PPS before any regular frames
            // that the encoder thread might send once the destination is visible.
            val config = cachedConfigData
            if (config != null) {
                val sock = socket
                if (sock != null) {
                    Log.d(TAG, "Sending cached SPS/PPS (${config.size} bytes) to new destination $clientId BEFORE adding to map")
                    val rtpTimestamp = (cachedConfigPresentationTimeUs / 1000 * clockRate / 1000) and 0xFFFFFFFFL
                    val nalus = splitNalus(config, config.size)
                    for ((idx, nalu) in nalus.withIndex()) {
                        val isLast = idx == nalus.size - 1
                        sendNaluTo(nalu, rtpTimestamp, sock, socketAddr, isLast)
                    }
                }
            }

            // NOW add to destinations - encoder thread will start sending regular frames
            destinations[clientId] = socketAddr
            val addrType = when (address) {
                is Inet6Address -> "IPv6"
                else -> "IPv4"
            }
            Log.d(TAG, "Added RTP destination: $clientId -> $host:$rtpPort ($addrType), total=${destinations.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add destination: $host:$rtpPort", e)
        }
    }

    fun removeDestination(clientId: String) {
        destinations.remove(clientId)
        Log.d(TAG, "Removed RTP destination: $clientId, remaining: ${destinations.size}")
    }

    fun hasDestinations(): Boolean = destinations.isNotEmpty()

    fun getDestinationCount(): Int = destinations.size

    fun getLocalPort(): Int = socket?.localPort ?: 0

    /**
     * Send H264 access unit (one or more NALUs) as RTP packets.
     * Called for each frame from MediaCodec output.
     * When sending an IDR frame, cached SPS/PPS is prepended to ensure
     * the decoder always receives config data before the keyframe.
     */
    fun sendH264AccessUnit(data: ByteArray, length: Int, presentationTimeUs: Long, isConfig: Boolean = false) {
        val sock = socket ?: return

        // Cache SPS/PPS config data for new clients and IDR prepending
        if (isConfig) {
            cachedConfigData = data.copyOf(length)
            cachedConfigPresentationTimeUs = presentationTimeUs
            if (destinations.isEmpty()) {
                Log.d(TAG, "RtpSender cached SPS/PPS (${length} bytes), no destinations")
                return
            }
        }

        // Convert presentation time to RTP timestamp (90kHz clock)
        val rtpTimestamp = (presentationTimeUs / 1000 * clockRate / 1000) and 0xFFFFFFFFL

        // Find NALU boundaries (start codes 0x000001 or 0x00000001)
        val nalus = splitNalus(data, length)

        // Detect IDR frame: if any NALU is type 5, prepend cached SPS/PPS
        // This guarantees every IDR carries its config, even for clients that
        // joined between the encoder's separate SPS/PPS and IDR outputs.
        val isIdr = nalus.any { nalu -> nalu.isNotEmpty() && (nalu[0].toInt() and 0x1F) == 5 }
        val allNalus: List<ByteArray> = if (isIdr && !isConfig) {
            val configNalus = cachedConfigData?.let { splitNalus(it, it.size) } ?: emptyList()
            if (configNalus.isNotEmpty()) {
                Log.d(TAG, "RtpSender prepending ${configNalus.size} SPS/PPS NALUs before IDR frame")
            }
            configNalus + nalus
        } else {
            nalus
        }

        for ((idx, nalu) in allNalus.withIndex()) {
            val isLast = idx == allNalus.size - 1
            sendNalu(nalu, rtpTimestamp, sock, isLast)
        }

        val count = packetCount.incrementAndGet()
        if (count % 300 == 0) {
            Log.d(TAG, "RtpSender sent $count access units, destinations=${destinations.size}")
        }
    }

    private fun sendNalu(nalu: ByteArray, rtpTimestamp: Long, sock: DatagramSocket, isLast: Boolean = true) {
        if (nalu.size <= RtpPacket.MAX_PAYLOAD_SIZE) {
            // Single NALU packet - marker only set on last NALU of access unit
            val packet = RtpPacket.build(
                payload = nalu,
                payloadLength = nalu.size,
                sequenceNumber = seqNum.incrementAndGet(),
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                marker = isLast
            )
            sendPacket(packet, sock)
        } else {
            // Fragmentation Unit (FU-A) - RFC 6184 section 5.8
            sendFuA(nalu, rtpTimestamp, sock, null, isLast)
        }
    }

    /**
     * Send a NALU to a specific destination only (used for cached SPS/PPS to new clients).
     */
    private fun sendNaluTo(nalu: ByteArray, rtpTimestamp: Long, sock: DatagramSocket, dest: java.net.InetSocketAddress, isLast: Boolean = true) {
        if (nalu.size <= RtpPacket.MAX_PAYLOAD_SIZE) {
            val packet = RtpPacket.build(
                payload = nalu,
                payloadLength = nalu.size,
                sequenceNumber = seqNum.incrementAndGet(),
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                marker = isLast
            )
            sendPacketTo(packet, sock, dest)
        } else {
            sendFuA(nalu, rtpTimestamp, sock, dest, isLast)
        }
    }

    private fun sendFuA(nalu: ByteArray, rtpTimestamp: Long, sock: DatagramSocket, singleDest: java.net.InetSocketAddress? = null, isLast: Boolean = true) {
        val naluHeader = nalu[0].toInt() and 0xFF // NALU header byte as Int
        val naluType = naluHeader and 0x1F

        val maxChunkSize = RtpPacket.MAX_PAYLOAD_SIZE - 2 // 2 bytes for FU indicator + FU header
        var offset = 1 // Skip original NALU header

        var firstPacket = true
        var lastPacket = false

        while (offset < nalu.size) {
            val remaining = nalu.size - offset
            val chunkSize = minOf(remaining, maxChunkSize)
            lastPacket = (offset + chunkSize) >= nalu.size

            val payload = ByteArray(2 + chunkSize)

            // FU indicator: F=0, NRI from original, Type=28 (FU-A)
            payload[0] = ((naluHeader and 0xE0) or 0x1C).toByte()
            // FU header: S|E|R|Type
            payload[1] = ((if (firstPacket) 0x80 else 0x00) or
                    (if (lastPacket) 0x40 else 0x00) or
                    (naluType and 0x1F)).toByte()

            System.arraycopy(nalu, offset, payload, 2, chunkSize)
            offset += chunkSize

            val packet = RtpPacket.build(
                payload = payload,
                payloadLength = payload.size,
                sequenceNumber = seqNum.incrementAndGet(),
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                marker = lastPacket && isLast
            )
            if (singleDest != null) {
                sendPacketTo(packet, sock, singleDest)
            } else {
                sendPacket(packet, sock)
            }
            firstPacket = false
        }
    }

    private fun sendPacket(packet: ByteArray, sock: DatagramSocket) {
        val dp = DatagramPacket(packet, packet.size)
        for ((_, dest) in destinations) {
            try {
                dp.address = dest.address
                dp.port = dest.port
                sock.send(dp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send RTP packet to ${dest.address}:${dest.port}", e)
            }
        }
    }

    /**
     * Send packet to a single specific destination.
     */
    private fun sendPacketTo(packet: ByteArray, sock: DatagramSocket, dest: java.net.InetSocketAddress) {
        try {
            val dp = DatagramPacket(packet, packet.size, dest.address, dest.port)
            sock.send(dp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send RTP packet to ${dest.address}:${dest.port}", e)
        }
    }

    /**
     * Split H264 byte stream into individual NALUs by start codes.
     */
    private fun splitNalus(data: ByteArray, length: Int): List<ByteArray> {
        val nalus = mutableListOf<ByteArray>()
        var i = 0
        val end = length

        while (i < end) {
            // Find start code
            val startCodeLen = findStartCode(data, i, end) ?: break
            val naluStart = i + startCodeLen
            if (naluStart >= end) break

            // Find next start code
            val nextStart = findNextStartCode(data, naluStart, end) ?: end
            val naluLength = nextStart - naluStart

            if (naluLength > 0) {
                val nalu = ByteArray(naluLength)
                System.arraycopy(data, naluStart, nalu, 0, naluLength)
                nalus.add(nalu)
            }
            i = nextStart
        }

        // If no start code found, treat entire data as single NALU
        if (nalus.isEmpty() && length > 0) {
            val nalu = ByteArray(length)
            System.arraycopy(data, 0, nalu, 0, length)
            nalus.add(nalu)
        }

        return nalus
    }

    private fun findStartCode(data: ByteArray, offset: Int, end: Int): Int? {
        if (offset + 3 < end && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) {
            if (data[offset + 2] == 1.toByte()) return 3
            if (offset + 4 <= end && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) return 4
        }
        return null
    }

    private fun findNextStartCode(data: ByteArray, offset: Int, end: Int): Int? {
        for (i in offset until end - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i + 3 < end && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
        }
        return null
    }

    companion object {
        private const val TAG = "RtpSender"
    }
}
