package com.dragon.rcamera.rtp

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AudioCapture {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BIT_RATE = 32000
        private const val PCM_BUFFER_SIZE = 2048
        private const val TIMEOUT_US = 10000L
        private const val AUDIO_PT = 97
        private const val AUDIO_CLOCK_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var captureThread: Thread? = null
    private var isRunning = false
    private val ssrc = (Math.random() * Int.MAX_VALUE).toInt()
    private val seqNum = AtomicInteger((Math.random() * 65535).toInt())
    private var timestamp: Long = 0
    private val destinations = ConcurrentHashMap<String, InetSocketAddress>()

    /** Called when the encoder produces AudioSpecificConfig (csd-0) data. */
    var onCodecSpecificData: ((ByteArray) -> Unit)? = null

    fun start(): Int {
        if (isRunning) return socket?.localPort ?: 0
        isRunning = true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(PCM_BUFFER_SIZE * 4))

        // AAC encoder — raw output (no ADTS), csd-0 will be extracted
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BUFFER_SIZE)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("::", 0))
            }
            Log.d(TAG, "AudioCapture started on port ${socket?.localPort}")
        } catch (e: SocketException) {
            Log.e(TAG, "Failed to bind to :: for audio, falling back", e)
            try {
                socket = DatagramSocket()
                Log.d(TAG, "AudioCapture started on port ${socket?.localPort} (IPv4 fallback)")
            } catch (e2: SocketException) {
                Log.e(TAG, "AudioCapture failed to start socket", e2)
                isRunning = false
                return 0
            }
        }

        val actualPort = socket?.localPort ?: 0

        captureThread = Thread({ captureLoop() }, "AudioCaptureThread").apply {
            isDaemon = true
            start()
        }

        return actualPort
    }

    fun stop() {
        isRunning = false
        captureThread?.join(500)
        captureThread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        destinations.clear()
        Log.d(TAG, "AudioCapture stopped")
    }

    fun addDestination(clientId: String, host: String, port: Int) {
        try {
            val address = InetAddress.getByName(host)
            destinations[clientId] = InetSocketAddress(address, port)
            Log.d(TAG, "Added audio destination: $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add audio destination: $host:$port", e)
        }
    }

    fun removeDestination(clientId: String) {
        destinations.remove(clientId)
    }

    fun getLocalPort(): Int = socket?.localPort ?: 0

    fun hasDestinations(): Boolean = destinations.isNotEmpty()

    private fun captureLoop() {
        val encoder = encoder ?: return
        val audioRecord = audioRecord ?: return
        val buffer = ByteArray(PCM_BUFFER_SIZE)
        val outputInfo = MediaCodec.BufferInfo()

        audioRecord.startRecording()
        Log.d(TAG, "Audio capture loop started")

        try {
            while (isRunning) {
                val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                if (bytesRead <= 0) continue

                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    inputBuffer.put(buffer, 0, bytesRead)
                    encoder.queueInputBuffer(inputIndex, 0, bytesRead,
                        timestamp * 1000000L / AUDIO_CLOCK_RATE, 0)
                    timestamp += (bytesRead / 2).toLong()
                }

                drainEncoder(encoder, outputInfo)
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Audio capture loop error", e)
        }

        drainEncoder(encoder, outputInfo)

        try { audioRecord.stop() } catch (_: Exception) {}
        Log.d(TAG, "Audio capture loop ended")
    }

    private fun drainEncoder(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Capture AudioSpecificConfig (csd-0) from output format
                val csd = encoder.outputFormat.getByteBuffer("csd-0")
                if (csd != null) {
                    val csdBytes = ByteArray(csd.remaining())
                    csd.get(csdBytes)
                    onCodecSpecificData?.invoke(csdBytes)
                }
                continue
            }

            if (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                val aacFrame = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.get(aacFrame, 0, info.size)
                encoder.releaseOutputBuffer(outputIndex, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue

                sendAudioPacket(aacFrame)
            }
        }
    }

    private fun sendAudioPacket(aacFrame: ByteArray) {
        val sock = socket ?: return
        if (destinations.isEmpty()) return

        // Send raw AAC frame (csd-0 delivered separately via WebSocket)
        timestamp += 1024
        val ts = timestamp and 0xFFFFFFFFL
        val packet = RtpPacket.build(
            payload = aacFrame,
            payloadLength = aacFrame.size,
            sequenceNumber = seqNum.incrementAndGet(),
            timestamp = ts,
            ssrc = ssrc,
            payloadType = AUDIO_PT,
            marker = false
        )

        val dp = DatagramPacket(packet, packet.size)
        for ((_, dest) in destinations) {
            try {
                dp.address = dest.address
                dp.port = dest.port
                sock.send(dp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send audio to ${dest.address}:${dest.port}", e)
            }
        }
    }
}
