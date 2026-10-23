package com.atomkarnickel.overhead.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.atomkarnickel.overhead.data.LocationMode
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.AircraftKind
import com.atomkarnickel.overhead.domain.AltitudeUnit
import com.atomkarnickel.overhead.domain.Format
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.LocationSource
import com.atomkarnickel.overhead.domain.ReferenceData
import com.atomkarnickel.overhead.domain.Snapshot
import com.atomkarnickel.overhead.domain.WidgetModelBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    pendingHex: MutableStateFlow<String?>,
    attributions: List<String>,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    // Live-Aktualisierung nur, solange die App sichtbar ist.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.liveLoop() } }
    val hex by pendingHex.collectAsStateWithLifecycle()
    LaunchedEffect(hex) { hex?.let { vm.select(it); pendingHex.value = null } }

    val requestLocation = {
        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val pickOnMap = {
        scope.launch {
            pager.animateScrollToPage(1)
            snackbar.showSnackbar("Lange auf die Karte drücken, um deinen Standort festzulegen.")
        }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Über dir", fontWeight = FontWeight.Bold)
                        Text(locationLine(state.snapshot, state.settings.locationMode), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren") }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Einstellungen") }
                    IconButton(onClick = onOpenAbout) { Icon(Icons.Default.Info, contentDescription = "Über die App") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = pager.currentPage) {
                Tab(pager.currentPage == 0, onClick = { scope.launch { pager.animateScrollToPage(0) } }, text = { Text("Liste") })
                Tab(pager.currentPage == 1, onClick = { scope.launch { pager.animateScrollToPage(1) } }, text = { Text("Karte") })
            }
            // Auf der Kartenseite gehören Wischgesten der Karte – zurück zur Liste über den Tab.
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = pager.currentPage == 0) { page ->
                when (page) {
                    0 -> AircraftList(state, vm.reference, attributions, requestLocation, pickOnMap, vm::select)
                    else -> AircraftMap(
                        snapshot = state.snapshot,
                        selectedHex = state.selectedHex,
                        onSelect = vm::select,
                        onLongPress = { pos ->
                            vm.setFixedLocation(pos)
                            scope.launch { snackbar.showSnackbar("Fester Standort gesetzt. Zurück zum Gerätestandort in den Einstellungen.") }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    state.selected?.let { a ->
        AircraftDetailSheet(
            aircraft = a, snapshot = state.snapshot!!, reference = vm.reference, unit = state.settings.altitudeUnit, photo = state.photo,
            onShowOnMap = { vm.select(a.hex); scope.launch { pager.animateScrollToPage(1) } },
            onDismiss = { vm.select(null) },
        )
    }
}

private fun locationLine(snapshot: Snapshot?, mode: LocationMode): String {
    val o = snapshot?.observer ?: return if (mode == LocationMode.FIXED) "Fester Standort" else "Standort wird ermittelt …"
    val source = when (o.source) {
        LocationSource.DEVICE -> "Aktueller Standort"
        LocationSource.FIXED -> "Fester Standort"
        LocationSource.SAVED -> "Letzter Standort (${Format.age(o.locationTimeMillis, System.currentTimeMillis())})"
    }
    return "$source · ${snapshot.radiusKm} km"
}

@Composable
private fun AircraftList(
    state: HomeUiState,
    reference: ReferenceData,
    attributions: List<String>,
    onRequestLocation: () -> Unit,
    onPickOnMap: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val snapshot = state.snapshot
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.needsLocation || (snapshot == null && !state.refreshing && state.lastError == null)) {
            item {
                InfoCard("Wo bist du?") {
                    Text("Overhead zeigt die Flugzeuge in deinem Umkreis. Erlaube den Standort oder lege einen festen Ort auf der Karte fest.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestLocation) { Text("Standort verwenden") }
                        OutlinedButton(onClick = onPickOnMap) { Text("Auf Karte wählen") }
                    }
                }
            }
        }
        state.lastError?.let { err ->
            item {
                InfoCard("Keine aktuellen Daten", containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                    if (snapshot != null) Text("Angezeigt wird der Stand von ${Format.time(snapshot.fetchedAtMillis)}.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (snapshot != null) {
            val airborne = snapshot.aircraft.count { !it.raw.onGround }
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        when (airborne) {
                            0 -> "Gerade kein Flugzeug in der Luft"
                            1 -> "1 Flugzeug im Umkreis von ${snapshot.radiusKm} km"
                            else -> "$airborne Flugzeuge im Umkreis von ${snapshot.radiusKm} km"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Stand ${Format.time(snapshot.fetchedAtMillis)} · Quelle ${snapshot.source} · nächstes zuerst",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(snapshot.aircraft, key = { it.hex }) { a ->
                AircraftRow(a, snapshot, reference, state.settings.altitudeUnit) { onSelect(a.hex) }
            }
            item {
                Text(
                    (attributions + "Routen: hexdb.io (laut Datenbank, kann abweichen)").joinToString(" · "),
                    Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AircraftRow(a: Aircraft, snapshot: Snapshot, reference: ReferenceData, unit: AltitudeUnit, onClick: () -> Unit) {
    val r = a.raw
    val kind = AircraftKind.of(r.category)
    val type = reference.typeName(r.typeCode, r.typeDescription) ?: kind.label
    val route = snapshot.routeFor(a)?.takeIf { it.plausible != false }
    val emergency = r.emergency != null || r.squawk in WidgetModelBuilder.EMERGENCY_SQUAWKS
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (emergency) CardDefaults.cardColors(containerColor = AlertRed.copy(alpha = 0.18f)) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Geo.arrow(a.bearingDeg), fontSize = 24.sp, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.semantics { contentDescription = "Richtung ${Geo.compass(a.bearingDeg)}" })
                Text(Geo.compass(a.bearingDeg), style = MaterialTheme.typography.labelMedium)
                a.elevationDeg?.takeIf { it > 0 }?.let { Text("${it.toInt()}°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f)) {
                Text("${kind.symbol} $type", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(r.callsign, reference.airlineName(r.callsign), r.registration).distinct().joinToString(" · ").ifEmpty { r.hex.uppercase() },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                route?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                if (emergency) Text("Notfall-Code ${r.squawk ?: r.emergency}", color = AlertRed, style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.distance(a.distanceKm), style = MaterialTheme.typography.titleMedium)
                Text("${Format.altitude(r.altitudeFt, r.onGround, unit)} ${Format.climbArrow(r.verticalRateFpm)}".trim(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.speed(r.groundSpeedKt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
