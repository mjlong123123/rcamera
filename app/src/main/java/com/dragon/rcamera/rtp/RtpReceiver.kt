package com.dragon.rcamera.rtp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * RTP receiver with sliding-window jitter buffer.
 * Packets are buffered by sequence number, reordered, and only
 * flushed when consecutive. Missing packets time out after
 * [REORDER_TIMEOUT_MS] and are treated as lost.
 */
class RtpReceiver {

    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var isRunning = false

    var onFrameReceived: ((ByteArray) -> Unit)? = null
    var onKeyFrameRequested: (() -> Unit)? = null

    // NALU entry with sequence number for ordering
    private data class NaluEntry(val seqNum: Int, val data: ByteArray)

    // Buffered packet waiting to be flushed
    private data class PacketEntry(
        val seqNum: Int,
        val timestamp: Long,
        val marker: Boolean,
        val naluType: Int,
        val payload: ByteArray,
        val arriveTimeMs: Long
    )

    // Reassembly state
    private var currentTimestamp: Long = -1
    private val naluBuffer = mutableListOf<NaluEntry>()
    private val lock = Object()

    // Sliding-window jitter buffer: seqNum -> PacketEntry
    private val packetBuffer = HashMap<Int, PacketEntry>(64)
    private var readSeqNum = -1  // next seqNum to flush
    private var firstPacketReceived = false

    // FU-A reassembly state — now processing in seq order, so fragments arrive in order
    private var fuAInProgress = false
    private var fuAStartSeq = -1
    private var fuAOrigNaluHeader = 0
    private val fuAFragments = mutableMapOf<Int, ByteArray>()

    // Frame-level tracking
    private var framePacketCount = 0
    private var frameNaluCount = 0

    // Loss / gap tracking
    private var lastKeyFrameRequestTime = 0L
    private var keyFrameRequestCount = 0

    // Stats for debugging
    @Volatile private var receivedPacketCount = 0
    @Volatile private var deliveredFrameCount = 0
    @Volatile private var lastFrameSize = 0
    @Volatile private var lostPacketCount = 0
    @Volatile private var discardedFrameCount = 0

    fun getStats(): String = "pkts=$receivedPacketCount frames=$deliveredFrameCount lost=$lostPacketCount discarded=$discardedFrameCount buf=${packetBuffer.size} lastSize=$lastFrameSize"

