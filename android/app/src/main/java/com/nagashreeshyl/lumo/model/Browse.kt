package com.nagashreeshyl.lumo.model

import kotlinx.serialization.Serializable

/** Something you can open: an album, artist, playlist or mood/genre page. */
@Serializable
data class Ref(
    val kind: Kind,
    val id: String,             // browseId (album/artist/category) or playlist id without "VL"
    val title: String,
    val subtitle: String,
    val thumbnail: String? = null,
    val params: String? = null, // category pages
    val color: Long? = null,    // mood/genre tile stripe (ARGB)
) {
    @Serializable enum class Kind { album, artist, playlist, category }
    fun artwork(px: Int): String? = Artwork.sized(thumbnail, px)
}

/** One search / shelf entry. */
@Serializable
sealed interface Item {
    val key: String
    @Serializable data class SongItem(val song: Song) : Item { override val key get() = "s:" + song.id }
    @Serializable data class PageItem(val ref: Ref) : Item { override val key get() = ref.kind.name + ":" + ref.id + (ref.params ?: "") }
}

@Serializable
data class BrowseSection(val title: String, val items: List<Item>) {
    val songs: List<Song> get() = items.mapNotNull { (it as? Item.SongItem)?.song }
}

data class SearchFilter(val title: String, val params: String)
data class SearchPage(val top: Item? = null, val items: List<Item> = emptyList(), val filters: List<SearchFilter> = emptyList())
data class Header(val title: String, val subtitle: String, val thumbnail: String?)
data class BrowsePage(val header: Header?, val sections: List<BrowseSection>)
