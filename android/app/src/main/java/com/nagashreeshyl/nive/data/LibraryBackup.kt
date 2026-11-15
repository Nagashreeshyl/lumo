package com.nagashreeshyl.nive.data

import com.nagashreeshyl.nive.data.db.Playlist
import com.nagashreeshyl.nive.data.db.Track
import com.nagashreeshyl.nive.model.Song
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/** ISO-8601 dates like Swift's JSONEncoder (whole seconds); fractional seconds are accepted when reading. */
object IsoDate : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("IsoDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) =
        encoder.encodeString(Instant.ofEpochMilli(value).truncatedTo(ChronoUnit.SECONDS).toString())
    override fun deserialize(decoder: Decoder): Long = Instant.parse(decoder.decodeString()).toEpochMilli()
}

/**
 * Library backup as one JSON file, the same format as the iPhone and Mac apps, so a library moves between them.
 * Audio isn't included; downloaded songs are downloaded again after import.
 */
object LibraryBackup {
    @Serializable
    data class File(
        val version: Int = 1,
        @Serializable(with = IsoDate::class) val exported: Long = System.currentTimeMillis(),
        val tracks: List<TrackRecord>,
        val playlists: List<PlaylistRecord>,
    )

    @Serializable
    data class TrackRecord(
        val song: Song,
        val liked: Boolean = false,
        @Serializable(with = IsoDate::class) val likedAt: Long? = null,
        val playCount: Int = 0,
        val skipCount: Int = 0,
        @Serializable(with = IsoDate::class) val lastPlayed: Long? = null,
        val disliked: Boolean = false,
        val listenedSeconds: Double = 0.0,
        val downloaded: Boolean = false,
    )

    @Serializable
    data class PlaylistRecord(val name: String, @Serializable(with = IsoDate::class) val createdAt: Long, val trackIds: List<String>)

    data class Merge(
        val tracks: List<Track>,
        val newPlaylists: List<Playlist>,
        val updatedPlaylists: List<Playlist>,
        val toDownload: List<Song>,
        val songs: Int,
        val playlists: Int,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = true; coerceInputValues = true }

    fun export(tracks: List<Track>, playlists: List<Playlist>): String = json.encodeToString(File.serializer(), File(
        tracks = tracks.map { TrackRecord(it.song, it.liked, it.likedAt, it.playCount, it.skipCount, it.lastPlayed, it.disliked, it.listenedSeconds, it.localFile != null) },
        playlists = playlists.map { PlaylistRecord(it.name, it.createdAt, it.trackIds) },
    ))

    fun decode(text: String): File = json.decodeFromString(File.serializer(), text)

    /**
     * Merges without losing anything already there: counts keep the higher value, likes and Not Interested are
     * kept if either side has them, same-name playlists are merged.
     */
    fun merge(file: File, existing: (String) -> Track?, playlists: List<Playlist>): Merge {
        val toDownload = mutableListOf<Song>()
        val tracks = file.tracks.map { r ->
            val t = existing(r.song.id) ?: Track.of(r.song)
            if (r.downloaded && t.localFile == null) toDownload += r.song
            t.copy(
                playCount = maxOf(t.playCount, r.playCount),
                skipCount = maxOf(t.skipCount, r.skipCount),
                listenedSeconds = maxOf(t.listenedSeconds, r.listenedSeconds),
                liked = t.liked || r.liked,
                likedAt = if (r.liked && !t.liked) r.likedAt ?: System.currentTimeMillis() else t.likedAt,
                disliked = t.disliked || r.disliked,
                lastPlayed = listOfNotNull(t.lastPlayed, r.lastPlayed).maxOrNull(),
            )
        }
        val created = mutableListOf<Playlist>()
        val updated = mutableListOf<Playlist>()
        for (p in file.playlists) {
            val same = (updated + playlists).firstOrNull { it.name == p.name }
            if (same != null) {
                val merged = same.copy(trackIds = same.trackIds + p.trackIds.filter { it !in same.trackIds })
                updated.removeAll { it.id == same.id }; updated += merged
            } else created += Playlist(name = p.name, createdAt = p.createdAt, trackIds = p.trackIds)
        }
        return Merge(tracks, created, updated, toDownload, file.tracks.size, file.playlists.size)
    }
}
