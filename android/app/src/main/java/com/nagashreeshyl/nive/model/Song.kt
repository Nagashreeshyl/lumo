package com.nagashreeshyl.nive.model

import kotlinx.serialization.Serializable

/** A YouTube Music song. Same JSON shape as the iPhone app's `Song`, so library backups move between them. */
@Serializable
data class Song(
    val id: String,          // YouTube videoId
    val title: String,
    val artist: String,
    val thumbnail: String? = null,
    val duration: Int = 0,   // seconds, 0 = unknown
) {
    fun artwork(px: Int): String = Artwork.sized(thumbnail, px) ?: Artwork.fallback(id)
}

@Serializable
data class Shelf(val title: String, val songs: List<Song>)

object Artwork {
    fun fallback(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private val size = Regex("""w\d+-h\d+""")

    /** Asks Google's image server for a square image of `px` pixels. */
    fun sized(url: String?, px: Int): String? {
        if (url.isNullOrEmpty()) return null
        val u = url.replace(Regex("""(?<=[sh]\d{1,5})-c"""), "")
        if (!u.contains("googleusercontent.com") && !u.contains("ggpht.com")) return u
        return if (size.containsMatchIn(u)) u.replace(size, "w$px-h$px") else u.substringBefore("=") + "=w$px-h$px"
    }
}
