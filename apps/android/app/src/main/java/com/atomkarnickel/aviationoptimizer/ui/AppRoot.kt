package com.atomkarnickel.aviationoptimizer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.atomkarnickel.aviationoptimizer.ui.about.AboutScreen
import com.atomkarnickel.aviationoptimizer.ui.discover.DiscoverScreen
import com.atomkarnickel.aviationoptimizer.ui.favorites.FavoritesScreen
import com.atomkarnickel.aviationoptimizer.ui.logbook.LogbookScreen
import com.atomkarnickel.aviationoptimizer.ui.profile.ProfileScreen

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    DISCOVER("discover", "Entdecken", Icons.Filled.Search),
    FAVORITES("favorites", "Favoriten", Icons.Filled.Favorite),
    LOGBOOK("logbook", "Logbuch", Icons.Filled.List),
    PROFILE("profile", "Profil", Icons.Filled.Person),
    ABOUT("about", "About", Icons.Filled.Info),
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.DISCOVER.route, modifier = Modifier.padding(padding)) {
            composable(Tab.DISCOVER.route) { DiscoverScreen() }
            composable(Tab.FAVORITES.route) { FavoritesScreen() }
            composable(Tab.LOGBOOK.route) { LogbookScreen() }
            composable(Tab.PROFILE.route) { ProfileScreen() }
            composable(Tab.ABOUT.route) { AboutScreen() }
        }
    }
}
