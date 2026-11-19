import Foundation

nonisolated enum RepeatMode: String, Sendable {
    case off, all, one
    var next: RepeatMode { self == .off ? .all : self == .all ? .one : .off }
    var symbol: String { self == .one ? "repeat.1" : "repeat" }
}

/// Pure queue reordering for shuffle, kept out of Player so it can be tested.
nonisolated enum QueueOps {
    /// Shuffles only the songs after `current`; what already played and what's playing stay put.
    static func shuffled(_ queue: [Song], current: Int) -> [Song] {
        guard queue.indices.contains(current) else { return queue.shuffled() }
        return Array(queue[...current]) + queue[(current + 1)...].shuffled()
    }

    /// Next songs that keep the mood: YouTube lists the closest songs first and drifts further down,
    /// so take only the head of the list, with songs the session's starting song also leads to first.
    static func coherent(_ candidates: [Song], anchor: [Song], excluding: Set<String>, limit: Int) -> [Song] {
        let pool = candidates.filter { !excluding.contains($0.id) }
        let fitsSession = Set(anchor.map(\.id))
        let shared = pool.filter { fitsSession.contains($0.id) }
        let rest = pool.filter { !fitsSession.contains($0.id) }
        return Array((shared + rest).prefix(limit))
    }

    /// Does a song fit the session's feel? Rejects big tempo jumps (beyond ±30%, half/double time counts as
    /// the same feel) and big loudness jumps (over 6 dB). Unsure tempos aren't judged.
    static func fitsMood(_ song: TrackAnalysis, session: [TrackAnalysis]) -> Bool {
        let minConfidence = 0.2
        let tempos = session.filter { $0.confidence >= minConfidence }.map(\.bpm).sorted()
        if song.confidence >= minConfidence, !tempos.isEmpty {
            let reference = tempos[tempos.count / 2]
            let off = [1.0, 2.0, 0.5].map { abs(song.bpm * $0 / reference - 1) }.min()!
            if off > 0.30 { return false }
        }
        let levels = session.compactMap(\.loudness)
        if let level = song.loudness, !levels.isEmpty {
            if abs(level - levels.reduce(0, +) / Double(levels.count)) > 6 { return false }
        }
        return true
    }

    /// Back to the original order, keeping songs added while shuffled (autoplay) at the end.
    static func unshuffled(original: [Song], shuffled: [Song], current: Int) -> (queue: [Song], index: Int) {
        let known = Set(original.map(\.id))
        let queue = original + shuffled.filter { !known.contains($0.id) }
        let currentId = shuffled.indices.contains(current) ? shuffled[current].id : nil
        let index = currentId.flatMap { id in queue.firstIndex { $0.id == id } } ?? 0
        return (queue, index)
    }
}
