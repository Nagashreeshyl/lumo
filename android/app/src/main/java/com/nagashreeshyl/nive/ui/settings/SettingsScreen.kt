package com.nagashreeshyl.nive.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nagashreeshyl.nive.ui.app

fun NavGraphBuilder.settingsRoutes(nav: NavController) {
    composable("settings") { SettingsScreen(nav) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val app = app()
    val automix by app.player.automixFlow.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar({ Text("Settings") }, windowInsets = WindowInsets(0),
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        LazyColumn {
            item {
                ListItem({ Text("AutoMix") }, supportingContent = { Text("Blend songs like a DJ: beat-matched transitions.") },
                    trailingContent = { Switch(automix, { app.player.automix = it; app.settings.setAutomix(it) }) })
            }
        }
    }
}
