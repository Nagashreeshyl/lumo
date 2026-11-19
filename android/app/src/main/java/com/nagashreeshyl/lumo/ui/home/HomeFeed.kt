package com.nagashreeshyl.lumo.ui.home

import com.nagashreeshyl.lumo.data.db.Track
import com.nagashreeshyl.lumo.logic.Rank
import com.nagashreeshyl.lumo.model.BrowseSection
import com.nagashreeshyl.lumo.model.Shelf
import com.nagashreeshyl.lumo.model.Song
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Builds Home progressively (port of the iPhone Recommender): `update` runs each time a source arrives
 * (Quick picks, "Because you liked", YouTube home), so the screen fills in as fast as the quickest source.
 */
object HomeFeed {
    private sealed interface Part {
        data class Quick(val songs: List<Song>) : Part
        data class Liked(val shelf: Shelf?) : Part
        data class YouTube(val sections: List<BrowseSection>) : Part
    }

    suspend fun build(
        tracks: List<Track>,
        radio: suspend (String) -> List<Song>,
        youtube: suspend () -> List<BrowseSection>,
        update: (List<Shelf>, List<BrowseSection>) -> Unit,
    ) = coroutineScope {
        val disliked = tracks.filter { it.disliked || Rank.isDisliked(it.skipCount, it.playCount) }.map { it.videoId }.toSet()
        val recent = tracks.filter { it.lastPlayed != null }.sortedByDescending { it.lastPlayed }.take(20).map { it.song }
        val seeds = Rank.seeds(tracks.map { Rank.Seed(it.videoId, it.playCount, it.liked, it.skipCount, it.lastPlayed) })
        val repeats = Rank.onRepeat(tracks.map { Rank.Listen(it.song, it.playCount, it.disliked, it.lastPlayed) })

        val parts = Channel<Part>(3)
        launch {
            val radios = seeds.map { id -> runCatching { radio(id) }.getOrDefault(emptyList()) }
            parts.send(Part.Quick(Rank.interleave(radios, disliked + seeds, 20)))
        }
        launch {
            val fav = tracks.filter { it.liked }.maxByOrNull { it.likedAt ?: 0L }
            val songs = fav?.let { f -> runCatching { radio(f.videoId) }.getOrDefault(emptyList()).filter { it.id !in disliked }.take(15) }.orEmpty()
            parts.send(Part.Liked(if (fav != null && songs.isNotEmpty()) Shelf("Because you liked ${fav.title}", songs) else null))
        }
        launch { parts.send(Part.YouTube(runCatching { youtube() }.getOrDefault(emptyList()))) }

        var quick = emptyList<Song>(); var liked: Shelf? = null; var yt = emptyList<BrowseSection>()
        repeat(3) {
            when (val p = parts.receive()) {
                is Part.Quick -> quick = p.songs
                is Part.Liked -> liked = p.shelf
                is Part.YouTube -> yt = p.sections
            }
            val shelves = mutableListOf<Shelf>()
            if (quick.isNotEmpty()) shelves += Shelf("Quick picks", quick)
            if (repeats.isNotEmpty()) shelves += Shelf("On repeat", repeats)
            liked?.let { shelves += it }
            if (recent.isNotEmpty()) shelves += Shelf("Recently played", recent)
            val personal = shelves.map { it.title }.toSet()
            val fresh = yt.filter { it.title !in personal && !it.title.contains("episode", ignoreCase = true) }
            shelves += fresh.filter { it.songs.size == it.items.size }.map { Shelf(it.title, it.songs) }
            val more = fresh.filter { it.songs.size != it.items.size }
            // Don't replace what's on screen with a half-empty page from a source that failed.
            if (quick.isNotEmpty() || yt.isNotEmpty()) update(shelves, more)
        }
    }
}
