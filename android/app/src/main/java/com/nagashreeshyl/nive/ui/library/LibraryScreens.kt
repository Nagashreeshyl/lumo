package com.nagashreeshyl.nive.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nagashreeshyl.nive.data.db.Playlist
import com.nagashreeshyl.nive.data.db.Track
import com.nagashreeshyl.nive.model.Song
import com.nagashreeshyl.nive.ui.Artwork
import com.nagashreeshyl.nive.ui.Empty
import com.nagashreeshyl.nive.ui.SectionHeader
import com.nagashreeshyl.nive.ui.SongRow
import com.nagashreeshyl.nive.ui.app
import com.nagashreeshyl.nive.ui.pages.PlayShuffle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

fun NavGraphBuilder.libraryRoutes(nav: NavController) {
    composable("library") { LibraryScreen(nav) }
    composable("liked") { TrackListScreen(nav, liked = true) }
    composable("downloads") { TrackListScreen(nav, liked = false) }
    composable("local/{id}") { e -> PlaylistScreen(nav, e.arguments!!.getString("id")!!.toLong()) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(nav: NavController) {
    val app = app()
    val lists by app.library.dao.playlists().collectAsState(emptyList())
    val history by app.library.dao.history(10).collectAsState(emptyList())
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Library", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { LinkRow(Icons.Rounded.Favorite, "Liked Songs") { nav.navigate("liked") } }
        item { LinkRow(Icons.Rounded.DownloadForOffline, "Downloads") { nav.navigate("downloads") } }
        item { SectionHeader("Playlists") }
        item { LinkRow(Icons.Rounded.Add, "New Playlist") { app.library.createPlaylist("Playlist ${lists.size + 1}") } }
        items(lists, key = { it.id }) { list ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { nav.navigate("local/${list.id}") }
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mosaic(app.library.songs(list).take(4), 48)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(list.name)
                        Text("${list.trackIds.size} song${if (list.trackIds.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename…") }, { renaming = list; menu = false }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("Delete Playlist") }, { app.library.delete(list); menu = false }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionHeader("Recently Played") }
            val recent = history.map { it.song }
            itemsIndexed(recent, key = { _, s -> "h:" + s.id }) { i, s -> SongRow(s) { app.player.play(recent, i) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    renaming?.let { RenameDialog(it) { renaming = null } }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun RenameDialog(list: Playlist, onDismiss: () -> Unit) {
    val app = app()
    var name by remember { mutableStateOf(list.name) }
    AlertDialog(onDismiss, confirmButton = { TextButton({ app.library.rename(list, name); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }, title = { Text("Rename Playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) })
}

/** 2×2 grid of covers (or one cover / a placeholder). */
@Composable
fun Mosaic(songs: List<Song>, size: Int) {
    val half = (size / 2).dp
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 12).dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            songs.size >= 4 -> Column {
                Row { Artwork(songs[0].artwork(200), half, shape = RoundedCornerShape(0.dp)); Artwork(songs[1].artwork(200), half, shape = RoundedCornerShape(0.dp)) }
                Row { Artwork(songs[2].artwork(200), half, shape = RoundedCornerShape(0.dp)); Artwork(songs[3].artwork(200), half, shape = RoundedCornerShape(0.dp)) }
            }
            songs.isNotEmpty() -> Artwork(songs[0].artwork(400), size.dp, shape = RoundedCornerShape(0.dp))
            else -> Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(nav: NavController, title: String = "", actions: @Composable () -> Unit = {}) {
    TopAppBar({ Text(title) }, windowInsets = WindowInsets(0), navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        actions = { actions() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(label: String, onSwipe: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onSwipe() }
    SwipeToDismissBox(state, backgroundContent = {
        if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)         Box(Modifier.fillMaxSize().background(Color(0xFFB3261E)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) { Text(label, color = Color.White) }
    }, enableDismissFromStartToEnd = false) { Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() } }
}

@Composable
fun TrackListScreen(nav: NavController, liked: Boolean) {
    val app = app()
    val tracks by (if (liked) app.library.dao.liked() else app.library.dao.downloads()).collectAsState(emptyList<Track>())
    val songs = tracks.map { it.song }
    Column(Modifier.fillMaxSize()) {
        BackBar(nav, if (liked) "Liked Songs" else "Downloads")
        LazyColumn(Modifier.fillMaxSize()) {
            if (songs.isEmpty()) item {
                if (liked) Empty("No liked songs", "Tap the heart in the player to save songs here.")
                else Empty("No downloads", "Use a song's ⋯ menu and choose Download to listen offline.")
            } else item { Box(Modifier.padding(horizontal = 16.dp)) { PlayShuffle(songs) } }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                SwipeRow(if (liked) "Unlike" else "Delete", { if (liked) app.library.setLiked(s, false) else app.downloads.delete(s.id) }) {
                    SongRow(s) { app.player.play(songs, i) }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(nav: NavController, id: Long) {
    val app = app()
    val list by app.library.dao.playlist(id).collectAsState(null)
    val l = list ?: return
    val songs = remember(l) { app.library.songs(l) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var order by remember(songs) { mutableStateOf(songs) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = order.indexOfFirst { it.id == from.key }; val t = order.indexOfFirst { it.id == to.key }
        if (f >= 0 && t >= 0) order = order.toMutableList().apply { add(t, removeAt(f)) }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(nav) {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Playlist actions") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Play") }, { app.player.play(songs); menu = false }, enabled = songs.isNotEmpty(), leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
                DropdownMenuItem({ Text("Shuffle") }, { app.player.play(songs.shuffled()); menu = false }, enabled = songs.isNotEmpty(), leadingIcon = { Icon(Icons.Rounded.Shuffle, null) })
                DropdownMenuItem({ Text("Rename…") }, { renaming = true; menu = false }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                DropdownMenuItem({ Text("Delete Playlist") }, { confirmDelete = true; menu = false }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Mosaic(songs.take(4), 180)
                    Text(l.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${songs.size} song${if (songs.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (songs.isNotEmpty()) PlayShuffle(songs)
                }
            }
            if (songs.isEmpty()) item(key = "empty") { Empty("Empty playlist", "Use a song's ⋯ menu and choose Add to Playlist.") }
            items(order, key = { it.id }) { s ->
                ReorderableItem(reorder, key = s.id) {
                    SwipeRow("Remove", { app.library.remove(l, s.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SongRow(s) { app.player.play(order, order.indexOf(s)) } }
                            IconButton({}, Modifier.draggableHandle(onDragStopped = { app.library.reorder(l, order.map { it.id }) })) {
                                Icon(Icons.Rounded.DragHandle, "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (renaming) RenameDialog(l) { renaming = false }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Delete “${l.name}”?") }, text = { Text("The songs stay in your library.") },
        confirmButton = { TextButton({ app.library.delete(l); confirmDelete = false; nav.popBackStack() }) { Text("Delete Playlist") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}
