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

    /// Back to the original order, keeping songs added while shuffled (autoplay) at the end.
    static func unshuffled(original: [Song], shuffled: [Song], current: Int) -> (queue: [Song], index: Int) {
        let known = Set(original.map(\.id))
        let queue = original + shuffled.filter { !known.contains($0.id) }
        let currentId = shuffled.indices.contains(current) ? shuffled[current].id : nil
        let index = currentId.flatMap { id in queue.firstIndex { $0.id == id } } ?? 0
        return (queue, index)
    }
}
