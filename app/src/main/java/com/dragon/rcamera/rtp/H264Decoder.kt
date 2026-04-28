package com.dragon.rcamera.rtp

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H264 decoder that receives RTP data and renders to a Surface.
 * Used on the client side to decode and display video.
 */
class H264Decoder {

    private var decoder: MediaCodec? = null
    private var isRunning = false
    private var decodeThread: Thread? = null

    // Buffer for reassembled H264 frames
    private val frameBuffer = mutableListOf<ByteArray>()
    private val lock = Object()

    fun start(surface: Surface, width: Int = 1280, height: Int = 720) {
        if (isRunning) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            isRunning = true

            decodeThread = Thread({
                decodeLoop()
            }, "H264DecodeThread").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "H264 decoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264 decoder", e)
            stop()
        }
    }

    fun stop() {
        isRunning = false
        synchronized(lock) {
            lock.notifyAll()
        }
        try {
            decodeThread?.join(1000)
        } catch (_: Exception) {}
        decodeThread = null
        try {
            decoder?.stop()
            decoder?.release()
        } catch (_: Exception) {}
        decoder = null
        frameBuffer.clear()
        Log.d(TAG, "H264 decoder stopped")
    }

    /**
     * Feed a complete H264 access unit (frame) to the decoder.
     */
    fun feedFrame(h264Data: ByteArray) {
        if (!isRunning) return
        synchronized(lock) {
            frameBuffer.add(h264Data)
            lock.notify()
        }
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10000L // 10ms

        while (isRunning) {
            try {
                val dec = decoder ?: break

                // Dequeue output buffer first to keep draining
                while (isRunning) {
                    val outIndex = dec.dequeueOutputBuffer(info, 0)
                    if (outIndex >= 0) {
                        dec.releaseOutputBuffer(outIndex, true)
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed: ${dec.outputFormat}")
                    } else {
                        break
                    }
                }

                // Get next frame to decode
                var frame: ByteArray? = null
                synchronized(lock) {
                    if (frameBuffer.isEmpty()) {
                        lock.wait(50)
                    }
                    if (frameBuffer.isNotEmpty()) {
                        frame = frameBuffer.removeAt(0)
                    }
                }

                val data = frame ?: continue

                // Queue input buffer
                val inIndex = dec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val buf = dec.getInputBuffer(inIndex) ?: continue
                    buf.clear()
                    buf.put(data)
                    dec.queueInputBuffer(
                        inIndex,
                        0,
                        data.size,
                        System.nanoTime() / 1000,
                        0
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decode loop error", e)
                if (!isRunning) break
            }
        }
    }

    companion object {
        private const val TAG = "H264Decoder"
    }
}
