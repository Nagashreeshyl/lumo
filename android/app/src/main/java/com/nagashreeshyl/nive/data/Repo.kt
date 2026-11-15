package com.nagashreeshyl.nive.data

import android.util.Log
import com.music.innertube.YouTube
import com.music.innertube.YouTube.SearchFilter as YTFilter
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import com.nagashreeshyl.nive.lyrics.LyricsParse
import com.nagashreeshyl.nive.model.BrowsePage
import com.nagashreeshyl.nive.model.BrowseSection
import com.nagashreeshyl.nive.model.Header
import com.nagashreeshyl.nive.model.Item
import com.nagashreeshyl.nive.model.Ref
import com.nagashreeshyl.nive.model.SearchFilter
import com.nagashreeshyl.nive.model.SearchPage
import com.nagashreeshyl.nive.model.Song
import kotlinx.coroutines.sync.withLock

fun SongItem.toSong() = Song(
    id = id,
    title = LyricsParse.cleanTitle(title),
    artist = artists.joinToString(", ") { it.name },
    thumbnail = thumbnail.ifEmpty { null },
    duration = duration ?: 0,
)

fun YTItem.toItem(): Item? = when (this) {
    is SongItem -> Item.SongItem(toSong())
    is AlbumItem -> Item.PageItem(Ref(Ref.Kind.album, browseId, title,
        listOfNotNull(artists?.joinToString(", ") { it.name }?.ifEmpty { null }, year?.toString()).joinToString(" • "), thumbnail))
    is ArtistItem -> Item.PageItem(Ref(Ref.Kind.artist, id, title, "Artist", thumbnail))
    is PlaylistItem -> Item.PageItem(Ref(Ref.Kind.playlist, id.removePrefix("VL"), title,
        listOfNotNull(author?.name, songCountText).joinToString(" • "), thumbnail))
    else -> null   // podcasts, episodes
}

private fun List<YTItem>.items() = mapNotNull { it.toItem() }.distinctBy { it.key }

/** YouTube Music → Nive models. Network or parse failures come back as empty results, never exceptions. */
object Repo {
    private const val TAG = "Nive"

    /** Same filters the iPhone app offers. */
    val filters = listOf(
        SearchFilter("Songs", YTFilter.FILTER_SONG.value),
        SearchFilter("Videos", YTFilter.FILTER_VIDEO.value),
        SearchFilter("Albums", YTFilter.FILTER_ALBUM.value),
        SearchFilter("Artists", YTFilter.FILTER_ARTIST.value),
        SearchFilter("Featured playlists", YTFilter.FILTER_FEATURED_PLAYLIST.value),
        SearchFilter("Community playlists", YTFilter.FILTER_COMMUNITY_PLAYLIST.value),
    )

    fun <T, R> orEmpty(what: String, result: Result<T>, map: (T) -> List<R>): List<R> =
        result.fold(map) { Log.w(TAG, "$what failed: ${it.message}"); emptyList() }

