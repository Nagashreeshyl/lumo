package com.nagashreeshyl.nive.player

import android.util.Log
import com.nagashreeshyl.nive.audio.MixEngine
import com.nagashreeshyl.nive.audio.TrackAnalysis
import com.nagashreeshyl.nive.audio.TransitionPlanner
import com.nagashreeshyl.nive.logic.QueueOps
import com.nagashreeshyl.nive.logic.RepeatMode
import com.nagashreeshyl.nive.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the queue, repeat/shuffle, autoplay and listening signals (port of the iPhone app's Player).
 * The MixEngine does the actual playback.
 */
class PlayerController(
    val engine: MixEngine,
    private val radio: RadioApi,
    private val library: LibraryApi,
    private val store: QueueStore,
    private val analysis: suspend (Song) -> TrackAnalysis?,
    private val scope: CoroutineScope,
    private val onStreamExpired: (String) -> Unit = {},
) {
    private val _queue = MutableStateFlow<List<Song>>(emptyList()); val queue = _queue.asStateFlow()
    private val _index = MutableStateFlow(0); val index = _index.asStateFlow()
    private val _current = MutableStateFlow<Song?>(null); val current = _current.asStateFlow()
    private val _isPlaying = MutableStateFlow(false); val isPlaying = _isPlaying.asStateFlow()
    private val _isLoading = MutableStateFlow(false); val isLoading = _isLoading.asStateFlow()
    private val _position = MutableStateFlow(0.0); val position = _position.asStateFlow()
    private val _duration = MutableStateFlow(0.0); val duration = _duration.asStateFlow()
    private val _repeat = MutableStateFlow(RepeatMode.OFF); val repeat = _repeat.asStateFlow()
    private val _shuffle = MutableStateFlow(false); val shuffle = _shuffle.asStateFlow()
    private val _isMixing = MutableStateFlow(false); val isMixing = _isMixing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _disliked = MutableStateFlow(library.dislikedIds()); val disliked: StateFlow<Set<String>> = _disliked.asStateFlow()
    private val _automix = MutableStateFlow(true); val automixFlow = _automix.asStateFlow()

    var automix: Boolean
        get() = _automix.value
        set(v) { _automix.value = v; reschedule() }
    var autoplay = true
    /** Called whenever anything the media session shows changes. */
    var onChange: () -> Unit = {}

    private var unshuffled: List<Song>? = null
    private var prepared = false              // engine has the current song loaded
    private var pendingStart = 0.0            // restored position to start from
    private var prefetchJob: Job? = null
    private var failJob: Job? = null
    private var refilling = false
    private var sessionAnchor: Song? = null
    private var anchorMix: List<Song> = emptyList()
    private val autoAdded = mutableSetOf<String>()
    private val sessionProfile = ArrayDeque<Pair<String, TrackAnalysis>>()
    private var moodSkips = 0
    private var playCounted = false
    private var lastTick: Double? = null
    private var failuresInARow = 0
    private var retried403: String? = null
    private var ticksSinceSave = 0

    init {
        engine.onFinished = {
            if (_repeat.value == RepeatMode.ONE) current.value?.let { engine.start(it); didStart() } else next(finished = true)
        }
        engine.onSwapped = { id -> advance(id); didStart() }
        engine.onMixing = { _isMixing.value = it; onChange() }
        engine.onError = ::streamFailed
        engine.onReady = { _isLoading.value = false; _duration.value = engine.duration; onChange() }
    }

    /** Drives the engine (~60 Hz) and the 4 Hz bookkeeping tick. */
    fun startTicker() = scope.launch {
        var n = 0
        while (isActive) {
            engine.step()
            if (n++ % 15 == 0) tick()
            delay(16)
        }
    }

    val upNext: List<Song> get() = queue.value.drop(index.value + 1)

    fun visible(songs: List<Song>): List<Song> = disliked.value.let { d -> if (d.isEmpty()) songs else songs.filter { it.id !in d } }

    // ---- Queue control

    fun play(all: List<Song>, at: Int = 0) {
        if (at !in all.indices) return
        val tapped = all[at]
        val songs = all.filterIndexed { i, s -> i == at || s.id !in disliked.value }
        val start = songs.indexOf(tapped).coerceAtLeast(0)
        sessionAnchor = tapped; anchorMix = emptyList(); autoAdded.clear(); sessionProfile.clear()
        recordSkipIfNeeded()
        if (_shuffle.value) { unshuffled = songs; setQueue(QueueOps.shuffled(songs, start), start) } else setQueue(songs, start)
        load()
    }

    fun playNext(song: Song) {
        val q = queue.value.toMutableList().apply { add(minOf(index.value + 1, size), song) }
        setQueue(q, index.value)
        if (q.size == 1) load() else reschedule()
    }

    fun addToQueue(song: Song) {
        setQueue(queue.value + song, index.value)
        if (queue.value.size == 1) load() else if (queue.value.size == index.value + 2) reschedule()
    }

    fun jump(i: Int) {
        if (i !in queue.value.indices) return
        recordSkipIfNeeded()
        setQueue(queue.value, i)
        load()
    }

    fun next(finished: Boolean = false) {
        if (!finished) recordSkipIfNeeded()
        if (index.value + 1 >= queue.value.size) {
            if (_repeat.value == RepeatMode.ALL && queue.value.isNotEmpty()) { setQueue(queue.value, 0); load() } else pause()
            return
        }
        if (!finished) engine.cutToScheduled()?.let { id ->
            _isPlaying.value = true
            advance(id); didStart(); return
        }
        setQueue(queue.value, index.value + 1)
        load()
    }

    fun previous() {
        if (engine.currentTime > 3 || index.value == 0) { seek(0.0); return }
        setQueue(queue.value, index.value - 1)
        load()
    }

    fun toggle() = if (_isPlaying.value) pause() else resume()

    fun resume() {
        val song = current.value ?: return
        if (!prepared) { load(at = pendingStart); return }
        engine.resume(); _isPlaying.value = true; onChange()
        if (_error.value != null) _error.value = null
        song.let { }
    }

    fun pause() {
        engine.pause(); _isPlaying.value = false; save(); onChange()
    }

    fun seek(sec: Double) {
        _position.value = sec
        engine.seek(sec)
        reschedule()
        onChange()
    }

    fun setRepeat(mode: RepeatMode) { _repeat.value = mode; reschedule(); onChange() }

    fun toggleShuffle() {
        val original = unshuffled
        if (_shuffle.value && original != null) {
            val (q, i) = QueueOps.unshuffled(original, queue.value, index.value)
            unshuffled = null; setQueue(q, i)
        } else {
            unshuffled = queue.value; setQueue(QueueOps.shuffled(queue.value, index.value), index.value)
        }
        _shuffle.value = !_shuffle.value
        reschedule(); onChange()
    }

    /** Swiped away in Up Next: drop it and never auto-queue or recommend it again. */
    fun notInterested(at: Int) {
        val q = queue.value.toMutableList()
        if (at !in q.indices || at == index.value) return
        val song = q.removeAt(at)
        unshuffled = unshuffled?.filter { it.id != song.id }
        setQueue(q, if (at < index.value) index.value - 1 else index.value)
        markDisliked(song)
        if (at == index.value + 1) reschedule()
        scope.launch { refillIfNeeded() }
    }

    fun dislike(song: Song) {
        val i = queue.value.withIndex().drop(index.value + 1).firstOrNull { it.value.id == song.id }?.index
        if (i != null) notInterested(i) else markDisliked(song)
    }

    fun undislike(id: String) { library.undislike(id); _disliked.value = _disliked.value - id }

    private fun markDisliked(song: Song) { library.markDisliked(song); _disliked.value = _disliked.value + song.id }

    /** Restores the last queue (paused) after the app was closed. */
    suspend fun restore() {
        val saved = store.load() ?: return
        if (saved.queue.isEmpty()) return
        setQueue(saved.queue, saved.index.coerceIn(saved.queue.indices))
        pendingStart = saved.position
        _position.value = saved.position
        _duration.value = current.value?.duration?.toDouble() ?: 0.0
        onChange()
    }

    // ---- Loading

    private fun load(at: Double = 0.0) {
        val song = current.value ?: return
        prefetchJob?.cancel(); failJob?.cancel()
        pendingStart = 0.0
        retried403 = null
        _position.value = at
        _duration.value = song.duration.toDouble()
        _isLoading.value = true
        _error.value = null
        engine.start(song, at)
        prepared = true
        _isPlaying.value = true
        didStart()
    }

    private fun streamFailed(id: String, code: Int?) {
        val song = current.value ?: return
        if (song.id != id) return
        if ((code == 403 || code == 410) && retried403 != id) {
            // Expired stream URL (long pause): resolve again and carry on where we were.
            retried403 = id
            onStreamExpired(id)
            val at = engine.currentTime
            engine.start(song, at, play = _isPlaying.value)
            return
        }
        Log.i("Nive", "load failed $id: HTTP $code")
        _isLoading.value = false
        failuresInARow += 1
        if (failuresInARow >= 3) {
            // Several failures in a row means the network or YouTube is down: stop instead of burning the queue.
            _isPlaying.value = false
            prepared = false
            _error.value = "Can't reach YouTube right now. Check your connection and try again."
            onChange()
            return
        }
        _error.value = "Couldn't play “${song.title}”"
        failJob = scope.launch {
            delay(1500)
            if (current.value?.id == song.id) next(finished = true)
        }
    }

    private fun advance(id: String) {
        val i = queue.value.withIndex().drop(index.value + 1).firstOrNull { it.value.id == id }?.index ?: (index.value + 1)
        setQueue(queue.value, i.coerceAtMost(queue.value.lastIndex))
    }

    /** Bookkeeping whenever a new song becomes current (load, handover or cut). */
    private fun didStart() {
        current.value ?: return
        moodSkips = 0; playCounted = false; lastTick = null
        _duration.value = if (engine.duration > 0) engine.duration else current.value!!.duration.toDouble()
        _position.value = engine.currentTime
        onChange()
        scope.launch {
            refillIfNeeded()
            reschedule()
        }
    }

    private fun fallback(duration: Double) = TrackAnalysis(duration, 120.0, 0.0, 0.0, 0.0, duration)

    /** Prepares the next song and plans the handover (an AutoMix blend, or gapless when AutoMix is off). */
    private fun reschedule() {
        prefetchJob?.cancel()
        engine.cancelScheduled()
        val song = current.value ?: return
        if (_repeat.value == RepeatMode.ONE || index.value + 1 !in queue.value.indices) return
        val next = queue.value[index.value + 1]
        prefetchJob = scope.launch {
            val real = analysis(song)
            if (real != null && sessionProfile.none { it.first == song.id }) {
                sessionProfile.addLast(song.id to real)
                while (sessionProfile.size > 3) sessionProfile.removeFirst()   // feel of the last 3 songs
            }
            val upcoming = analysis(next)
            if (current.value?.id != song.id || queue.value.getOrNull(index.value + 1)?.id != next.id) return@launch
            // Mood guard: an autoplay pick far from the session's tempo/loudness is skipped (not disliked).
            if (upcoming != null && next.id in autoAdded && moodSkips < 4 && !QueueOps.fitsMood(upcoming, sessionProfile.map { it.second })) {
                moodSkips += 1
                Log.i("Nive", "mood guard skipped ${next.id}")
                setQueue(queue.value.toMutableList().apply { removeAt(index.value + 1) }, index.value)
                unshuffled = unshuffled?.filter { it.id != next.id }
                refillIfNeeded()
                reschedule()
                return@launch
            }
            val a = real ?: fallback(if (engine.duration > 0) engine.duration else song.duration.toDouble())
            val b = upcoming ?: fallback(next.duration.toDouble())
            val plan = if (automix && a.duration > 0) TransitionPlanner.plan(a, b, engine.currentTime) else TransitionPlanner.gapless(a)
            engine.schedule(next, plan)
        }
    }

    /** Keeps the queue going with YouTube's Mix for the current song. */
    private suspend fun refillIfNeeded() {
        val song = current.value ?: return
        if (!autoplay || refilling || queue.value.size - index.value > 3) return
        refilling = true
        try {
            val mix = runCatching { radio.youtubeMix(song.id) }.getOrDefault(emptyList())
            val candidates = if (mix.size >= 5) mix else runCatching { radio.radio(song.id) }.getOrDefault(mix).ifEmpty { mix }
            val anchor = sessionAnchor
            if (anchor != null && anchor.id != song.id && anchorMix.isEmpty()) anchorMix = runCatching { radio.youtubeMix(anchor.id) }.getOrDefault(emptyList())
            val recent = queue.value.takeLast(50).map { it.id }.toSet()
            val picked = QueueOps.coherent(candidates, anchorMix, recent + library.skippedOrDisliked() + disliked.value, 8)
            autoAdded += picked.map { it.id }
            if (picked.isNotEmpty()) setQueue(queue.value + picked, index.value)
        } finally {
            refilling = false
        }
    }

    /** 4 Hz: position, listening time and real plays (counted after 30 s). */
    fun tick() {
        val t = engine.currentTime
        _position.value = t
        val song = current.value
        val last = lastTick
        if (_isPlaying.value && song != null && last != null) {
            val delta = t - last
            if (delta > 0 && delta < 2) library.addListened(song, delta)
            val d = if (engine.duration > 0) engine.duration else song.duration.toDouble()
            if (!playCounted && t >= minOf(30.0, maxOf(d - 1, 1.0))) {
                playCounted = true
                library.recordPlay(song)
                failuresInARow = 0
            }
        }
        lastTick = t
        if (++ticksSinceSave >= 40) save()   // every ~10 s
    }

    private fun recordSkipIfNeeded() {
        val song = current.value ?: return
        val t = engine.currentTime
        if (t > 0 && t < 30) library.recordSkip(song.id)
    }

    private fun setQueue(q: List<Song>, i: Int) {
        _queue.value = q
        _index.value = i
        _current.value = q.getOrNull(i)
        save()
        onChange()
    }

    private fun save() {
        ticksSinceSave = 0
        store.save(SavedQueue(queue.value.takeLast(500), index.value.coerceAtMost(499), _position.value))
    }
}
