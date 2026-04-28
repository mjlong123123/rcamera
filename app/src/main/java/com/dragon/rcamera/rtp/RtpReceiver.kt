package com.dragon.rcamera.rtp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * RTP receiver that receives RTP packets and reassembles H264 frames.
 * Used on the client side.
 */
class RtpReceiver {

    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var isRunning = false

    var onFrameReceived: ((ByteArray) -> Unit)? = null

    // Reassembly state
    private var currentTimestamp: Long = -1
    private val naluBuffer = mutableListOf<ByteArray>()
    private val lock = Object()

    // Stats for debugging
    @Volatile private var receivedPacketCount = 0
    @Volatile private var deliveredFrameCount = 0
    @Volatile private var lastFrameSize = 0

    fun getStats(): String = "pkts=$receivedPacketCount frames=$deliveredFrameCount lastSize=$lastFrameSize"

    fun start(localPort: Int) {
        if (isRunning) return
        try {
            // Bind to IPv6 wildcard "::" for dual-stack (IPv4+IPv6) support.
            // If localPort is 0, the system picks an available port.
            socket = DatagramSocket(null)
            socket?.reuseAddress = true
            socket?.bind(InetSocketAddress("::", localPort))
            socket?.soTimeout = 5000
            isRunning = true

            receiveThread = Thread({
                receiveLoop()
            }, "RtpReceiveThread").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "RTP receiver started on port ${socket?.localPort}, bound to :: (dual-stack)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTP receiver on ::, falling back", e)
            // Fallback to IPv4 only if IPv6 binding fails
            try {
                socket = DatagramSocket(localPort)
                socket?.soTimeout = 5000
                isRunning = true
                Log.d(TAG, "RTP receiver started on port ${socket?.localPort}, bound to 0.0.0.0 (IPv4 only)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start RTP receiver", e2)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        try {
            receiveThread?.join(1000)
        } catch (_: Exception) {}
        receiveThread = null
        synchronized(lock) {
            naluBuffer.clear()
        }
        currentTimestamp = -1
        receivedPacketCount = 0
        deliveredFrameCount = 0
        lastFrameSize = 0
        Log.d(TAG, "RTP receiver stopped")
    }

    fun getLocalPort(): Int = socket?.localPort ?: 0

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)

        // FU-A reassembly state
        var fuAInProgress = false
        var fuABuffer = ByteArray(0)
        var fuATimestamp: Long = -1
        var fuASeqNum = -1

