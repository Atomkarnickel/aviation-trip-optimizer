package com.atomkarnickel.aviationoptimizer.ui.about

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomkarnickel.aviationoptimizer.domain.ReleaseCheck
import com.atomkarnickel.aviationoptimizer.domain.UpdateState
import com.atomkarnickel.aviationoptimizer.ui.common.Banner
import com.atomkarnickel.aviationoptimizer.ui.common.BannerKind
import com.atomkarnickel.aviationoptimizer.ui.common.Format
import com.atomkarnickel.aviationoptimizer.ui.common.KeyValue

@Composable
fun AboutScreen(vm: AboutViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val info = vm.appInfo
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Über diese App", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                KeyValue("App", info.appName)
                KeyValue("Installierte Version", "${state.installedVersion} (Build ${info.versionCode})")
                val latest = (state.check as? ReleaseCheck.Ok)?.latest
                KeyValue("Neueste stabile Version", latest?.version ?: "–")
                KeyValue("Veröffentlicht", latest?.publishedAt?.let(Format::date) ?: "–")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (state.updateState) {
            UpdateState.CURRENT -> Banner("Aktuell", "Du nutzt die neueste stabile Version.", BannerKind.OK)
            UpdateState.UPDATE_AVAILABLE -> Banner("Update verfügbar", "Eine neuere stabile Version wurde veröffentlicht.", BannerKind.WARN)
            UpdateState.NEWER_THAN_RELEASE -> Banner("Entwicklungsversion", "Installierte Version ist neuer als das letzte stabile Release.", BannerKind.INFO)
            UpdateState.UNKNOWN -> if (!state.loading) {
                val msg = when (val c = state.check) {
                    is ReleaseCheck.NoRelease -> c.message
                    is ReleaseCheck.Unavailable -> c.message
                    else -> "Die aktuelle Version konnte nicht zuverlässig ermittelt werden."
                }
                Banner("Versionsprüfung nicht möglich", msg, BannerKind.INFO)
            }
        }
        (state.check as? ReleaseCheck.Ok)?.let { ok ->
            if (ok.stale) Text("GitHub derzeit nicht erreichbar – zwischengespeicherter Stand.", style = MaterialTheme.typography.labelSmall)
            val latest = ok.latest
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Neueste APK", style = MaterialTheme.typography.titleMedium)
                    KeyValue("Datei", latest.apkName)
                    KeyValue("Größe", latest.apkSize?.let { "%.1f MB".format(it / 1024.0 / 1024.0) } ?: "–")
                    KeyValue("Änderungen", latest.summary.ifBlank { "–" })
                    Text("SHA-256", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Text(state.sha256 ?: if (latest.checksumUrl != null) "wird geladen …" else "nicht verfügbar", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { vm.downloadUrl()?.let(::open) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Neueste APK herunterladen (v${latest.version})")
                    }
                    Text(
                        "Android fragt ggf. nach der Erlaubnis „Unbekannte Apps installieren“ für Browser oder Dateimanager. " +
                            "Die Installation bestätigst du selbst; Updates funktionieren nur mit derselben Signatur.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { open(latest.htmlUrl) }) { Text("Release Notes") }
                }
            }
        }
        state.downloadNotice?.let { Banner("Download", it, BannerKind.INFO) }
        OutlinedButton(onClick = { vm.refresh(true) }, enabled = !state.loading) { Text("Erneut prüfen") }
        OutlinedButton(onClick = { open("https://github.com/${info.repository}/releases") }) { Text("Alle Releases") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Projekt & Lizenz", style = MaterialTheme.typography.titleMedium)
                KeyValue("Quellcode", "github.com/${info.repository}")
                KeyValue("Lizenz", "MIT")
                KeyValue("Datenschutz", "Kein Konto, kein Tracking. Favoriten, Logbuch und Einstellungen liegen lokal auf dem Gerät.")
                KeyValue("Flugzeugtypen", "Laut Flugplan geplant, nicht garantiert.")
            }
        }
    }
}
