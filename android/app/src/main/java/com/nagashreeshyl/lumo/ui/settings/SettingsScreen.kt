package com.nagashreeshyl.lumo.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nagashreeshyl.lumo.auth.LoginScreen
import com.nagashreeshyl.lumo.logic.Rank
import com.nagashreeshyl.lumo.ui.Empty
import com.nagashreeshyl.lumo.ui.SectionHeader
import com.nagashreeshyl.lumo.ui.SongRow
import com.nagashreeshyl.lumo.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

fun NavGraphBuilder.settingsRoutes(nav: NavController) {
    composable("settings") { SettingsScreen(nav) }
    composable("stats") { StatsScreen(nav) }
    composable("hidden") { HiddenSongsScreen(nav) }
    composable("login") { LoginScreen(nav) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(nav: NavController, title: String) =
    TopAppBar({ Text(title) }, windowInsets = WindowInsets(0),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })

@Composable
private fun Footer(text: String) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SettingsScreen(nav: NavController) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val automix by app.player.automixFlow.collectAsState()
    var autoplay by remember { mutableStateOf(app.settings.autoplay) }
    val account by app.session.account.collectAsState()
    val hidden by app.player.disliked.collectAsState()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = app.library.exportBackup()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
            }.onSuccess { toast("Library exported.") }.onFailure { toast("Couldn't export: ${it.message}") }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                app.library.importBackup(text)
            }.onSuccess { r ->
                r.toDownload.forEach(app.downloads::download)
                app.player.reloadDisliked()
                toast("Imported ${r.songs} songs and ${r.playlists} playlists." + if (r.toDownload.isEmpty()) "" else " Re-downloading ${r.toDownload.size} songs.")
            }.onFailure { toast("That file couldn't be imported.") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar(nav, "Settings")
        LazyColumn {
            item { SectionHeader("YouTube Music account") }
            item {
                if (account != null) ListItem({ Text("Signed in as $account") },
                    trailingContent = { TextButton({ app.session.signOut() }) { Text("Sign Out", color = MaterialTheme.colorScheme.error) } })
                else ListItem({ Text("Sign in with Google") }, trailingContent = { TextButton({ nav.navigate("login") }) { Text("Sign In") } })
                Footer("Optional. Signing in gives you your personal YouTube Music home and mixes. Lumo works fully without it.")
            }
            item { HorizontalDivider(Modifier.padding(top = 12.dp)) }
            item {
                ListItem({ Text("Listening stats") }, leadingContent = { Icon(Icons.Rounded.BarChart, null) },
                    trailingContent = { TextButton({ nav.navigate("stats") }) { Text("Open") } })
            }
            item { SectionHeader("Backup") }
            item {
                ListItem({ Text("Export Library") }, leadingContent = { Icon(Icons.Rounded.Upload, null) },
                    trailingContent = { TextButton({ export.launch("Lumo Library ${LocalDate.now()}.json") }) { Text("Export") } })
                ListItem({ Text("Import Library") }, leadingContent = { Icon(Icons.Rounded.Download, null) },
                    trailingContent = { TextButton({ import.launch(arrayOf("application/json", "*/*")) }) { Text("Import") } })
                Footer("Saves likes, playlists, stats and hidden songs to a file, to move them to another install, an iPhone or a Mac. Downloaded songs are downloaded again after import.")
            }
            item { HorizontalDivider(Modifier.padding(top = 12.dp)) }
            item {
                ListItem({ Text("Hidden songs (${hidden.size})") }, leadingContent = { Icon(Icons.Rounded.Block, null) },
                    trailingContent = { TextButton({ nav.navigate("hidden") }) { Text("Show") } })
                Footer("Songs you marked Not Interested never show up anywhere in Lumo.")
            }
            item {
                ListItem({ Text("AutoMix") }, trailingContent = { Switch(automix, { app.player.automix = it; app.settings.setAutomix(it) }) })
                Footer("Blends songs into each other with matched beats, like a DJ.")
            }
            item {
                ListItem({ Text("Autoplay similar songs") }, trailingContent = {
                    Switch(autoplay, { autoplay = it; app.player.autoplay = it; app.settings.setAutoplay(it) })
                })
            }
            item { Footer("Lumo is free and open source (GPL-3.0). Not affiliated with YouTube or Google.") }
        }
    }
}

@Composable
fun StatsScreen(nav: NavController) {
    val app = app()
    val tracks by app.library.dao.played().collectAsState(emptyList())
    val plays = tracks.sumOf { it.playCount }
    val seconds = tracks.sumOf { it.listenedSeconds }
    val artists = tracks.groupBy { it.artist }.filterKeys { it.isNotEmpty() }
        .map { (name, list) -> name to list.sumOf { it.playCount } }.sortedByDescending { it.second }
    Column(Modifier.fillMaxSize()) {
        Bar(nav, "Listening stats")
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(Rank.listening(seconds), "listened")
                    Stat("$plays", if (plays == 1) "play" else "plays")
                    Stat("${tracks.size}", if (tracks.size == 1) "song" else "songs")
                }
                Footer("A play counts after 30 seconds of listening.")
            }
            if (tracks.isEmpty()) item { Empty("No stats yet", "Play some songs and your stats will show up here.") }
            else {
                item { SectionHeader("Most played songs") }
                val top = tracks.take(10)
                itemsIndexed(top, key = { _, t -> t.videoId }) { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.padding(start = 16.dp).width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) { SongRow(t.song) { app.player.play(top.map { it.song }, i) } }
                        Text("${t.playCount}×", Modifier.padding(end = 16.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { SectionHeader("Top artists") }
                itemsIndexed(artists.take(10)) { i, (name, count) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("${i + 1}", Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(name, Modifier.weight(1f), maxLines = 1)
                        Text("$count plays", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun HiddenSongsScreen(nav: NavController) {
    val app = app()
    val tracks by app.library.dao.hidden().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        Bar(nav, "Hidden songs")
        LazyColumn {
            if (tracks.isEmpty()) item { Empty("No hidden songs", "Songs you mark Not Interested show up here.") }
            itemsIndexed(tracks, key = { _, t -> t.videoId }) { _, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SongRow(t.song) {} }
                    TextButton({ app.player.undislike(t.videoId) }, Modifier.padding(end = 8.dp)) { Text("Show Again") }
                }
            }
        }
    }
}
