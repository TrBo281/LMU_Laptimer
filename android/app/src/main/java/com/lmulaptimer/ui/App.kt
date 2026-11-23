package com.lmulaptimer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.Repo

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Filled.Speed),
    FLAGS("Flaggen", Icons.Filled.Flag),
    LAPS("Zeiten", Icons.Filled.Timer),
    STRATEGY("Strategie", Icons.Filled.Calculate),
    ANALYSIS("Analyse", Icons.Filled.Insights),
    SETTINGS("Bridge", Icons.Filled.Settings),
}

@Composable
fun App(repo: Repo) {
    var tab by remember { mutableStateOf(Tab.LIVE) }
    var syncTick by remember { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(repo.nightMode) }

    LmuTheme(night = night) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Bg,
                            selectedTextColor = Lime,
                            indicatorColor = Lime,
                            unselectedIconColor = TextDim,
                            unselectedTextColor = TextDim,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.LIVE -> LiveScreen(repo, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.FLAGS -> FlagsScreen(repo, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.LAPS -> LapsScreen(repo, syncTick)
                Tab.STRATEGY -> StrategyScreen(repo)
                Tab.ANALYSIS -> AnalysisScreen(repo, syncTick)
                Tab.SETTINGS -> SettingsScreen(
                    repo = repo,
                    night = night,
                    onNightChanged = {
                        night = it
                        repo.nightMode = it
                    },
                    onSynced = { syncTick += 1 },
                )
            }
        }
    }
    }
}