    fun start(localPort: Int) {
        if (isRunning) return
        try {
            socket = DatagramSocket(null)
            socket?.reuseAddress = true
            socket?.bind(InetSocketAddress("::", localPort))
            socket?.soTimeout = 250  // short timeout to check for buffer timeouts frequently
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
            try {
                socket = DatagramSocket(localPort)
                socket?.soTimeout = 250
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
        packetBuffer.clear()
        readSeqNum = -1
        firstPacketReceived = false
        currentTimestamp = -1
        fuAInProgress = false
        fuAFragments.clear()
        receivedPacketCount = 0
        deliveredFrameCount = 0
        lastFrameSize = 0
        lostPacketCount = 0
        discardedFrameCount = 0
        keyFrameRequestCount = 0
        Log.d(TAG, "RTP receiver stopped")
    }

    fun getLocalPort(): Int = socket?.localPort ?: 0

    // ── seqNum helpers (16-bit wrap-aware) ──

    /** Distance from [a] to [b] in the forward direction: how many steps ahead is b? */
    private fun seqDistance(a: Int, b: Int): Int = ((b - a) and 0xFFFF)

    /** Is [b] after [a] in the 16-bit sequence space? */
    private fun isSeqAfter(b: Int, a: Int): Boolean {
        val dist = seqDistance(a, b)
        return dist in 1..32767
    }

    // ── Receive loop ──

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)

        while (isRunning) {
            try {
                // First, check for timed-out gaps in the buffer
                tryFlush()

                socket?.receive(packet) ?: break

                val data = packet.data
                val length = packet.length
                if (length < RtpPacket.HEADER_SIZE) continue

                receivedPacketCount++
                if (receivedPacketCount == 1) {
                    Log.d(TAG, "First RTP packet received! From ${packet.address}:${packet.port}, length=$length")
                }
                if (receivedPacketCount % 300 == 0) {
                    Log.d(TAG, "RTP stats: received $receivedPacketCount packets, delivered $deliveredFrameCount frames, lost=$lostPacketCount discarded=$discardedFrameCount buf=${packetBuffer.size} keyReq=$keyFrameRequestCount readSeq=$readSeqNum")
                }

                // Parse RTP header
                val bufWrap = ByteBuffer.wrap(data, 0, length)
                val byte0 = bufWrap.get().toInt() and 0xFF
                val byte1 = bufWrap.get().toInt() and 0xFF
                val seqNum = bufWrap.short.toInt() and 0xFFFF
                val timestamp = (bufWrap.int.toLong()) and 0xFFFFFFFFL
                bufWrap.int // skip SSRC

                val marker = (byte1 and 0x80) != 0
                val payloadOffset = RtpPacket.HEADER_SIZE
                val payloadLength = length - RtpPacket.HEADER_SIZE

                if (payloadLength <= 0) continue

                val payload = ByteArray(payloadLength)
                System.arraycopy(data, payloadOffset, payload, 0, payloadLength)
                val naluType = payload[0].toInt() and 0x1F

                Log.d(TAG, "RECV seq=$seqNum ts=$timestamp marker=$marker naluType=$naluType len=$payloadLength")

                // Initialize readSeqNum on first packet
                if (!firstPacketReceived) {
                    readSeqNum = seqNum
                    firstPacketReceived = true
                    Log.d(TAG, "FIRST packet seq=$seqNum, readSeqNum initialized")
                }

                // Discard packets that are behind the read cursor (extremely late)
                if (firstPacketReceived && isSeqAfter(readSeqNum, seqNum)) {
                    val behind = seqDistance(seqNum, readSeqNum)
                    if (behind > REORDER_WINDOW) {
                        Log.w(TAG, "STALE: seq=$seqNum is far behind readSeq=$readSeqNum (behind by $behind), dropping")
                        continue
                    }
                }

                // Insert into buffer (may replace duplicate)
                val now = System.currentTimeMillis()
                packetBuffer[seqNum] = PacketEntry(seqNum, timestamp, marker, naluType, payload, now)

                // Try to flush consecutive packets
                tryFlush()

            } catch (e: SocketTimeoutException) {
                // Short timeout — used to periodically check for timed-out gaps
                tryFlush()
                continue
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "RTP receive error", e)
                }
            }
        }
    }

    // ── Flush logic ──

    /**
     * Flush consecutive packets from the buffer starting at [readSeqNum].
     * If a gap is detected and the next buffered packet has been waiting
     * longer than [REORDER_TIMEOUT_MS], skip the missing packets (treat as lost)
     * and continue flushing from the next available seqNum.
     */
    private fun tryFlush() {
        while (isRunning && firstPacketReceived) {
            val entry = packetBuffer.remove(readSeqNum)
            if (entry != null) {
                processPacket(entry)
                readSeqNum = (readSeqNum + 1) and 0xFFFF
                continue
            }

            // readSeqNum not in buffer — check if we should timeout the gap
            if (packetBuffer.isEmpty()) break

            // Find the smallest seqNum in the buffer ahead of readSeqNum
            val candidates = packetBuffer.keys.filter { isSeqAfter(it, readSeqNum) }
            if (candidates.isEmpty()) break

            val nextSeq = candidates.minByOrNull { seqDistance(readSeqNum, it) } ?: break
            val gap = seqDistance(readSeqNum, nextSeq)
            val nextEntry = packetBuffer[nextSeq] ?: break
            val waitMs = System.currentTimeMillis() - nextEntry.arriveTimeMs

            if (waitMs >= REORDER_TIMEOUT_MS || packetBuffer.size >= MAX_BUFFER_SIZE) {
                // Timeout or buffer full — skip the gap, treat packets as lost
                lostPacketCount += gap
                discardedFrameCount++
                Log.w(TAG, "FLUSH_TIMEOUT: skipping $gap missing packets (seq $readSeqNum to ${(readSeqNum + gap - 1) and 0xFFFF}), waited=${waitMs}ms, bufSize=${packetBuffer.size}, discarded=$discardedFrameCount")
                requestKeyFrame()

                // Advance readSeqNum to the next available packet
                readSeqNum = nextSeq
                // Next iteration will pick up nextSeq
            } else {
                // Still waiting for the missing packet
                break
            }
        }
    }

    // ── Packet processing (called in seq order from tryFlush) ──

    private fun processPacket(entry: PacketEntry) {
        val seqNum = entry.seqNum
        val timestamp = entry.timestamp
        val marker = entry.marker
        val naluType = entry.naluType
        val payload = entry.payload

        // Timestamp changed -> deliver previous frame
        if (currentTimestamp != -1L && timestamp != currentTimestamp) {
            Log.d(TAG, "FRAME_END ts=$currentTimestamp -> deliver (pkts=$framePacketCount nalus=$frameNaluCount), new ts=$timestamp")
            deliverFrame()
            framePacketCount = 0
            frameNaluCount = 0
        }
        currentTimestamp = timestamp
        framePacketCount++

        when {
            naluType in 1..23 -> {
                // Single NALU packet
                addNalu(seqNum, payload)
                frameNaluCount++
                if (marker) {
                    Log.d(TAG, "MARKER seq=$seqNum ts=$timestamp -> deliver (pkts=$framePacketCount nalus=$frameNaluCount)")
                    deliverFrame()
                    framePacketCount = 0
                    frameNaluCount = 0
                }
            }
            naluType == 28 -> {
                // FU-A — fragments now arrive in seqNum order (thanks to jitter buffer)
                if (payload.size < 2) return
                val fuHeader = payload[1].toInt() and 0xFF
                val startBit = (fuHeader and 0x80) != 0
                val endBit = (fuHeader and 0x40) != 0
                val origNaluType = fuHeader and 0x1F
                val naluHeaderByte = payload[0].toInt() and 0xFF

                if (startBit) {
                    if (fuAInProgress) {
                        discardedFrameCount++
                        Log.w(TAG, "FU-A discarded: new start before previous completed (prevStartSeq=$fuAStartSeq)")
                    }
                    fuAInProgress = true
                    fuAStartSeq = seqNum
                    fuAOrigNaluHeader = (naluHeaderByte and 0xE0) or origNaluType
                    fuAFragments.clear()
                    val fragData = ByteArray(payload.size - 2)
                    System.arraycopy(payload, 2, fragData, 0, fragData.size)
                    fuAFragments[seqNum] = fragData
                } else if (fuAInProgress) {
                    val fragData = ByteArray(payload.size - 2)
                    System.arraycopy(payload, 2, fragData, 0, fragData.size)
                    fuAFragments[seqNum] = fragData
                } else {
                    // Fragment outside FU-A context — should not happen with ordered delivery
                    Log.w(TAG, "FU-A fragment seq=$seqNum outside FU-A context (no start)")
                    return
                }

                if (endBit && fuAInProgress) {
                    val endSeq = seqNum
                    val expected = endSeq - fuAStartSeq + 1
                    if (expected == fuAFragments.size) {
                        // All fragments present — reassemble
                        var totalLen = 1
                        for (s in fuAStartSeq..endSeq) {
                            totalLen += fuAFragments[s]!!.size
                        }
                        val reassembled = ByteArray(totalLen)
                        reassembled[0] = fuAOrigNaluHeader.toByte()
                        var offset = 1
                        for (s in fuAStartSeq..endSeq) {
                            val frag = fuAFragments[s]!!
                            System.arraycopy(frag, 0, reassembled, offset, frag.size)
                            offset += frag.size
                        }
                        addNalu(fuAStartSeq, reassembled)
                        frameNaluCount++
                        Log.d(TAG, "FU-A reassembled: startSeq=$fuAStartSeq endSeq=$endSeq fragments=$expected naluSize=$totalLen")
                    } else {
                        // Some fragments missing — discard and request keyframe
                        discardedFrameCount++
                        Log.w(TAG, "FU-A INCOMPLETE: startSeq=$fuAStartSeq endSeq=$endSeq have=${fuAFragments.size} expected=$expected, discarded=$discardedFrameCount")
                        requestKeyFrame()
                    }
                    fuAInProgress = false
                    fuAFragments.clear()
                    if (marker) {
                        Log.d(TAG, "MARKER (FU-A end) seq=$seqNum -> deliver (pkts=$framePacketCount nalus=$frameNaluCount)")
                        deliverFrame()
                        framePacketCount = 0
                        frameNaluCount = 0
                    }
                }
            }
            naluType == 24 -> {
                // STAP-A - multiple NALUs in one packet
                var stapCount = 0
                var offset = 1
                while (offset + 1 < payload.size) {
                    val naluSize = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                    offset += 2
                    if (offset + naluSize <= payload.size) {
                        val stapNalu = ByteArray(naluSize)
                        System.arraycopy(payload, offset, stapNalu, 0, naluSize)
                        addNalu(seqNum, stapNalu)
                        frameNaluCount++
                        stapCount++
                        offset += naluSize
                    } else {
                        break
                    }
                }
                Log.d(TAG, "STAP-A: unpacked $stapCount NALUs from seq=$seqNum")
                if (marker) {
                    Log.d(TAG, "MARKER (STAP-A) seq=$seqNum -> deliver (pkts=$framePacketCount nalus=$frameNaluCount)")
                    deliverFrame()
                    framePacketCount = 0
                    frameNaluCount = 0
                }
            }
            else -> {
                Log.w(TAG, "Unsupported NALU type: $naluType at seq=$seqNum")
            }
        }
    }

    // ── NALU / Frame assembly ──

    private fun addNalu(seqNum: Int, nalu: ByteArray) {
        synchronized(lock) {
            naluBuffer.add(NaluEntry(seqNum, nalu))
        }
    }

    private fun deliverFrame() {
        synchronized(lock) {
            val naluCount = naluBuffer.size

            if (naluBuffer.isEmpty()) {
                Log.d(TAG, "DELIVER: empty frame (0 nalus)")
                return
            }

            // Sort NALUs by sequence number
            naluBuffer.sortBy { it.seqNum }

            // Combine all NALUs with start codes to form an access unit
            var totalSize = 0
            for (entry in naluBuffer) {
                totalSize += 4 + entry.data.size
            }

            val frame = ByteArray(totalSize)
            var offset = 0
            for (entry in naluBuffer) {
                frame[offset] = 0
                frame[offset + 1] = 0
                frame[offset + 2] = 0
                frame[offset + 3] = 1
                System.arraycopy(entry.data, 0, frame, offset + 4, entry.data.size)
                offset += 4 + entry.data.size
            }

            naluBuffer.clear()
            deliveredFrameCount++
            lastFrameSize = totalSize
            Log.d(TAG, "DELIVER: frame #$deliveredFrameCount, nalus=$naluCount totalBytes=$totalSize")
            onFrameReceived?.invoke(frame)
        }
    }

    // ── Keyframe request ──

    private fun requestKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyFrameRequestTime < 1000) return
        lastKeyFrameRequestTime = now
        keyFrameRequestCount++
        Log.d(TAG, "Requesting keyframe (count=$keyFrameRequestCount)")
        onKeyFrameRequested?.invoke()
    }

    companion object {
        private const val TAG = "RtpReceiver"

        /** Max packets to buffer before forcing a flush (skip timed-out gaps). */
        private const val MAX_BUFFER_SIZE = 128

        /** How long to wait for a missing packet before treating it as lost. */
        private const val REORDER_TIMEOUT_MS = 150L

        /** Packets farther behind readSeqNum than this are dropped as stale. */
        private const val REORDER_WINDOW = 64
    }
}
