package com.nagashreeshyl.nive.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Decodes a song to an ~11 kHz mono downmix (a quarter of the memory of full rate), like the iPhone analyzer. */
object PcmDecoder {
    class Pcm(val samples: FloatArray, val sampleRate: Double, val duration: Double)

    const val MAX_SECONDS = 15 * 60

    /** Null if the file can't be decoded. Songs over 15 minutes return no samples (they get plain crossfades). */
    fun decode(file: File): Pcm? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        val factor = maxOf(sr / 11_025, 1)
        if (duration > MAX_SECONDS) { ex.release(); return Pcm(FloatArray(0), sr.toDouble() / factor, duration) }

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = FloatArrayBuilder(((duration.coerceAtLeast(1.0)) * sr / factor).toInt() + 1024)
        var acc = 0f; var n = 0
        var outChannels = channels
        var pcmFloat = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    if (pcmFloat) {
                        val fb = buf.asFloatBuffer()
                        while (fb.remaining() >= outChannels) {
                            var s = 0f; repeat(outChannels) { s += fb.get() }
                            acc += s / outChannels; if (++n == factor) { out.add(acc / factor); acc = 0f; n = 0 }
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        while (sb.remaining() >= outChannels) {
                            var s = 0f; repeat(outChannels) { s += sb.get() / 32768f }
                            acc += s / outChannels; if (++n == factor) { out.add(acc / factor); acc = 0f; n = 0 }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        val rate = sr.toDouble() / factor
        val samples = out.toArray()
        Pcm(samples, rate, if (duration > 0) duration else samples.size / rate)
    }.getOrNull()

    private class FloatArrayBuilder(capacity: Int) {
        private var a = FloatArray(capacity); private var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}
