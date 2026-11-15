package com.nagashreeshyl.nive.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nagashreeshyl.nive.ui.Artwork
import com.nagashreeshyl.nive.ui.app

/** Floating bar above the tabs: current song, play/pause, next. Tap opens the full player. */
@Composable
fun MiniPlayer() {
    val app = app()
    val p = app.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    val position by p.position.collectAsState()
    val duration by p.duration.collectAsState()
    val mixing by p.isMixing.collectAsState()
    AnimatedVisibility(song != null, enter = expandVertically(), exit = shrinkVertically()) {
        val s = song ?: return@AnimatedVisibility
        Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clickable { app.showPlayer.value = true },
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 6.dp) {
            Column {
                Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(s.artwork(120), 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(if (mixing) "Mixing…" else s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = if (mixing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ p.toggle() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                    IconButton({ p.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                }
                LinearProgressIndicator({ if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f },
                    Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent, drawStopIndicator = {})
            }
        }
    }
}
