package com.nagashreeshyl.nive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nagashreeshyl.nive.data.Repo
import com.nagashreeshyl.nive.model.BrowsePage
import com.nagashreeshyl.nive.model.Ref
import com.nagashreeshyl.nive.model.Song
import com.nagashreeshyl.nive.ui.Artwork
import com.nagashreeshyl.nive.ui.Empty
import com.nagashreeshyl.nive.ui.ItemCarousel
import com.nagashreeshyl.nive.ui.Loading
import com.nagashreeshyl.nive.ui.SectionHeader
import com.nagashreeshyl.nive.ui.SongRow
import com.nagashreeshyl.nive.ui.app
import com.nagashreeshyl.nive.ui.open

/** Album, artist, playlist or mood page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(nav: NavController, ref: Ref) {
    var page by remember { mutableStateOf<BrowsePage?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(ref, attempt) {
        page = when (ref.kind) {
            Ref.Kind.album -> Repo.album(ref.id)
            Ref.Kind.artist -> Repo.artist(ref.id)
            Ref.Kind.playlist -> Repo.playlist(ref.id)
            Ref.Kind.category -> Repo.category(ref)
        }
    }
    val player = app().player
    Column(Modifier.fillMaxSize()) {
        TopAppBar({}, windowInsets = androidx.compose.foundation.layout.WindowInsets(0), navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        val p = page
        LazyColumn(Modifier.fillMaxSize()) {
            if (p == null) { item { Loading() }; return@LazyColumn }
            if (p.sections.isEmpty()) { item { Empty("Couldn't load this page", "Check your connection and try again.") { page = null; attempt++ } }; return@LazyColumn }
            val allSongs = p.sections.flatMap { it.songs }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    p.header?.thumbnail?.let {
                        Artwork(com.nagashreeshyl.nive.model.Artwork.sized(it, 544), 220.dp,
                            shape = if (ref.kind == Ref.Kind.artist) CircleShape else RoundedCornerShape(14.dp))
                    }
                    Text(p.header?.title ?: ref.title, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    p.header?.subtitle?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (allSongs.isNotEmpty()) PlayShuffle(allSongs)
                }
            }
            p.sections.forEach { section ->
                if (section.songs.size == section.items.size) {
                    if (section.title.isNotEmpty()) item { SectionHeader(section.title) }
                    itemsIndexed(section.songs, key = { i, s -> section.title + i + s.id }) { i, song -> SongRow(song) { player.play(section.songs, i) } }
                } else {
                    item { SectionHeader(section.title) }
                    item { ItemCarousel(section) { nav.open(it) } }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PlayShuffle(songs: List<Song>) {
    val player = app().player
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton({ player.play(songs) }, Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Text("Play", Modifier.padding(start = 6.dp)) }
        FilledTonalButton({ player.play(songs.shuffled()) }, Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle", Modifier.padding(start = 6.dp)) }
    }
}