        while (isRunning) {
            try {
                socket?.receive(packet) ?: break

                val data = packet.data
                val length = packet.length
                if (length < RtpPacket.HEADER_SIZE) continue

                receivedPacketCount++
                if (receivedPacketCount == 1) {
                    Log.d(TAG, "First RTP packet received! From ${packet.address}:${packet.port}, length=$length")
                }
                if (receivedPacketCount % 300 == 0) {
                    Log.d(TAG, "RTP stats: received $receivedPacketCount packets, delivered $deliveredFrameCount frames")
                }

                // Parse RTP header
                val bufWrap = ByteBuffer.wrap(data, 0, length)
                val byte0 = bufWrap.get().toInt() and 0xFF
                val byte1 = bufWrap.get().toInt() and 0xFF
                val seqNum = bufWrap.short.toInt() and 0xFFFF
                val timestamp = (bufWrap.int.toLong()) and 0xFFFFFFFFL
                // Skip SSRC
                bufWrap.int

                val marker = (byte1 and 0x80) != 0
                val payloadType = byte1 and 0x7F
                val payloadOffset = RtpPacket.HEADER_SIZE
                val payloadLength = length - RtpPacket.HEADER_SIZE

                if (payloadLength <= 0) continue

                val payload = ByteArray(payloadLength)
                System.arraycopy(data, payloadOffset, payload, 0, payloadLength)

                // Timestamp changed -> deliver previous frame
                if (currentTimestamp != -1L && timestamp != currentTimestamp) {
                    deliverFrame()
                }
                currentTimestamp = timestamp

                // Check NALU type
                val naluType = payload[0].toInt() and 0x1F

                when {
                    naluType in 1..23 -> {
                        // Single NALU packet
                        addNalu(payload)
                        if (marker) {
                            deliverFrame()
                        }
                    }
                    naluType == 28 -> {
                        // FU-A
                        if (payload.size < 2) continue
                        val fuHeader = payload[1].toInt() and 0xFF
                        val startBit = (fuHeader and 0x80) != 0
                        val endBit = (fuHeader and 0x40) != 0
                        val origNaluType = fuHeader and 0x1F
                        val naluHeaderByte = payload[0].toInt() and 0xFF

                        if (startBit) {
                            // Start of FU-A
                            fuAInProgress = true
                            fuATimestamp = timestamp
                            fuASeqNum = seqNum
                            // Reconstruct NALU header: F|NRI from FU indicator, Type from FU header
                            val reconstructedHeader = ((naluHeaderByte and 0xE0) or origNaluType).toByte()
                            fuABuffer = ByteArray(1 + (payload.size - 2))
                            fuABuffer[0] = reconstructedHeader
                            System.arraycopy(payload, 2, fuABuffer, 1, payload.size - 2)
                        } else if (fuAInProgress && seqNum == fuASeqNum + 1) {
                            // Continuation of FU-A (simplified seq check)
                            fuASeqNum = seqNum
                            val oldLen = fuABuffer.size
                            val newBuf = ByteArray(oldLen + payload.size - 2)
                            System.arraycopy(fuABuffer, 0, newBuf, 0, oldLen)
                            System.arraycopy(payload, 2, newBuf, oldLen, payload.size - 2)
                            fuABuffer = newBuf
                        } else {
                            // Out of order or lost, reset
                            fuAInProgress = false
                            fuABuffer = ByteArray(0)
                        }

                        if (endBit && fuAInProgress) {
                            addNalu(fuABuffer)
                            fuAInProgress = false
                            fuABuffer = ByteArray(0)
                            if (marker) {
                                deliverFrame()
                            }
                        }
                    }
                    naluType == 24 -> {
                        // STAP-A - multiple NALUs in one packet
                        var offset = 1
                        while (offset + 1 < payload.size) {
                            val naluSize = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                            offset += 2
                            if (offset + naluSize <= payload.size) {
                                val stapNalu = ByteArray(naluSize)
                                System.arraycopy(payload, offset, stapNalu, 0, naluSize)
                                addNalu(stapNalu)
                                offset += naluSize
                            } else {
                                break
                            }
                        }
                        if (marker) {
                            deliverFrame()
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unsupported NALU type: $naluType")
                    }
                }

            } catch (e: SocketTimeoutException) {
                // Normal timeout, continue
                continue
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "RTP receive error", e)
                }
            }
        }
    }

    private fun addNalu(nalu: ByteArray) {
        synchronized(lock) {
            naluBuffer.add(nalu)
        }
    }

    private fun deliverFrame() {
        synchronized(lock) {
            if (naluBuffer.isEmpty()) return

            // Combine all NALUs with start codes to form an access unit
            var totalSize = 0
            for (nalu in naluBuffer) {
                totalSize += 4 + nalu.size // 4-byte start code + NALU
            }

            val frame = ByteArray(totalSize)
            var offset = 0
            for (nalu in naluBuffer) {
                // Start code: 0x00000001
                frame[offset] = 0
                frame[offset + 1] = 0
                frame[offset + 2] = 0
                frame[offset + 3] = 1
                System.arraycopy(nalu, 0, frame, offset + 4, nalu.size)
                offset += 4 + nalu.size
            }

            naluBuffer.clear()
            deliveredFrameCount++
            lastFrameSize = totalSize
            onFrameReceived?.invoke(frame)
        }
    }

    companion object {
        private const val TAG = "RtpReceiver"
    }
}
