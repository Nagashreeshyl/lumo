package com.nagashreeshyl.lumo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.nagashreeshyl.lumo.model.Song
import kotlinx.serialization.json.Json

/** A song the user has interacted with: likes, plays, skips, downloads, Not Interested (same fields as iPhone). */
@Entity(tableName = "track")
data class Track(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val duration: Int,
    val liked: Boolean = false,
    val likedAt: Long? = null,        // epoch ms
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayed: Long? = null,     // epoch ms
    val localFile: String? = null,    // set when downloaded
    val disliked: Boolean = false,
    val listenedSeconds: Double = 0.0,
) {
    val song get() = Song(videoId, title, artist, thumbnail, duration)
    companion object {
        fun of(song: Song) = Track(song.id, song.title, song.artist, song.thumbnail, song.duration)
    }
}

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val trackIds: List<String> = emptyList(),   // ordered videoIds
)

class Converters {
    @TypeConverter fun fromIds(ids: List<String>): String = Json.encodeToString(ids)
    @TypeConverter fun toIds(json: String): List<String> = Json.decodeFromString(json)
}
