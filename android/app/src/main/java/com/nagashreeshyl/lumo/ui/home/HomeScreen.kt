package com.nagashreeshyl.lumo.ui.home

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nagashreeshyl.lumo.LumoApplication
import com.nagashreeshyl.lumo.R
import com.nagashreeshyl.lumo.data.Repo
import com.nagashreeshyl.lumo.model.BrowseSection
import com.nagashreeshyl.lumo.model.Shelf
import com.nagashreeshyl.lumo.ui.Empty
import com.nagashreeshyl.lumo.ui.ItemCarousel
import com.nagashreeshyl.lumo.ui.Loading
import com.nagashreeshyl.lumo.ui.QuickPicks
import com.nagashreeshyl.lumo.ui.SectionHeader
import com.nagashreeshyl.lumo.ui.SongCarousel
import com.nagashreeshyl.lumo.ui.app
import com.nagashreeshyl.lumo.ui.open
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

@Serializable
data class HomeState(val shelves: List<Shelf> = emptyList(), val more: List<BrowseSection> = emptyList())

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LumoApplication
    private val cache = File(application.cacheDir, "home.json")
    val state = MutableStateFlow(runCatching { Json.decodeFromString<HomeState>(cache.readText()) }.getOrDefault(HomeState()))
    val loading = MutableStateFlow(false)
    val failed = MutableStateFlow(false)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        loading.value = true; failed.value = false
        val tracks = withContext(Dispatchers.IO) { app.library.dao.allTracks() }
        var got = false
        HomeFeed.build(tracks, Repo::radio, Repo::homeSections) { shelves, more ->
            got = true
            state.value = HomeState(shelves.map { it.copy(songs = app.player.visible(it.songs)) }, more)
        }
        if (got) withContext(Dispatchers.IO) { runCatching { cache.writeText(Json.encodeToString(HomeState.serializer(), state.value)) } }
        else failed.value = state.value.shelves.isEmpty()
        loading.value = false
    }
}

private fun greeting() = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val loading by vm.loading.collectAsState()
    val failed by vm.failed.collectAsState()
    val player = app().player
    PullToRefreshBox(loading && state.shelves.isNotEmpty(), { vm.refresh() }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
                    Text("Lumo", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    IconButton({ nav.navigate("settings") }) { Icon(Icons.Rounded.Settings, "Settings") }
                }
                Text(greeting(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
            if (state.shelves.isEmpty() && state.more.isEmpty()) item {
                if (failed) Empty("Couldn't load Home", "Check your connection and try again.") { vm.refresh() } else Loading()
            }
            items(state.shelves, key = { "s:" + it.title }) { shelf ->
                SectionHeader(shelf.title) { player.play(shelf.songs) }
                if (shelf.title == "Quick picks") QuickPicks(shelf.songs) { player.play(shelf.songs, it) }
                else SongCarousel(shelf.songs) { player.play(shelf.songs, it) }
            }
            items(state.more, key = { "m:" + it.title }) { section ->
                SectionHeader(section.title)
                ItemCarousel(section) { nav.open(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
