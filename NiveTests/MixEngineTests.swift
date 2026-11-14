import AVFoundation
import Foundation
import Testing
@testable import Nive

/// Drives the real audio engine in the simulator. Regression for the device bug where a
/// beat-matched (rate ≠ 1) blend never started the incoming song and playback went silent.
@MainActor
struct MixEngineTests {
    private func tone(_ seconds: Double, in dir: URL, name: String) throws -> PreparedTrack {
        let url = dir.appending(path: "\(name).m4a")
        let pcm = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!
        let frames = AVAudioFrameCount(seconds * 44_100)
        let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                                               AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 128_000])
        let buf = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: frames)!
        buf.frameLength = frames
        for c in 0..<2 { for i in 0..<Int(frames) { buf.floatChannelData![c][i] = 0.3 * sinf(2 * .pi * 330 * Float(i) / 44_100) } }
        try file.write(from: buf)
        let analysis = TrackAnalysis(duration: seconds, bpm: 120, beatOffset: 0, confidence: 0.9, leadingSilence: 0, audioEnd: seconds)
        return PreparedTrack(songId: name, file: url, analysis: analysis)
    }

    private func runBlend(rate: Double) async throws -> (swappedTo: String?, positionAfter: Double) {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let a = try tone(4, in: dir, name: "A"), b = try tone(10, in: dir, name: "B")
        let engine = MixEngine()
        var swapped: String?
        engine.onSwapped = { swapped = $0 }
        engine.start(a)
        engine.schedule(next: b, plan: TransitionPlan(mixStartA: 1.5, overlap: 2, startB: 0, rateB: rate, beatMatched: rate != 1))
        try await Task.sleep(for: .seconds(5))
        let position = engine.currentTime
        engine.pause()
        return (swapped, position)
    }

    @Test func crossfadeHandsOverAndKeepsPlaying() async throws {
        let r = try await runBlend(rate: 1)
        #expect(r.swappedTo == "B")
        #expect(r.positionAfter > 2, "B position \(r.positionAfter)")
    }

    @Test func beatMatchedBlendStartsTheIncomingSong() async throws {
        let r = try await runBlend(rate: 0.95)
        #expect(r.swappedTo == "B")
        #expect(r.positionAfter > 2, "B position \(r.positionAfter)")
    }
}
