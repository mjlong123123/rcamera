package com.dragon.rcamera.rtp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class AudioPlayback {

    companion object {
        private const val TAG = "AudioPlayback"
        private const val SAMPLE_RATE = 16000
        private const val TIMEOUT_US = 10000L
    }

    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var isRunning = false
    private val decoderLock = Any()
    private var csdData: ByteArray? = null
    private var csdReady = false

    /** Provide AudioSpecificConfig (csd-0) data before the first audio frame arrives. */
    fun setCodecSpecificData(data: ByteArray) {
        synchronized(decoderLock) {
            csdData = data
            csdReady = true
            // Recreate decoder now that CSD is available
            initDecoder()
        }
    }

    fun start(): Int {
        if (isRunning) return socket?.localPort ?: 0
        isRunning = true

        initAudioTrack()

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("::", 0))
            }
            Log.d(TAG, "AudioPlayback started on port ${socket?.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayback failed to start socket", e)
            isRunning = false
            return 0
        }

        val actualPort = socket?.localPort ?: 0

        receiveThread = Thread({ receiveLoop() }, "AudioReceiveThread").apply {
            isDaemon = true
            start()
        }

        return actualPort
    }

    private fun initDecoder() {
        synchronized(decoderLock) {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null

            val csd = csdData ?: run {
                Log.d(TAG, "initDecoder: no CSD yet, waiting")
                return
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE, 1)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))

            decoder = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AAC decoder", e)
                null
            }
        }
    }

    private fun initAudioTrack() {
        try { audioTrack?.release() } catch (_: Exception) {}
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBufferSize.coerceAtLeast(4096),
            AudioTrack.MODE_STREAM,
            0
        )
        audioTrack?.play()
    }

    fun stop() {
        isRunning = false
        receiveThread?.join(500)
        receiveThread = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        synchronized(decoderLock) {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
        }
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        Log.d(TAG, "AudioPlayback stopped")
    }

    fun getLocalPort(): Int = socket?.localPort ?: 0

    private fun receiveLoop() {
        val sock = socket ?: return
        val audioTrack = audioTrack ?: return
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        val outputInfo = MediaCodec.BufferInfo()

        Log.d(TAG, "Audio receive loop started")

        try {
            while (isRunning) {
                sock.receive(packet)
                if (!isRunning) break

                val payloadLength = packet.length - RtpPacket.HEADER_SIZE
                if (payloadLength <= 0) continue
                val aacFrame = ByteArray(payloadLength)
                System.arraycopy(packet.data, RtpPacket.HEADER_SIZE, aacFrame, 0, payloadLength)

                synchronized(decoderLock) {
                    val dec = decoder
                    if (dec == null) {
                        Log.d(TAG, "drop audio frame, decoder not ready")
                        continue
                    }

                    try {
                        val inputIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = dec.getInputBuffer(inputIndex) ?: continue
                            inputBuffer.clear()
                            inputBuffer.put(aacFrame)
                            dec.queueInputBuffer(inputIndex, 0, aacFrame.size, 0L, 0)
                        }
                        drainAndPlay(dec, audioTrack, outputInfo)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Decoder error, recreating", e)
                        initDecoder()
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Audio receive loop error", e)
        }

        Log.d(TAG, "Audio receive loop ended")
    }

    private fun drainAndPlay(decoder: MediaCodec, audioTrack: AudioTrack, info: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue

            if (outputIndex >= 0) {
                val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: continue
                val pcmData = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.get(pcmData, 0, info.size)
                decoder.releaseOutputBuffer(outputIndex, false)

                try {
                    audioTrack.write(pcmData, 0, pcmData.size)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack write failed", e)
                }
            }
        }
    }
}