    /** Titles starting with a short query first, keeping YouTube's order otherwise. */
    fun rank(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        fun tier(s: Song): Int {
            val t = s.title.lowercase()
            return when {
                t.startsWith(q) -> 0
                t.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(q) } -> 1
                else -> 2
            }
        }
        return songs.withIndex().sortedWith(compareBy({ tier(it.value) }, { it.index })).map { it.value }
    }

    suspend fun search(query: String): List<Song> {
        val songs = orEmpty("search", YouTube.search(query, YTFilter.FILTER_SONG)) { r -> r.items.filterIsInstance<SongItem>().map { it.toSong() } }
        return if (query.trim().length <= 3) rank(songs, query) else songs
    }

    suspend fun searchPage(query: String, filter: String?): SearchPage {
        if (filter != null) {
            val items = orEmpty("search", YouTube.search(query, YTFilter(filter))) { it.items.items() }
            return SearchPage(items = items, filters = filters)
        }
        val summaries = orEmpty("searchSummary", YouTube.searchSummary(query)) { it.summaries }
        val topSummary = summaries.firstOrNull { it.title.contains("top", ignoreCase = true) }
        val top = topSummary?.items?.firstNotNullOfOrNull { it.toItem() }
        val rest = summaries.flatMap { it.items }.items().filter { it.key != top?.key }
        return SearchPage(top, rest, filters)
    }

    suspend fun suggestions(query: String): List<String> =
        orEmpty("suggestions", YouTube.searchSuggestions(query)) { it.queries }

    private val visitorLock = kotlinx.coroutines.sync.Mutex()

    /** YouTube serves a one-shelf home (and no continuations) without a visitor id; fetch one once. */
    suspend fun ensureVisitor() = visitorLock.withLock {
        if (YouTube.visitorData == null) YouTube.visitorData = YouTube.visitorData().getOrNull()
    }

    /** YouTube Music's home (personalised when signed in): first page plus up to 5 continuations. */
    suspend fun homeSections(): List<BrowseSection> {
        ensureVisitor()
        val first = YouTube.home().getOrElse { Log.w(TAG, "home failed: ${it.message}"); return emptyList() }
        val sections = first.sections.toMutableList()
        var next = first.continuation
        repeat(5) {
            val c = next ?: return@repeat
            val more = YouTube.home(c).getOrNull() ?: return@repeat
            sections += more.sections; next = more.continuation
        }
        return sections.map { BrowseSection(it.title, it.items.items()) }.filter { it.items.isNotEmpty() }.distinctBy { it.title }
    }

    suspend fun explore(): List<BrowseSection> {
        ensureVisitor()
        val page = YouTube.explore().getOrElse { Log.w(TAG, "explore failed: ${it.message}"); return emptyList() }
        val moods = page.moodAndGenres.map {
            Item.PageItem(Ref(Ref.Kind.category, it.endpoint.browseId, it.title, "", null, it.endpoint.params, it.stripeColor))
        }
        return listOf(
            BrowseSection("New albums", page.newReleaseAlbums.items()),
            BrowseSection("Moods & genres", moods),
        ).filter { it.items.isNotEmpty() }
    }

    suspend fun category(ref: Ref): BrowsePage {
        val r = YouTube.browse(ref.id, ref.params).getOrElse { Log.w(TAG, "category failed: ${it.message}"); return BrowsePage(null, emptyList()) }
        return BrowsePage(Header(r.title ?: ref.title, "", null),
            r.items.map { BrowseSection(it.title ?: "", it.items.items()) }.filter { it.items.isNotEmpty() })
    }

    suspend fun album(id: String): BrowsePage {
        val p = YouTube.album(id).getOrElse { Log.w(TAG, "album failed: ${it.message}"); return BrowsePage(null, emptyList()) }
        val songs = p.songs.map { s -> s.toSong().let { if (it.thumbnail == null) it.copy(thumbnail = p.album.thumbnail) else it } }
        val subtitle = listOfNotNull(p.album.artists?.joinToString(", ") { it.name }, p.album.year?.toString()).joinToString(" • ")
        return BrowsePage(Header(p.album.title, subtitle, p.album.thumbnail), listOf(
            BrowseSection("", songs.map { Item.SongItem(it) }),
            BrowseSection("Other versions", p.otherVersions.items()),
        ).filter { it.items.isNotEmpty() })
    }

    suspend fun artist(id: String): BrowsePage {
        val p = YouTube.artist(id).getOrElse { Log.w(TAG, "artist failed: ${it.message}"); return BrowsePage(null, emptyList()) }
        return BrowsePage(Header(p.artist.title, p.subscriberCountText ?: "", p.artist.thumbnail),
            p.sections.map { BrowseSection(it.title, it.items.items()) }.filter { it.items.isNotEmpty() })
    }

    /** Playlist songs, following up to 4 continuations (~500 songs). */
    suspend fun playlist(id: String): BrowsePage {
        val p = YouTube.playlist(id).getOrElse { Log.w(TAG, "playlist failed: ${it.message}"); return BrowsePage(null, emptyList()) }
        val songs = p.songs.toMutableList()
        var next = p.songsContinuation
        repeat(4) {
            val c = next ?: return@repeat
            val more = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
            songs += more.songs; next = more.continuation
        }
        val subtitle = listOfNotNull(p.playlist.author?.name, p.playlist.songCountText).joinToString(" • ")
        return BrowsePage(Header(p.playlist.title, subtitle, p.playlist.thumbnail),
            listOf(BrowseSection("", songs.map { Item.SongItem(it.toSong()) }.distinctBy { it.key }))
                .filter { it.items.isNotEmpty() })
    }

    private suspend fun next(videoId: String, playlistId: String): List<Song> =
        orEmpty("next $playlistId", YouTube.next(WatchEndpoint(videoId = videoId, playlistId = playlistId))) { r ->
            r.items.map { it.toSong() }.filter { it.id != videoId }
        }

    /** YouTube's Mix for a song (closest songs first), falling back to YouTube Music radio. */
    suspend fun youtubeMix(videoId: String): List<Song> =
        next(videoId, "RD$videoId").ifEmpty { radio(videoId) }

    suspend fun radio(videoId: String): List<Song> = next(videoId, "RDAMVM$videoId")
}
