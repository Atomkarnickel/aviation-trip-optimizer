package com.atomkarnickel.overhead.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.atomkarnickel.overhead.BuildConfig
import com.atomkarnickel.overhead.container
import com.atomkarnickel.overhead.work.RefreshScheduler
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Vom Widget übergebenes Flugzeug (Tipp auf eine Zeile) – öffnet direkt die Details. */
    private val pendingHex = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingHex.value = intent?.getStringExtra(EXTRA_HEX)
        val c = container
        setContent {
            OverheadTheme {
                val home: HomeViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        HomeViewModel(
                            c.settings, c.refresher, c.snapshots, c.deviceLocation, c.reference,
                            loadPhoto = c.photos::photo, loadImage = c.photos::image,
                            onSettingsChanged = {
                                RefreshScheduler.syncWithWidgets(applicationContext)
                                c.updateWidgetsNow()
                            },
                        )
                    }
                })
                val about: AboutViewModel = viewModel(factory = viewModelFactory {
                    initializer { AboutViewModel(c.releases, BuildConfig.VERSION_NAME, BuildConfig.GITHUB_REPOSITORY) }
                })
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        vm = home, pendingHex = pendingHex, attributions = c.attributions,
                        onOpenSettings = { screen = Screen.SETTINGS }, onOpenAbout = { screen = Screen.ABOUT },
                    )
                    Screen.SETTINGS -> SettingsScreen(home, onBack = { screen = Screen.HOME })
                    Screen.ABOUT -> AboutScreen(about, attributions = c.attributions, onBack = { screen = Screen.HOME })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_HEX)?.let { pendingHex.value = it }
    }

    enum class Screen { HOME, SETTINGS, ABOUT }

    companion object {
        const val EXTRA_HEX = "hex"
    }
}
