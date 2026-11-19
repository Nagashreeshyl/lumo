package com.nagashreeshyl.lumo.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nagashreeshyl.lumo.logic.RepeatMode
import com.nagashreeshyl.lumo.lyrics.LyricLine
import com.nagashreeshyl.lumo.lyrics.LyricsParse
import com.nagashreeshyl.lumo.lyrics.LyricsRepo
import com.nagashreeshyl.lumo.model.Song
import com.nagashreeshyl.lumo.ui.Artwork
import com.nagashreeshyl.lumo.ui.SongMenu
import com.nagashreeshyl.lumo.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Panel { Artwork, Lyrics, Queue }

fun formatTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** Full-screen player over everything: artwork (or lyrics / Up Next), scrubber and controls. Drag down to close. */
@Composable
fun FullPlayer() {
    val app = app()
    val show by app.showPlayer.collectAsState()
    val song by app.player.current.collectAsState()
    AnimatedVisibility(show && song != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        song?.let { PlayerContent(it) { app.showPlayer.value = false } }
    }
}

@Composable
private fun PlayerContent(song: Song, close: () -> Unit) {
    val app = app()
    val p = app.player
    var panel by remember { mutableStateOf(Panel.Artwork) }
    var drag by remember { mutableFloatStateOf(0f) }
    BackHandler { if (panel != Panel.Artwork) panel = Panel.Artwork else close() }
    Box(Modifier.fillMaxSize().offset { IntOffset(0, drag.roundToInt()) }.draggable(rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) }, Orientation.Vertical,
        onDragStopped = { if (drag > 300f) close(); drag = 0f })) {
        Ambient(song)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.4f)).clickable(onClick = close))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(panel, label = "panel") { current ->
                    when (current) {
                        Panel.Artwork -> ArtworkPanel(song)
                        Panel.Lyrics -> LyricsPanel(song)
                        Panel.Queue -> QueuePanel()
                    }
                }
            }
            TitleRow(song)
            Scrubber()
            Controls()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                PanelButton(Icons.Rounded.FormatQuote, "Lyrics", panel == Panel.Lyrics) { panel = if (panel == Panel.Lyrics) Panel.Artwork else Panel.Lyrics }
                PanelButton(Icons.AutoMirrored.Rounded.QueueMusic, "Up Next", panel == Panel.Queue) { panel = if (panel == Panel.Queue) Panel.Artwork else Panel.Queue }
            }
        }
    }
}

/** Blurred, darkened artwork filling the screen (gradient from the art on phones without blur). */
@Composable
private fun Ambient(song: Song) {
    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        AsyncImage(song.artwork(300), null, Modifier.fillMaxSize().scale(1.6f).blur(90.dp), contentScale = ContentScale.Crop,
            alpha = if (Build.VERSION.SDK_INT >= 31) 1f else 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f)))))
    }
}

@Composable
private fun ArtworkPanel(song: Song) {
    val playing by app().player.isPlaying.collectAsState()
    val scale by animateFloatAsState(if (playing) 1f else 0.86f, label = "art")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Artwork(song.artwork(800), 340.dp, Modifier.scale(scale).aspectRatio(1f), shape = RoundedCornerShape(14.dp))
    }
}

@Composable
private fun TitleRow(song: Song) {
    val app = app()
    val haptics = LocalHapticFeedback.current
    val likedIds by remember { app.library.dao.liked().map { l -> l.map { it.videoId }.toSet() } }.collectAsState(emptySet())
    val liked = song.id in likedIds
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton({ app.player.dislike(song); app.player.next() }) { Icon(Icons.Rounded.ThumbDown, "Not Interested", tint = Color.White.copy(alpha = 0.8f)) }
        IconButton({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); app.library.setLiked(song, !liked) }) {
            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", tint = Color.White)
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "More", tint = Color.White) }
            SongMenu(song, menu) { menu = false }
        }
    }
}

