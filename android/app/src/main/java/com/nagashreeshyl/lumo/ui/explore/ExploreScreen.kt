package com.nagashreeshyl.lumo.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nagashreeshyl.lumo.data.Repo
import com.nagashreeshyl.lumo.model.BrowseSection
import com.nagashreeshyl.lumo.model.Item
import com.nagashreeshyl.lumo.model.Ref
import com.nagashreeshyl.lumo.ui.Empty
import com.nagashreeshyl.lumo.ui.ItemCarousel
import com.nagashreeshyl.lumo.ui.Loading
import com.nagashreeshyl.lumo.ui.MoodTile
import com.nagashreeshyl.lumo.ui.SectionHeader
import com.nagashreeshyl.lumo.ui.open

/** New albums plus a grid of moods & genres. */
@Composable
fun ExploreScreen(nav: NavController) {
    var sections by remember { mutableStateOf<List<BrowseSection>?>(null) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { sections = Repo.explore() }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Explore", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        val s = sections
        when {
            s == null -> item { Loading() }
            s.isEmpty() -> item { Empty("Couldn't load Explore", "Check your connection and try again.") { sections = null; attempt++ } }
            else -> s.forEach { section ->
                item { SectionHeader(section.title) }
                val moods = section.items.mapNotNull { (it as? Item.PageItem)?.ref?.takeIf { r -> r.kind == Ref.Kind.category } }
                if (moods.size == section.items.size) items(moods.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { MoodTile(it, Modifier.weight(1f)) { nav.open(it) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                } else item { ItemCarousel(section) { nav.open(it) } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
