package com.nagashreeshyl.nive.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds tempo, beat grid and silent edges of a song (port of the iPhone app's BeatAnalyzer).
 * shortcut: beats only, no downbeat/phrase detection; mixes start on a beat, not necessarily bar 1.
 */
object BeatAnalyzer {
    private const val SILENCE_RMS = 0.00316f   // −50 dBFS

    fun analyze(x: FloatArray, sampleRate: Double): TrackAnalysis {
        val sr = sampleRate
        val duration = x.size / sr
        val (lead, end) = silentEdges(x, sr)
        val hop = maxOf(1 shl log2(sr / 172).roundToInt(), 16)
        val frameSize = hop * 4
        val env = onsetEnvelope(x, frameSize, hop)
        val fr = sr / hop
        if (env.size <= (fr * 4).toInt()) return TrackAnalysis(duration, 120.0, 0.0, 0.0, lead, end)

        // Autocorrelation over 70–180 BPM lags (normalized by overlap length).
        val minLag = kotlin.math.floor(60 * fr / 180).toInt()
        val maxLag = kotlin.math.ceil(60 * fr / 70).toInt()
        val r = DoubleArray(maxLag + 2) { autocorr(env, it) }
        val range = r.copyOfRange(minLag, maxLag + 1)
        var best = minLag + range.indices.maxBy { range[it] }
        // Prefer the faster tempo when its half-period also correlates strongly (174 vs 87 BPM).
        val half = best / 2
        if (half >= minLag && r[half] >= 0.7 * r[best]) best = half

        // Parabolic refinement for sub-frame lag precision.
        var lag = best.toDouble()
        if (best > 0 && best + 1 < r.size) {
            val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
            val denom = a - 2 * b + c
            if (denom != 0.0) lag += 0.5 * (a - c) / denom
        }
        val bpm = 60 * fr / lag

        val median = range.sorted()[range.size / 2]
        val confidence = ((r[best] - median) / maxOf(r[0] - median, 1e-9)).coerceIn(0.0, 1.0)

        // Beat phase: offset whose comb over the whole song collects the most onset energy.
        var bestPhase = 0; var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until maxOf(lag.toInt(), 1)) {
            var s = 0.0; var t = phase.toDouble()
            while (t.roundToInt() < env.size) { s += env[t.roundToInt()]; t += lag }
            if (s > bestScore) { bestScore = s; bestPhase = phase }
        }
        // Frame i is centred at i*hop + frameSize/2 samples.
        val beatOffset = (bestPhase * hop + frameSize / 2) / sr
        return TrackAnalysis(duration, bpm, beatOffset % (60 / bpm), confidence, lead, end, loudness(x, sr, lead, end))
    }

    /** Spectral flux of log magnitudes, half-wave rectified and mean-removed. */
    private fun onsetEnvelope(x: FloatArray, frameSize: Int, hop: Int): FloatArray {
        if (x.size < frameSize) return FloatArray(0)
        val window = FloatArray(frameSize) { (0.5 - 0.5 * cos(2 * PI * it / frameSize)).toFloat() }
        val half = frameSize / 2
        val re = DoubleArray(frameSize); val im = DoubleArray(frameSize)
        val prev = FloatArray(half)
        val env = ArrayList<Float>(x.size / hop)
        val fft = Fft(frameSize)
        var i = 0
        while (i + frameSize <= x.size) {
            for (k in 0 until frameSize) { re[k] = (x[i + k] * window[k]).toDouble(); im[k] = 0.0 }
            fft.forward(re, im)
            var flux = 0f
            for (k in 0 until half) {
                // ×2 matches vDSP's packed real FFT scaling.
                val m = ln(1 + 2 * sqrt(re[k] * re[k] + im[k] * im[k])).toFloat()
                val d = m - prev[k]
                if (d > 0) flux += d
                prev[k] = m
            }
            env += flux
            i += hop
        }
        val mean = env.average().toFloat()
        return FloatArray(env.size) { maxOf(env[it] - mean, 0f) }
    }

    private fun autocorr(e: FloatArray, lag: Int): Double {
        if (lag >= e.size) return 0.0
        val n = e.size - lag
        var s = 0.0
        for (i in 0 until n) s += e[i] * e[i + lag]
        return s / n
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /** Average level of the music (silent edges excluded), in dBFS. */
    private fun loudness(x: FloatArray, sr: Double, from: Double, to: Double): Double? {
        val a = maxOf((from * sr).toInt(), 0); val b = minOf((to * sr).toInt(), x.size)
        if (b - a <= sr.toInt()) return null
        return 20 * log10(maxOf(rms(x, a, b), 1e-6f).toDouble())
    }

    /** First and last 50 ms window louder than −50 dBFS. */
    private fun silentEdges(x: FloatArray, sr: Double): Pair<Double, Double> {
        val w = maxOf((sr * 0.05).toInt(), 1)
        val windows = x.size / w
        val all = 0.0 to x.size / sr
        if (windows == 0) return all
        val loud = { j: Int -> rms(x, j * w, (j + 1) * w) > SILENCE_RMS }
        val first = (0 until windows).firstOrNull(loud) ?: return all
        val last = (windows - 1 downTo 0).first(loud)
        return first * w / sr to (last + 1) * w / sr
    }
}

/** In-place iterative radix-2 FFT (size must be a power of two). */
internal class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }

    fun forward(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (s in 0 until n step len) for (k in 0 until len / 2) {
                val c = cosT[k * step]; val sn = sinT[k * step]
                val a = s + k; val b = a + len / 2
                val tr = re[b] * c - im[b] * sn; val ti = re[b] * sn + im[b] * c
                re[b] = re[a] - tr; im[b] = im[a] - ti
                re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }
}