@Composable
private fun Scrubber() {
    val p = app().player
    val position by p.position.collectAsState()
    val duration by p.duration.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.toDouble() ?: position
    Column(Modifier.padding(top = 8.dp)) {
        Slider(shown.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)), { dragging = it },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            onValueChangeFinished = { dragging?.let { p.seek(it.toDouble()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)))
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shown), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("-" + formatTime(duration - shown), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Controls() {
    val p = app().player
    val playing by p.isPlaying.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val mixing by p.isMixing.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(if (mixing) "Mixing" else " ", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ p.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)) }
            IconButton({ p.previous() }, Modifier.size(60.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(40.dp), tint = Color.White) }
            IconButton({ p.toggle() }, Modifier.size(76.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(60.dp), tint = Color.White)
            }
            IconButton({ p.next() }, Modifier.size(60.dp)) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(40.dp), tint = Color.White) }
            IconButton({ p.setRepeat(repeat.next) }) {
                Icon(if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                    tint = if (repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f)) }
}

/** Synced lyrics: the sung line is bright and kept in view; tap a line to jump there. */
@Composable
private fun LyricsPanel(song: Song) {
    val p = app().player
    var lines by remember(song.id) { mutableStateOf<List<LyricLine>?>(null) }
    var english by remember(song.id) { mutableStateOf(false) }
    var query by remember(song.id) { mutableStateOf("") }
    LaunchedEffect(song.id) { lines = withContext(Dispatchers.IO) { LyricsRepo.live.fetch(song) } }
    val position by p.position.collectAsState()
    val l = lines
    when {
        l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading lyrics…", color = Color.White.copy(alpha = 0.6f)) }
        l.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No lyrics found", color = Color.White, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("Song and artist") }, singleLine = true)
            var searching by remember { mutableStateOf(false) }
            LaunchedEffect(searching) { if (searching) { lines = withContext(Dispatchers.IO) { LyricsRepo.live.search(query) }; searching = false } }
            TextButton({ searching = true }, enabled = query.isNotBlank()) { Text("Search Lyrics") }
        }
        else -> {
            val canRomanize = remember(l) { LyricsParse.needsRomanization(l) }
            val shown = remember(l, english) { if (english) l.map { it.copy(text = LyricsParse.romanize(it.text)) } else l }
            val active = LyricsParse.activeIndex(shown, position)
            val list = rememberLazyListState()
            LaunchedEffect(active) { active?.let { list.animateScrollToItem((it - 2).coerceAtLeast(0)) } }
            Column(Modifier.fillMaxSize()) {
                if (canRomanize) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("English letters", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    Switch(english, { english = it })
                }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(shown) { i, line ->
                        val synced = line.time != null
                        Text(line.text.ifEmpty { "♪" }, Modifier.fillMaxWidth().clickable(enabled = synced) { line.time?.let { p.seek(it) } }.padding(vertical = 8.dp),
                            color = if (!synced || i == active) Color.White else Color.White.copy(alpha = 0.35f),
                            fontSize = if (synced) 24.sp else 18.sp, fontWeight = if (synced) FontWeight.Bold else FontWeight.Medium, lineHeight = 30.sp)
                    }
                }
            }
        }
    }
}

/** Up Next: tap to jump, swipe left for Not Interested, AutoMix switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuePanel() {
    val p = app().player
    val queue by p.queue.collectAsState()
    val index by p.index.collectAsState()
    val automix by p.automixFlow.collectAsState()
    val settings = app().settings
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up Next", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text("AutoMix", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(end = 8.dp))
            Switch(automix, { p.automix = it; settings.setAutomix(it) })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            val upcoming = queue.withIndex().drop(index + 1).toList()
            itemsIndexed(upcoming, key = { _, v -> "${v.index}:${v.value.id}" }) { _, (i, song) ->
                val state = rememberSwipeToDismissBoxState()
                LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) p.notInterested(i) }
                SwipeToDismissBox(state, backgroundContent = {
                    if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)                     Box(Modifier.fillMaxSize().background(Color(0xFFB3261E)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                        Text("Not Interested", color = Color.White)
                    }
                }, enableDismissFromStartToEnd = false) {
                    Row(Modifier.fillMaxWidth().background(Color.Transparent).clickable { p.jump(i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artwork(120), 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (upcoming.isEmpty()) item { Text("Nothing up next yet.", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 24.dp)) }
        }
    }
}
