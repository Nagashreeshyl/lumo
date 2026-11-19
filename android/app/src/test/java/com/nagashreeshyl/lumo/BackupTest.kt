package com.nagashreeshyl.lumo

import com.nagashreeshyl.lumo.data.LibraryBackup
import com.nagashreeshyl.lumo.data.db.Playlist
import com.nagashreeshyl.lumo.data.db.Track
import com.nagashreeshyl.lumo.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupTest {
    private fun song(id: String) = Song(id, "T$id", "A", null, 200)
    private fun fixture() = javaClass.getResource("/ios-backup.json")!!.readText()

    @Test fun exportThenImportRestoresEverything() {
        val tracks = listOf(
            Track.of(song("1")).copy(liked = true, likedAt = 1_000_000L, playCount = 7, listenedSeconds = 900.0),
            Track.of(song("2")).copy(disliked = true),
            Track.of(song("3")).copy(localFile = "3"),
        )
        val json = LibraryBackup.export(tracks, listOf(Playlist(name = "Gym", createdAt = 5_000L, trackIds = listOf("1", "3"))))
        val m = LibraryBackup.merge(LibraryBackup.decode(json), existing = { null }, playlists = emptyList())
        val byId = m.tracks.associateBy { it.videoId }
        assertTrue(byId["1"]!!.liked); assertEquals(7, byId["1"]!!.playCount)
        assertTrue(byId["2"]!!.disliked)
        assertEquals(listOf("3"), m.toDownload.map { it.id })
        assertNull(byId["3"]!!.localFile)
        assertEquals(listOf("Gym"), m.newPlaylists.map { it.name }); assertEquals(listOf("1", "3"), m.newPlaylists[0].trackIds)
    }

    @Test fun importMergesWithoutLosingNewerListening() {
        val json = LibraryBackup.export(listOf(Track.of(song("1")).copy(playCount = 3, liked = true)),
            listOf(Playlist(name = "Chill", createdAt = 0, trackIds = listOf("1"))))
        val current = Track.of(song("1")).copy(playCount = 10)
        val existingList = Playlist(id = 4, name = "Chill", createdAt = 0, trackIds = listOf("9"))
        val m = LibraryBackup.merge(LibraryBackup.decode(json), existing = { if (it == "1") current else null }, playlists = listOf(existingList))
        assertEquals(10, m.tracks.single().playCount); assertTrue(m.tracks.single().liked)
        assertTrue(m.newPlaylists.isEmpty())
        assertEquals(listOf("9", "1"), m.updatedPlaylists.single().trackIds); assertEquals(4L, m.updatedPlaylists.single().id)
    }

    @Test fun importsIosFixture() {
        val m = LibraryBackup.merge(LibraryBackup.decode(fixture()), existing = { null }, playlists = emptyList())
        val byId = m.tracks.associateBy { it.videoId }
        val a = byId["abc123"]!!
        assertTrue(a.liked); assertEquals(Instant.parse("2026-10-02T09:16:41.512Z").toEpochMilli(), a.likedAt)
        assertEquals(1234.5, a.listenedSeconds, 0.0); assertEquals("Blinding Lights", a.title)
        assertNull(byId["def456"]!!.thumbnail); assertTrue(byId["def456"]!!.disliked)
        assertEquals(listOf("abc123"), m.toDownload.map { it.id })
        assertEquals(listOf("abc123", "def456"), m.newPlaylists.single().trackIds)
        assertEquals(2, m.songs); assertEquals(1, m.playlists)
    }

    @Test fun exportIsIosReadable() {
        val json = LibraryBackup.export(listOf(Track.of(song("1")).copy(liked = true, likedAt = 1_700_000_000_123L)),
            listOf(Playlist(name = "P", createdAt = 1_700_000_000_000L, trackIds = listOf("1"))))
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(1, o["version"]!!.jsonPrimitive.content.toInt())
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(o["exported"]!!.jsonPrimitive.content))
        val t = o["tracks"]!!.jsonArray[0].jsonObject
        for (k in listOf("song", "liked", "playCount", "skipCount", "disliked", "listenedSeconds", "downloaded")) assertTrue(k, k in t)
        assertEquals("2023-11-14T22:13:20Z", t["likedAt"]!!.jsonPrimitive.content)
        assertTrue("lastPlayed" !in t)   // nil dates are omitted, like Swift
        val s = t["song"]!!.jsonObject
        for (k in listOf("id", "title", "artist", "duration")) assertTrue(k, k in s)
        val p = o["playlists"]!!.jsonArray[0].jsonObject
        for (k in listOf("name", "createdAt", "trackIds")) assertTrue(k, k in p)
    }
}
