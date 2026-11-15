package com.nagashreeshyl.nive.ui.search

import android.app.Application
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nagashreeshyl.nive.NiveApplication
import com.nagashreeshyl.nive.data.Repo
import com.nagashreeshyl.nive.model.Item
import com.nagashreeshyl.nive.model.Ref
import com.nagashreeshyl.nive.model.SearchPage
import com.nagashreeshyl.nive.ui.Artwork
import com.nagashreeshyl.nive.ui.Empty
import com.nagashreeshyl.nive.ui.Loading
import com.nagashreeshyl.nive.ui.PageRow
import com.nagashreeshyl.nive.ui.SongRow
import com.nagashreeshyl.nive.ui.open
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("search", Context.MODE_PRIVATE)
    val query = MutableStateFlow("")
    val suggestions = MutableStateFlow(emptyList<String>())
    val submitted = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow<String?>(null)
    val page = MutableStateFlow<SearchPage?>(null)
    val recents = MutableStateFlow(prefs.getString("recents", "")!!.split("\n").filter { it.isNotBlank() })
    private var typing: Job? = null
    private var searching: Job? = null
    val player = (application as NiveApplication).player

    fun type(text: String) {
        query.value = text
        submitted.value = null
        typing?.cancel()
        typing = viewModelScope.launch {
            delay(250)
            suggestions.value = if (text.isBlank()) emptyList() else Repo.suggestions(text)
        }
    }

    fun submit(text: String = query.value, filter: String? = null) {
        val q = text.trim().ifEmpty { return }
        query.value = q; submitted.value = q; this.filter.value = filter; page.value = null
        recents.value = (listOf(q) + recents.value.filter { !it.equals(q, true) }).take(10)
        prefs.edit().putString("recents", recents.value.joinToString("\n")).apply()
        searching?.cancel()
        searching = viewModelScope.launch {
            val p = Repo.searchPage(q, filter)
            page.value = p.copy(items = p.items.filter { it !is Item.SongItem || it.song.id !in player.disliked.value })
        }
    }

    fun clearRecents() { recents.value = emptyList(); prefs.edit().remove("recents").apply() }
}

@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = viewModel()) {
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val page by vm.page.collectAsState()
    val filter by vm.filter.collectAsState()
    val recents by vm.recents.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun go(q: String) { keyboard?.hide(); focus.clearFocus(); vm.submit(q) }

    Column(Modifier.fillMaxSize()) {
        Text("Search", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        TextField(query, vm::type, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Songs, artists, albums") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.type("") }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go(query) }),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        when {
            submitted == null && query.isNotBlank() -> LazyColumn {
                items(suggestions) { s ->
                    Row(Modifier.fillMaxWidth().clickable { go(s) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s, Modifier.padding(start = 14.dp))
                    }
                }
            }
            submitted == null -> LazyColumn {
                if (recents.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        androidx.compose.material3.TextButton(vm::clearRecents) { Text("Clear") }
                    }
                }
                items(recents) { r ->
                    Row(Modifier.fillMaxWidth().clickable { go(r) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r, Modifier.padding(start = 14.dp))
                    }
                }
            }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(filter == null, { vm.submit(submitted!!, null) }, { Text("All") }) }
                    items(Repo.filters) { f -> FilterChip(filter == f.params, { vm.submit(submitted!!, f.params) }, { Text(f.title) }) }
                }
                val p = page
                LazyColumn(Modifier.fillMaxSize()) {
                    if (p == null) { item { Loading() }; return@LazyColumn }
                    if (p.items.isEmpty() && p.top == null) { item { Empty("No results", "Try different words, or check your connection.") { vm.submit(submitted!!, filter) } }; return@LazyColumn }
                    p.top?.let { top -> item { TopResult(top, nav, vm) } }
                    val songs = p.items.mapNotNull { (it as? Item.SongItem)?.song }
                    items(p.items, key = { it.key }) { item ->
                        when (item) {
                            is Item.SongItem -> SongRow(item.song) { vm.player.play(songs, songs.indexOf(item.song)) }
                            is Item.PageItem -> PageRow(item.ref) { nav.open(item.ref) }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TopResult(item: Item, nav: NavController, vm: SearchViewModel) {
    val (title, subtitle, art, circle) = when (item) {
        is Item.SongItem -> listOf(item.song.title, "Song • " + item.song.artist, item.song.artwork(300), false)
        is Item.PageItem -> listOf(item.ref.title, listOf(item.ref.kind.name.replaceFirstChar { it.uppercase() }, item.ref.subtitle).filter { it.isNotEmpty() }.distinct().joinToString(" • "),
            item.ref.artwork(300), item.ref.kind == Ref.Kind.artist)
    }
    Surface(Modifier.fillMaxWidth().padding(16.dp).clickable {
        when (item) { is Item.SongItem -> vm.player.play(listOf(item.song)); is Item.PageItem -> nav.open(item.ref) }
    }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(art as String?, 84.dp, shape = if (circle as Boolean) CircleShape else RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title as String, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(subtitle as String, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (item is Item.SongItem) FilledTonalButton({ vm.player.play(listOf(item.song)) }, Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Text("Play", Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
