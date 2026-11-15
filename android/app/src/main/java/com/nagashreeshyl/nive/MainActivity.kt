package com.nagashreeshyl.nive

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nagashreeshyl.nive.data.Repo
import com.nagashreeshyl.nive.model.Song
import com.nagashreeshyl.nive.player.PlaybackService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as NiveApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        // Connecting a controller starts the playback service, which then runs in the foreground while music plays.
        MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { DebugSearch() }
            }
        }
    }

    /** shortcut: temporary search-and-play screen until the real UI (Task 7). */
    @androidx.compose.runtime.Composable
    private fun DebugSearch() {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf(emptyList<Song>()) }
        val scope = rememberCoroutineScope()
        val current by app.player.current.collectAsState()
        val playing by app.player.isPlaying.collectAsState()
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            OutlinedTextField(query, { query = it; scope.launch { results = Repo.search(it) } }, Modifier.fillMaxWidth(), label = { Text("Search") })
            Text("${if (playing) "▶" else "⏸"} ${current?.title ?: ""}", Modifier.padding(vertical = 8.dp).clickable { app.player.toggle() })
            LazyColumn {
                itemsIndexed(results) { i, s ->
                    Text("${s.title} — ${s.artist}", Modifier.fillMaxWidth().clickable { app.player.play(results, i) }.padding(vertical = 10.dp))
                }
            }
        }
    }
}
