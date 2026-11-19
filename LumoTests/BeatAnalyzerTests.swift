import Foundation
import Testing
@testable import Lumo

struct BeatAnalyzerTests {
    static let sr = 44_100.0

    /// 10 ms 1 kHz bursts on silence, `lead` seconds of silence first, `tail` after.
    static func clicks(bpm: Double, seconds: Double, offset: Double = 0, lead: Double = 0, tail: Double = 0) -> [Float] {
        let total = Int((lead + seconds + tail) * sr)
        var out = [Float](repeating: 0, count: total)
        let period = 60 / bpm
        var t = lead + offset
        while t < lead + seconds {
            let start = Int(t * sr)
            for i in 0..<Int(0.01 * sr) where start + i < total {
                out[start + i] = 0.8 * sinf(2 * .pi * 1000 * Float(i) / Float(sr))
            }
            t += period
        }
        return out
    }

    @Test(arguments: [90.0, 120.0, 174.0])
    func detectsTempo(bpm: Double) {
        let a = BeatAnalyzer.analyze(mono: Self.clicks(bpm: bpm, seconds: 30), sampleRate: Self.sr)
        #expect(abs(a.bpm - bpm) <= 1, "got \(a.bpm)")
        #expect(a.confidence >= 0.5)
    }

    @Test func detectsBeatPhase() {
        let a = BeatAnalyzer.analyze(mono: Self.clicks(bpm: 120, seconds: 30, offset: 0.25), sampleRate: Self.sr)
        let phase = a.beatOffset.truncatingRemainder(dividingBy: 0.5)
        #expect(abs(phase - 0.25) <= 0.03, "got \(phase)")
    }

    @Test func detectsSilence() {
        // Continuous tone so the sound truly ends at 12 s (a click track goes quiet after its last click).
        let tone = (0..<Int(10 * Self.sr)).map { 0.5 * sinf(2 * .pi * 440 * Float($0) / Float(Self.sr)) }
        let signal = [Float](repeating: 0, count: Int(2 * Self.sr)) + tone + [Float](repeating: 0, count: Int(3 * Self.sr))
        let a = BeatAnalyzer.analyze(mono: signal, sampleRate: Self.sr)
        #expect(abs(a.leadingSilence - 2) <= 0.05, "got \(a.leadingSilence)")
        #expect(abs(a.audioEnd - 12) <= 0.1, "got \(a.audioEnd)")
        #expect(abs(a.duration - 15) <= 0.01)
    }

    @Test func detectsTempoAtLowSampleRate() {
        // The file path analyses an 11 kHz downmix to save memory; tempo must survive that.
        let x = Self.clicks(bpm: 128, seconds: 30)
        let down = stride(from: 0, to: x.count - 3, by: 4).map { (x[$0] + x[$0 + 1] + x[$0 + 2] + x[$0 + 3]) / 4 }
        let a = BeatAnalyzer.analyze(mono: down, sampleRate: Self.sr / 4)
        #expect(abs(a.bpm - 128) <= 1, "got \(a.bpm)")
    }

    @Test func measuresLoudness() {
        let quiet = (0..<Int(10 * Self.sr)).map { 0.05 * sinf(2 * .pi * 440 * Float($0) / Float(Self.sr)) }
        let loud = quiet.map { $0 * 10 }
        let q = BeatAnalyzer.analyze(mono: quiet, sampleRate: Self.sr).loudness ?? 0
        let l = BeatAnalyzer.analyze(mono: loud, sampleRate: Self.sr).loudness ?? 0
        #expect(abs((l - q) - 20) < 0.5, "quiet \(q) loud \(l)")   // 10x amplitude = +20 dB
    }

    @Test func noiseIsLowConfidence() {
        var rng = SystemRandomNumberGenerator()
        let noise = (0..<Int(20 * Self.sr)).map { _ in Float.random(in: -0.5...0.5, using: &rng) }
        let a = BeatAnalyzer.analyze(mono: noise, sampleRate: Self.sr)
        #expect(a.confidence < 0.3, "got \(a.confidence)")
    }
}
