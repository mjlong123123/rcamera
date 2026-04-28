package com.dragon.rcamera.rtp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
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

    fun start() {
        try {
            socket = DatagramSocket()
            Log.d(TAG, "RTP sender started on port ${socket?.localPort}")
        } catch (e: SocketException) {
            Log.e(TAG, "Failed to start RTP sender", e)
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        destinations.clear()
        Log.d(TAG, "RTP sender stopped")
    }

    fun addDestination(clientId: String, host: String, rtpPort: Int) {
        try {
            val address = InetAddress.getByName(host)
            val socketAddr = java.net.InetSocketAddress(address, rtpPort)
            destinations[clientId] = socketAddr
            Log.d(TAG, "Added RTP destination: $clientId -> $host:$rtpPort")
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
     */
    fun sendH264AccessUnit(data: ByteArray, length: Int, presentationTimeUs: Long) {
        val sock = socket ?: return
        if (destinations.isEmpty()) return

        // Convert presentation time to RTP timestamp (90kHz clock)
        val rtpTimestamp = (presentationTimeUs / 1000 * clockRate / 1000) and 0xFFFFFFFFL

        // Find NALU boundaries (start codes 0x000001 or 0x00000001)
        val nalus = splitNalus(data, length)

        for (nalu in nalus) {
            sendNalu(nalu, rtpTimestamp, sock)
        }
    }

    private fun sendNalu(nalu: ByteArray, rtpTimestamp: Long, sock: DatagramSocket) {
        if (nalu.size <= RtpPacket.MAX_PAYLOAD_SIZE) {
            // Single NALU packet
            val marker = true // Last packet of frame (simplified)
            val packet = RtpPacket.build(
                payload = nalu,
                payloadLength = nalu.size,
                sequenceNumber = seqNum.incrementAndGet(),
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                marker = marker
            )
            sendPacket(packet, sock)
        } else {
            // Fragmentation Unit (FU-A) - RFC 6184 section 5.8
            sendFuA(nalu, rtpTimestamp, sock)
        }
    }

    private fun sendFuA(nalu: ByteArray, rtpTimestamp: Long, sock: DatagramSocket) {
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
                marker = lastPacket
            )
            sendPacket(packet, sock)
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
            i = naluStart
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
