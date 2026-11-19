import AVFoundation
import Foundation
import Testing
@testable import Lumo

struct AudioCacheTests {
    @Test func preparesCompressedPlayableFile() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let src = dir.appending(path: "in.m4a"), dst = dir.appending(path: "out.m4a")

        // 2 s stereo sine encoded as AAC.
        let pcm = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!
        do {
            let file = try AVAudioFile(forWriting: src, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                                                   AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 128_000])
            let buf = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: 88_200)!
            buf.frameLength = 88_200
            for c in 0..<2 { for i in 0..<88_200 { buf.floatChannelData![c][i] = 0.5 * sinf(2 * .pi * 440 * Float(i) / 44_100) } }
            try file.write(from: buf)
        }

        try await AudioCache.decode(src, to: dst)
        let out = try AVAudioFile(forReading: dst)
        #expect(out.processingFormat.sampleRate == 44_100)
        #expect(out.processingFormat.channelCount == 2)
        #expect(abs(Int(out.length) - 88_200) <= 4_096, "got \(out.length)")
        // Stays compressed: far smaller than 2 s of Float32 stereo PCM (705 KB).
        let bytes = (try FileManager.default.attributesOfItem(atPath: dst.path())[.size] as? Int) ?? .max
        #expect(bytes < 100_000, "got \(bytes) bytes")
    }

    @Test func evictsOldestOverLimit() {
        let now = Date.now
        let files: [(url: URL, accessed: Date, bytes: Int)] = [
            (URL(fileURLWithPath: "/a"), now.addingTimeInterval(-300), 300),
            (URL(fileURLWithPath: "/b"), now.addingTimeInterval(-100), 300),
            (URL(fileURLWithPath: "/c"), now, 300),
        ]
        #expect(AudioCache.evictionList(files, limit: 600) == [URL(fileURLWithPath: "/a")])
        #expect(AudioCache.evictionList(files, limit: 1_000).isEmpty)
    }
}
