package com.dragon.rcamera.rtp

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

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

    // Max buffered frames to prevent OOM
    private  val MAX_BUFFER_SIZE = 60

    // Stats for debugging
    @Volatile private var fedFrameCount = 0
    @Volatile private var decodedFrameCount = 0
    @Volatile private var inputErrorCount = 0
    @Volatile private var droppedFrameCount = 0
    var onOutputFormatChanged: ((width: Int, height: Int) -> Unit)? = null

    fun getStats(): String = "fed=$fedFrameCount decoded=$decodedFrameCount errors=$inputErrorCount dropped=$droppedFrameCount buf=${frameBuffer.size} running=$isRunning"

    fun start(surface: Surface, width: Int = 0, height: Int = 0) {
        if (isRunning) return
        try {
            // Use provided dimensions if available; otherwise use 0x0 to let decoder
            // auto-detect from SPS. Non-zero values are hints for surface configuration.
            val format = if (width > 0 && height > 0) {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            } else {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280)
            }
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
        Log.d(TAG, "H264 decoder stopped, stats: fed=$fedFrameCount decoded=$decodedFrameCount errors=$inputErrorCount dropped=$droppedFrameCount")
        fedFrameCount = 0
        decodedFrameCount = 0
        inputErrorCount = 0
        droppedFrameCount = 0
    }

    /**
     * Feed a complete H264 access unit (frame) to the decoder.
     * If buffer is full, drop the oldest non-config frame to prevent OOM.
     */
    fun feedFrame(h264Data: ByteArray) {
        if (!isRunning) return
        synchronized(lock) {
            // Drop oldest frame if buffer is full (but prefer keeping SPS/PPS)
            if (frameBuffer.size >= MAX_BUFFER_SIZE) {
                // Find a non-config frame to drop (config frames start with 0x00 0x00 0x00 0x01 0x67 or 0x68)
                var dropped = false
                val iter = frameBuffer.iterator()
                while (iter.hasNext()) {
                    val f = iter.next()
                    if (!isConfigFrame(f)) {
                        iter.remove()
                        droppedFrameCount++
                        dropped = true
                        break
                    }
                }
                if (!dropped) {
                    // All frames are config frames, drop the oldest anyway
                    frameBuffer.removeAt(0)
                    droppedFrameCount++
                }
            }
            frameBuffer.add(h264Data)
            fedFrameCount++
            if (fedFrameCount == 1) {
                Log.d(TAG, "First frame fed to decoder: ${h264Data.size} bytes")
            }
            lock.notify()
        }
    }

    /**
     * Check if a frame is a config frame (SPS/PPS).
     * Config frames contain NALU types 7 (SPS) or 8 (PPS).
     */
    private fun isConfigFrame(data: ByteArray): Boolean {
        if (data.size < 5) return false
        // Find start code and check NALU type
        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val naluType = data[i + 4].toInt() and 0x1F
                if (naluType == 7 || naluType == 8) return true
            }
        }
        return false
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10000L // 10ms

        while (isRunning) {
            try {
                val dec = decoder ?: break

                // Drain all available output buffers
                drainOutput(dec, info)

                // Try to queue as many input frames as available
                var queuedCount = 0
                while (isRunning && queuedCount < 4) {
                    val frame = getNextFrame() ?: break
                    val inIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)
                        if (buf != null) {
                            buf.clear()
                            if (frame.size > buf.capacity()) {
                                Log.w(TAG, "Frame too large for input buffer: ${frame.size} > ${buf.capacity()}")
                                inputErrorCount++
                            } else {
                                buf.put(frame)
                                dec.queueInputBuffer(
                                    inIndex,
                                    0,
                                    frame.size,
                                    System.nanoTime() / 1000,
                                    0
                                )
                                queuedCount++
                            }
                        } else {
                            // getInputBuffer returned null, put frame back
                            putBackFrame(frame)
                            break
                        }
                    } else {
                        // No input buffer available, put frame back for next iteration
                        putBackFrame(frame)
                        break
                    }
                }

                // If nothing was queued and no frames available, wait briefly
                if (queuedCount == 0) {
                    synchronized(lock) {
                        if (frameBuffer.isEmpty()) {
                            lock.wait(50)
                        }
                    }
                }
            } catch (e: Exception) {
                inputErrorCount++
                Log.e(TAG, "Decode loop error (errors=$inputErrorCount)", e)
                if (!isRunning) break
            }
        }
    }

    private fun drainOutput(dec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (isRunning) {
            val outIndex = dec.dequeueOutputBuffer(info, 0)
            if (outIndex >= 0) {
                decodedFrameCount++
                dec.releaseOutputBuffer(outIndex, true)
                if (decodedFrameCount == 1) {
                    Log.d(TAG, "First frame decoded and rendered!")
                }
                if (decodedFrameCount % 100 == 0) {
                    Log.d(TAG, "Decoded $decodedFrameCount frames")
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = dec.outputFormat
                Log.d(TAG, "Output format changed: $format")
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                onOutputFormatChanged?.invoke(w, h)
            } else {
                break
            }
        }
    }

    private fun getNextFrame(): ByteArray? {
        synchronized(lock) {
            if (frameBuffer.isNotEmpty()) {
                return frameBuffer.removeAt(0)
            }
            return null
        }
    }

    private fun putBackFrame(frame: ByteArray) {
        synchronized(lock) {
            frameBuffer.add(0, frame)
        }
    }

    companion object {
        private const val TAG = "H264Decoder"
    }
}
