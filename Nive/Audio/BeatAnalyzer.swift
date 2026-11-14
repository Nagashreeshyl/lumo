import Accelerate
import AVFoundation

/// Finds tempo, beat grid and silent edges of a song.
/// shortcut: beats only, no downbeat/phrase detection; mixes start on a beat, not necessarily bar 1.
nonisolated enum BeatAnalyzer {
    // ~172 onset frames/s at any sample rate (hop 256 at 44.1 kHz, 64 at 11 kHz).
    private static let silenceRMS: Float = 0.00316   // −50 dBFS

    /// Reads the file as an ~11 kHz mono downmix (a quarter of the memory of full rate).
    /// Tracks longer than 15 minutes (DJ sets, jukeboxes) skip beat analysis and get plain crossfades.
    static func analyze(file: URL) throws -> TrackAnalysis {
        let f = try AVAudioFile(forReading: file)
        let fmt = f.processingFormat
        let duration = Double(f.length) / fmt.sampleRate
        guard duration <= 15 * 60 else {
            return TrackAnalysis(duration: duration, bpm: 120, beatOffset: 0, confidence: 0, leadingSilence: 0, audioEnd: duration)
        }
        let factor = max(Int(fmt.sampleRate / 11_025), 1)
        let chunk: AVAudioFrameCount = 1 << 16
        guard let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: chunk) else { throw CocoaError(.fileReadCorruptFile) }
        let channels = Int(fmt.channelCount)
        var mono: [Float] = []
        mono.reserveCapacity(Int(f.length) / factor + 1)
        var acc: Float = 0, n = 0
        while f.framePosition < f.length {
            try f.read(into: buf, frameCount: chunk)
            let frames = Int(buf.frameLength)
            guard frames > 0, let ch = buf.floatChannelData else { break }
            for i in 0..<frames {
                for c in 0..<channels { acc += fmt.isInterleaved ? ch[0][i * channels + c] : ch[c][i] }
                n += 1
                if n == factor {
                    mono.append(acc / Float(factor * channels))
                    acc = 0; n = 0
                }
            }
        }
        return analyze(mono: mono, sampleRate: fmt.sampleRate / Double(factor))
    }

    static func analyze(mono x: [Float], sampleRate sr: Double) -> TrackAnalysis {
        let duration = Double(x.count) / sr
        let (lead, end) = silentEdges(x, sr: sr)
        let hop = max(1 << Int(log2(sr / 172).rounded()), 16)
        let frameSize = hop * 4
        let env = onsetEnvelope(x, frameSize: frameSize, hop: hop)
        let fr = sr / Double(hop)
        guard env.count > Int(fr * 4) else {
            return TrackAnalysis(duration: duration, bpm: 120, beatOffset: 0, confidence: 0, leadingSilence: lead, audioEnd: end)
        }

        // Autocorrelation over 70–180 BPM lags (normalized by overlap length).
        let minLag = Int((60 * fr / 180).rounded(.down)), maxLag = Int((60 * fr / 70).rounded(.up))
        let r = (0...maxLag + 1).map { autocorr(env, lag: $0) }
        let range = Array(r[minLag...maxLag])
        var best = minLag + range.indices.max { range[$0] < range[$1] }!
        // Prefer the faster tempo when its half-period also correlates strongly (174 vs 87 BPM).
        let half = best / 2
        if half >= minLag, r[half] >= 0.7 * r[best] { best = half }

        // Parabolic refinement for sub-frame lag precision.
        var lag = Double(best)
        if best > 0, best + 1 < r.count {
            let (a, b, c) = (r[best - 1], r[best], r[best + 1])
            let denom = a - 2 * b + c
            if denom != 0 { lag += 0.5 * (a - c) / denom }
        }
        let bpm = 60 * fr / lag

        let sorted = range.sorted()
        let median = sorted[sorted.count / 2]
        let confidence = max(0, min(1, (r[best] - median) / max(r[0] - median, 1e-9)))

        // Beat phase: offset whose comb over the whole song collects the most onset energy.
        var bestPhase = 0, bestScore = -Double.infinity
        for phase in 0..<max(Int(lag), 1) {
            var s = 0.0
            var t = Double(phase)
            while Int(t.rounded()) < env.count { s += Double(env[Int(t.rounded())]); t += lag }
            if s > bestScore { bestScore = s; bestPhase = phase }
        }
        // Frame i is centred at i*hop + frameSize/2 samples.
        let beatOffset = (Double(bestPhase * hop) + Double(frameSize / 2)) / sr

        return TrackAnalysis(duration: duration, bpm: bpm, beatOffset: beatOffset.truncatingRemainder(dividingBy: 60 / bpm),
                             confidence: confidence, leadingSilence: lead, audioEnd: end)
    }

    /// Spectral flux of log magnitudes, half-wave rectified and mean-removed.
    private static func onsetEnvelope(_ x: [Float], frameSize: Int, hop: Int) -> [Float] {
        guard x.count >= frameSize else { return [] }
        let log2n = vDSP_Length(log2(Double(frameSize)))
        guard let fft = vDSP.FFT(log2n: log2n, radix: .radix2, ofType: DSPSplitComplex.self) else { return [] }
        let window = vDSP.window(ofType: Float.self, usingSequence: .hanningDenormalized, count: frameSize, isHalfWindow: false)
        let half = frameSize / 2
        var real = [Float](repeating: 0, count: half), imag = [Float](repeating: 0, count: half)
        var mags = [Float](repeating: 0, count: half), prev = [Float](repeating: 0, count: half)
        var frame = [Float](repeating: 0, count: frameSize)
        var env: [Float] = []
        env.reserveCapacity(x.count / hop)

        var i = 0
        while i + frameSize <= x.count {
            x.withUnsafeBufferPointer { src in
                vDSP.multiply(UnsafeBufferPointer(rebasing: src[i..<i + frameSize]), window, result: &frame)
            }
            real.withUnsafeMutableBufferPointer { rp in
                imag.withUnsafeMutableBufferPointer { ip in
                    var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
                    frame.withUnsafeBufferPointer { fp in
                        fp.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: half) { vDSP_ctoz($0, 2, &split, 1, vDSP_Length(half)) }
                    }
                    fft.forward(input: split, output: &split)
                    mags.withUnsafeMutableBufferPointer { mp in vDSP_zvabs(&split, 1, mp.baseAddress!, 1, vDSP_Length(half)) }
                }
            }
            var flux: Float = 0
            for k in 0..<half {
                let m = logf(1 + mags[k])
                let d = m - prev[k]
                if d > 0 { flux += d }
                prev[k] = m
            }
            env.append(flux)
            i += hop
        }
        let mean = vDSP.mean(env)
        return env.map { max($0 - mean, 0) }
    }

    private static func autocorr(_ e: [Float], lag: Int) -> Double {
        guard lag < e.count else { return 0 }
        let n = e.count - lag
        var s: Float = 0
        e.withUnsafeBufferPointer { p in
            vDSP_dotpr(p.baseAddress!, 1, p.baseAddress! + lag, 1, &s, vDSP_Length(n))
        }
        return Double(s) / Double(n)
    }

    /// First and last 50 ms window louder than −50 dBFS.
    private static func silentEdges(_ x: [Float], sr: Double) -> (Double, Double) {
        let w = max(Int(sr * 0.05), 1)
        let windows = x.count / w
        guard windows > 0 else { return (0, Double(x.count) / sr) }
        let loud: (Int) -> Bool = { j in
            x.withUnsafeBufferPointer { p in vDSP.rootMeanSquare(UnsafeBufferPointer(rebasing: p[j * w..<(j + 1) * w])) > silenceRMS }
        }
        guard let first = (0..<windows).first(where: loud), let last = (0..<windows).last(where: loud) else {
            return (0, Double(x.count) / sr)
        }
        return (Double(first * w) / sr, Double((last + 1) * w) / sr)
    }
}
