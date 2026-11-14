import Testing
@testable import Nive

struct QueueTests {
    private func songs(_ ids: [String]) -> [Song] { ids.map { Song(id: $0, title: $0, artist: "", thumbnail: nil, duration: 0) } }

    @Test func shuffleKeepsPlayedAndCurrentInPlace() {
        let q = songs(["a", "b", "c", "d", "e", "f"])
        let s = QueueOps.shuffled(q, current: 2)
        #expect(Array(s.prefix(3)).map(\.id) == ["a", "b", "c"])
        #expect(Set(s.dropFirst(3).map(\.id)) == ["d", "e", "f"])
        #expect(s.count == q.count)
    }

    @Test func unshuffleRestoresOriginalOrderAroundCurrent() {
        let original = songs(["a", "b", "c", "d", "e"])
        let shuffled = songs(["a", "b", "e", "c", "d"])   // now playing "e" at index 2
        let (queue, index) = QueueOps.unshuffled(original: original, shuffled: shuffled, current: 2)
        #expect(queue.map(\.id) == ["a", "b", "c", "d", "e"])
        #expect(index == 4)
    }

    @Test func unshuffleKeepsSongsAddedWhileShuffled() {
        let original = songs(["a", "b", "c"])
        let shuffled = songs(["a", "c", "b", "x"])   // "x" added by autoplay during shuffle
        let (queue, index) = QueueOps.unshuffled(original: original, shuffled: shuffled, current: 1)
        #expect(queue.map(\.id) == ["a", "b", "c", "x"])
        #expect(index == 2)
    }

    @Test func repeatModeCycles() {
        #expect(RepeatMode.off.next == .all)
        #expect(RepeatMode.all.next == .one)
        #expect(RepeatMode.one.next == .off)
    }
}
