package com.nagashreeshyl.nive.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM track WHERE videoId = :id") fun track(id: String): Track?
    @Query("SELECT * FROM track") fun allTracks(): List<Track>
    @Upsert fun upsert(track: Track)
    @Upsert fun upsertAll(tracks: List<Track>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertIfMissing(track: Track)

    @Query("SELECT * FROM track WHERE liked ORDER BY likedAt DESC") fun liked(): Flow<List<Track>>
    @Query("SELECT * FROM track WHERE liked ORDER BY likedAt DESC") fun likedNow(): List<Track>
    @Query("SELECT * FROM track WHERE playCount > 0 ORDER BY playCount DESC") fun played(): Flow<List<Track>>
    @Query("SELECT * FROM track WHERE localFile IS NOT NULL ORDER BY title") fun downloads(): Flow<List<Track>>
    @Query("SELECT * FROM track WHERE lastPlayed IS NOT NULL ORDER BY lastPlayed DESC LIMIT :limit") fun history(limit: Int): Flow<List<Track>>
    @Query("SELECT * FROM track WHERE disliked ORDER BY title") fun hidden(): Flow<List<Track>>
    @Query("SELECT * FROM track") fun tracksFlow(): Flow<List<Track>>
    @Query("SELECT videoId FROM track WHERE disliked") fun dislikedIds(): List<String>
    @Query("SELECT videoId FROM track WHERE disliked OR skipCount >= 2") fun skippedOrDisliked(): List<String>

    @Query("SELECT * FROM playlist ORDER BY createdAt DESC") fun playlists(): Flow<List<Playlist>>
    @Query("SELECT * FROM playlist") fun allPlaylists(): List<Playlist>
    @Query("SELECT * FROM playlist WHERE id = :id") fun playlist(id: Long): Flow<Playlist?>
    @Query("SELECT * FROM playlist WHERE id = :id") fun playlistNow(id: Long): Playlist?
    @Insert fun insert(playlist: Playlist): Long
    @Update fun update(playlist: Playlist)
    @Delete fun delete(playlist: Playlist)
}

@Database(entities = [Track::class, Playlist::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NiveDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        // shortcut: main-thread queries allowed — single-row reads/writes on a small local DB; move to IO if it ever janks.
        fun open(context: Context) = Room.databaseBuilder(context, NiveDb::class.java, "nive.db").allowMainThreadQueries().build()
    }
}
