package com.nagashreeshyl.nive.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nagashreeshyl.nive.NiveApplication
import com.nagashreeshyl.nive.model.BrowseSection
import com.nagashreeshyl.nive.model.Item
import com.nagashreeshyl.nive.model.Ref
import com.nagashreeshyl.nive.model.Song

@Composable
fun app(): NiveApplication = LocalContext.current.applicationContext as NiveApplication

@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun SectionHeader(title: String, onPlay: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onPlay != null) IconButton(onPlay) { Icon(Icons.Rounded.PlayArrow, "Play $title", tint = MaterialTheme.colorScheme.primary) }
    }
}

/** One song in a list: artwork, title, artist and a ⋯ menu. */
@Composable
fun SongRow(song: Song, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val current by app().player.current.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.artwork(120), 48.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current?.id == song.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "More for ${song.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            SongMenu(song, menu) { menu = false }
        }
    }
}

/** Play Next, Add to Queue, Add to Playlist, Like, Download, Not Interested. */
@Composable
fun SongMenu(song: Song, expanded: Boolean, onDismiss: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    var pickPlaylist by remember { mutableStateOf(false) }
    val downloading by app.downloads.active.collectAsState()
    val track = remember(expanded) { app.library.existing(song.id) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("Play Next") }, { app.player.playNext(song); onDismiss() }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) })
        DropdownMenuItem({ Text("Add to Queue") }, { app.player.addToQueue(song); onDismiss(); toast("Added to queue") },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) })
        DropdownMenuItem({ Text("Add to Playlist…") }, { pickPlaylist = true; onDismiss() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
        val liked = track?.liked == true
        DropdownMenuItem({ Text(if (liked) "Remove from Liked" else "Like") }, { app.library.setLiked(song, !liked); onDismiss() },
            leadingIcon = { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) })
        when {
            track?.localFile != null -> DropdownMenuItem({ Text("Remove Download") }, { app.downloads.delete(song.id); onDismiss() },
                leadingIcon = { Icon(Icons.Rounded.DownloadDone, null) })
            song.id in downloading -> DropdownMenuItem({ Text("Downloading…") }, {}, enabled = false, leadingIcon = { Icon(Icons.Rounded.Download, null) })
            else -> DropdownMenuItem({ Text("Download") }, { app.downloads.download(song); onDismiss(); toast("Downloading") },
                leadingIcon = { Icon(Icons.Rounded.Download, null) })
        }
        HorizontalDivider()
        DropdownMenuItem({ Text("Not Interested") }, { app.player.dislike(song); onDismiss(); toast("You won't see this song again") },
            leadingIcon = { Icon(Icons.Rounded.Block, null) })
    }
    if (pickPlaylist) AddToPlaylistDialog(song) { pickPlaylist = false }
}

@Composable
fun AddToPlaylistDialog(song: Song, onDismiss: () -> Unit) {
    val app = app()
    val lists by app.library.dao.playlists().collectAsState(emptyList())
    var newName by remember { mutableStateOf("") }
    AlertDialog(onDismiss, confirmButton = {
        TextButton({
            if (newName.isNotBlank()) {
                val id = app.library.createPlaylist(newName.trim())
                app.library.dao.playlistNow(id)?.let { app.library.add(it, song) }
                onDismiss()
            }
        }, enabled = newName.isNotBlank()) { Text("Create") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } }, title = { Text("Add to Playlist") }, text = {
        Column {
            lists.forEach { list ->
                Text(list.name, Modifier.fillMaxWidth().clickable { app.library.add(list, song); onDismiss() }.padding(vertical = 12.dp))
            }
            OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text("New playlist") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Add, null) })
        }
    })
}

/** Quick picks: four rows of songs that page sideways. */
@Composable
fun QuickPicks(songs: List<Song>, onPlay: (Int) -> Unit) {
    val width = LocalConfiguration.current.screenWidthDp.dp * 0.86f
    LazyHorizontalGrid(GridCells.Fixed(4), Modifier.height(60.dp * 4)) {
        items(songs.size) { i -> SongRow(songs[i], Modifier.width(width).height(60.dp)) { onPlay(i) } }
    }
}

@Composable
fun SongCard(song: Song, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(150.dp).clickable(onClick = onClick)) {
        Box {
            Artwork(song.artwork(400), 150.dp, shape = RoundedCornerShape(10.dp))
            SongMenu(song, menu) { menu = false }
        }
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PageCard(ref: Ref, onClick: () -> Unit) {
    val circle = ref.kind == Ref.Kind.artist
    Column(Modifier.width(150.dp).clickable(onClick = onClick), horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start) {
        Artwork(ref.artwork(400), 150.dp, shape = if (circle) CircleShape else RoundedCornerShape(10.dp))
        Text(ref.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
        if (ref.subtitle.isNotEmpty()) Text(ref.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SongCarousel(songs: List<Song>, onPlay: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(songs.size) { i -> SongCard(songs[i]) { onPlay(i) } }
    }
}

@Composable
fun ItemCarousel(section: BrowseSection, open: (Ref) -> Unit) {
    val player = app().player
    val songs = section.songs
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(section.items, key = { it.key }) { item ->
            when (item) {
                is Item.SongItem -> SongCard(item.song) { player.play(songs, songs.indexOf(item.song)) }
                is Item.PageItem -> if (item.ref.kind == Ref.Kind.category) MoodTile(item.ref, Modifier.width(160.dp)) { open(item.ref) }
                    else PageCard(item.ref) { open(item.ref) }
            }
        }
    }
}

@Composable
fun MoodTile(ref: Ref, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val stripe = ref.color?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    Row(modifier.height(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
        Text(ref.title, Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A page result in a list (album, artist, playlist). */
@Composable
fun PageRow(ref: Ref, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(ref.artwork(120), 48.dp, shape = if (ref.kind == Ref.Kind.artist) CircleShape else RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(ref.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(ref.kind.name.replaceFirstChar { it.uppercase() }, ref.subtitle).filter { it.isNotEmpty() }.distinct().joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Loading() = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    androidx.compose.material3.CircularProgressIndicator()
}

@Composable
fun Empty(title: String, message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) TextButton(onRetry) { Text("Try Again") }
    }
}
