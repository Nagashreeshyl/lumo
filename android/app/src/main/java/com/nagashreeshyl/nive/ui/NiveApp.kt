package com.nagashreeshyl.nive.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nagashreeshyl.nive.model.Ref
import com.nagashreeshyl.nive.ui.explore.ExploreScreen
import com.nagashreeshyl.nive.ui.home.HomeScreen
import com.nagashreeshyl.nive.ui.pages.PageScreen
import com.nagashreeshyl.nive.ui.search.SearchScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home), Tab("explore", "Explore", Icons.Rounded.Explore),
    Tab("library", "Library", Icons.Rounded.LibraryMusic), Tab("search", "Search", Icons.Rounded.Search),
)

/** Opens an album, artist, playlist or mood page. */
fun NavController.open(ref: Ref) =
    navigate("page/${ref.kind.name}/${Uri.encode(ref.id)}?params=${Uri.encode(ref.params ?: "")}&title=${Uri.encode(ref.title)}")

/** Extra screens other tasks add (library, settings…). */
var extraRoutes: NavGraphBuilder.(NavController) -> Unit = {}

@Composable
fun NiveApp(miniPlayer: @Composable () -> Unit = {}, overlay: @Composable () -> Unit = {}) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val app = app()
    val pending by app.pendingRoute.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { nav.navigate(it) { launchSingleTop = true }; app.pendingRoute.value = null }
    }
    Scaffold(bottomBar = {
        Column {
            miniPlayer()
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = entry?.destination?.route == tab.route
                    NavigationBarItem(selected, {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }, icon = { Icon(tab.icon, null) }, label = { Text(tab.label) })
                }
            }
        }
    }) { padding ->
        NavHost(nav, "home", Modifier.padding(padding)) {
            composable("home") { HomeScreen(nav) }
            composable("explore") { ExploreScreen(nav) }
            composable("search") { SearchScreen(nav) }
            composable("page/{kind}/{id}?params={params}&title={title}") { e ->
                val a = e.arguments!!
                PageScreen(nav, Ref(Ref.Kind.valueOf(a.getString("kind")!!), a.getString("id")!!, a.getString("title") ?: "", "",
                    params = a.getString("params")?.ifEmpty { null }))
            }
            extraRoutes(nav)
        }
    }
    overlay()
}
