package com.dragon.rcamera.rtp

import java.nio.ByteBuffer

/**
 * RTP packet builder following RFC 3550.
 * Default payload type 96 (dynamic), SSRC random.
 */
object RtpPacket {

    const val HEADER_SIZE = 12
    const val MAX_PAYLOAD_SIZE = 1400 // Typical MTU-safe payload size

    fun build(
        payload: ByteArray,
        payloadLength: Int,
        sequenceNumber: Int,
        timestamp: Long,
        ssrc: Int,
        payloadType: Int = 96,
        marker: Boolean = false
    ): ByteArray {
        val packet = ByteArray(HEADER_SIZE + payloadLength)
        val buf = ByteBuffer.wrap(packet)

        // V=2, P=0, X=0, CC=0
        buf.put(0x80.toByte())
        // M bit + PT
        buf.put(((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte())
        // Sequence number
        buf.putShort((sequenceNumber and 0xFFFF).toShort())
        // Timestamp
        buf.putInt((timestamp and 0xFFFFFFFFL).toInt())
        // SSRC
        buf.putInt(ssrc)

        // Payload
        System.arraycopy(payload, 0, packet, HEADER_SIZE, payloadLength)

        return packet
    }
}
