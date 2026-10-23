package com.atomkarnickel.overhead.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.overhead.data.remote.ReleaseRepository
import com.atomkarnickel.overhead.domain.ReleaseCheck
import com.atomkarnickel.overhead.domain.ReleaseRules
import com.atomkarnickel.overhead.domain.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AboutUiState(
    val installedVersion: String,
    val loading: Boolean = false,
    val check: ReleaseCheck? = null,
    val sha256: String? = null,
) {
    val updateState: UpdateState get() = ReleaseRules.updateState(installedVersion, check)
}

class AboutViewModel(private val releases: ReleaseRepository, version: String, val repository: String) : ViewModel() {
    private val _state = MutableStateFlow(AboutUiState(installedVersion = version))
    val state: StateFlow<AboutUiState> = _state

    fun refresh(force: Boolean = false) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val check = releases.check(force)
            val sha = (check as? ReleaseCheck.Ok)?.latest?.checksumUrl?.let { releases.checksum(it) }
            _state.update { it.copy(loading = false, check = check, sha256 = sha) }
        }
    }

    fun safeDownloadUrl(): String? =
        (state.value.check as? ReleaseCheck.Ok)?.latest?.apkUrl?.takeIf { ReleaseRules.isSafeDownloadUrl(it, repository) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(vm: AboutViewModel, attributions: List<String>, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Über Overhead") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard("Version") {
                KeyValue("Installiert", state.installedVersion)
                val latest = (state.check as? ReleaseCheck.Ok)?.latest
                KeyValue("Neueste stabile", latest?.version ?: "–")
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    when (state.updateState) {
                        UpdateState.CURRENT -> "✓ Du nutzt die neueste Version."
                        UpdateState.UPDATE_AVAILABLE -> "Update verfügbar: ${latest?.summary.orEmpty()}"
                        UpdateState.NEWER_THAN_RELEASE -> "Entwicklungsversion (neuer als das letzte Release)."
                        UpdateState.UNKNOWN -> when (val c = state.check) {
                            is ReleaseCheck.NoRelease -> c.message
                            is ReleaseCheck.Unavailable -> c.message
                            else -> if (state.loading) "Prüfe …" else "Versionsprüfung nicht möglich."
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (latest != null) {
                    Text("SHA-256 der APK", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(state.sha256 ?: "–", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    Button(onClick = { vm.safeDownloadUrl()?.let(open) }) { Text("APK v${latest.version} herunterladen") }
                    Text(
                        "Android fragt ggf. nach „Unbekannte Apps installieren“. Updates installieren sich über die alte Version, weil jede APK mit demselben Schlüssel signiert ist.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { vm.refresh(force = true) }, enabled = !state.loading) { Text("Erneut prüfen") }
                OutlinedButton(onClick = { open("https://github.com/${vm.repository}/releases") }) { Text("Alle Releases") }
            }

            InfoCard("Datenquellen – alle kostenlos") {
                attributions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text("• Routen & Flughäfen: hexdb.io (Datenbank, kann veraltet sein – wird gegen die Position geprüft)", style = MaterialTheme.typography.bodyMedium)
                Text("• Fotos: planespotters.net (mit Fotografennennung)", style = MaterialTheme.typography.bodyMedium)
                Text("• Karte: OpenFreeMap, © OpenMapTiles, Daten © OpenStreetMap-Mitwirkende", style = MaterialTheme.typography.bodyMedium)
                Text("ADS-B-Daten stammen von Freiwilligen-Empfängern. Nicht jedes Flugzeug ist sichtbar, Militär und Privatflüge fehlen teilweise.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            InfoCard("Datenschutz") {
                Text(
                    "Kein Konto, kein Tracking. Dein Standort bleibt auf dem Gerät; an die Flugdatendienste geht nur ein auf ca. 1 km gerundeter Punkt. " +
                        "Für Fotos und Routen werden Transponder-Kennung bzw. Rufzeichen abgefragt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            InfoCard("Projekt") {
                KeyValue("Quellcode", "github.com/${vm.repository}")
                KeyValue("Lizenz", "MIT")
                OutlinedButton(onClick = { open("https://github.com/${vm.repository}") }) { Text("Auf GitHub öffnen") }
            }
        }
    }
}
