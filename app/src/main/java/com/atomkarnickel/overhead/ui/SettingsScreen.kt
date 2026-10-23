package com.atomkarnickel.overhead.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomkarnickel.overhead.data.LocationMode
import com.atomkarnickel.overhead.data.Settings
import com.atomkarnickel.overhead.domain.AltitudeUnit
import com.atomkarnickel.overhead.domain.Format
import com.atomkarnickel.overhead.widget.OverheadWidgetReceiver
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current
    // Zähler erzwingt Neuberechnung des Berechtigungsstatus nach einer Rückkehr aus dem Systemdialog.
    val permissionTick = remember { mutableIntStateOf(0) }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick.intValue++; vm.refresh() }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick.intValue++ }
    val hasPermission = permissionTick.intValue.let { vm.hasLocationPermission() }
    val hasBackground = permissionTick.intValue.let { vm.hasBackgroundLocationPermission() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Einstellungen") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard("Standort") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.locationMode == LocationMode.DEVICE, onClick = vm::useDeviceLocation, label = { Text("Gerätestandort") })
                    FilterChip(
                        s.locationMode == LocationMode.FIXED, enabled = s.fixedLocation != null,
                        onClick = { s.fixedLocation?.let(vm::setFixedLocation) }, label = { Text("Fester Standort") },
                    )
                }
                Text(
                    s.fixedLocation?.let { "Fester Standort: ${String.format(Locale.GERMANY, "%.3f, %.3f", it.lat, it.lon)} (lange auf die Karte drücken zum Ändern)" }
                        ?: "Einen festen Standort legst du per langem Druck auf die Karte fest.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.lastDeviceLocation?.let { last ->
                    OutlinedButton(onClick = { vm.setFixedLocation(last.position) }) { Text("Letzten Gerätestandort als festen übernehmen") }
                }
                if (!hasPermission) {
                    Button(onClick = { foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                        Text("Standortzugriff erlauben")
                    }
                }
                Text(
                    when {
                        s.locationMode == LocationMode.FIXED -> "Widget nutzt den festen Standort."
                        hasBackground -> "Widget nutzt auch im Hintergrund den aktuellen Standort."
                        else -> "Widget nutzt den zuletzt in der App ermittelten Standort. Für den aktuellen Standort im Hintergrund „Immer erlauben“ wählen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hasPermission && !hasBackground && s.locationMode == LocationMode.DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    OutlinedButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Standort im Hintergrund erlauben") }
                }
            }

            InfoCard("Umkreis") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.RADIUS_OPTIONS.forEach { km ->
                        FilterChip(s.radiusKm == km, onClick = { vm.updateSettings { it.copy(radiusKm = km) } }, label = { Text("$km km") })
                    }
                }
            }

            InfoCard("Anzeige") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Flugzeuge am Boden ausblenden", Modifier.weight(1f))
                    Switch(s.hideGround, onCheckedChange = { v -> vm.updateSettings { it.copy(hideGround = v) } })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.altitudeUnit == AltitudeUnit.METERS, onClick = { vm.updateSettings { it.copy(altitudeUnit = AltitudeUnit.METERS) } }, label = { Text("Höhe in Metern") })
                    FilterChip(s.altitudeUnit == AltitudeUnit.FEET, onClick = { vm.updateSettings { it.copy(altitudeUnit = AltitudeUnit.FEET) } }, label = { Text("Höhe in Fuß") })
                }
            }

            InfoCard("Widget") {
                Text("Automatisch aktualisieren", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.WIDGET_INTERVAL_OPTIONS.forEach { min ->
                        FilterChip(
                            s.widgetIntervalMinutes == min, onClick = { vm.updateSettings { it.copy(widgetIntervalMinutes = min) } },
                            label = { Text(if (min == 0) "nur manuell" else "alle $min min") },
                        )
                    }
                }
                Text(
                    "Android erlaubt Hintergrund-Aktualisierungen höchstens alle 15 Minuten. Für den Live-Stand tippe im Widget auf ⟳ oder öffne die App (alle 10 s).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val manager = context.getSystemService(AppWidgetManager::class.java)
                if (manager?.isRequestPinAppWidgetSupported == true) {
                    Button(onClick = { manager.requestPinAppWidget(ComponentName(context, OverheadWidgetReceiver::class.java), null, null) }) {
                        Text("Widget zum Startbildschirm hinzufügen")
                    }
                } else {
                    Text("Widget hinzufügen: lange auf den Startbildschirm drücken → Widgets → Overhead.", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.snapshot?.let { snap ->
                Text(
                    "Letzter Abruf: ${Format.time(snap.fetchedAtMillis)} · ${snap.aircraft.size} Flugzeuge · Quelle ${snap.source}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
