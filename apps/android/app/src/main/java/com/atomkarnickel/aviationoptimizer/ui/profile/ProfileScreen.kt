package com.atomkarnickel.aviationoptimizer.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.ApiResult
import com.atomkarnickel.aviationoptimizer.data.AppSettings
import com.atomkarnickel.aviationoptimizer.data.FavoritesStore
import com.atomkarnickel.aviationoptimizer.data.SearchRepository
import com.atomkarnickel.aviationoptimizer.data.SettingsStore
import com.atomkarnickel.aviationoptimizer.data.isAllowedBackendUrl
import com.atomkarnickel.aviationoptimizer.data.local.AircraftPreferenceEntity
import com.atomkarnickel.aviationoptimizer.data.remote.WeightsDto
import com.atomkarnickel.aviationoptimizer.domain.SearchValidation
import com.atomkarnickel.aviationoptimizer.ui.common.Banner
import com.atomkarnickel.aviationoptimizer.ui.common.BannerKind
import com.atomkarnickel.aviationoptimizer.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

val MODE_WEIGHTS = mapOf(
    "best_value" to WeightsDto(5.0, 4.0, 3.0, 3.0, 3.0, 1.0, 2.0),
    "aircraft_hunter" to WeightsDto(2.0, 9.0, 2.0, 1.0, 2.0, 1.0, 1.0),
    "cheapest" to WeightsDto(10.0, 0.0, 1.0, 1.0, 1.0, 0.0, 1.0),
)

val AIRCRAFT_STATUS_OPTIONS = linkedMapOf(
    "neutral" to "Neutral", "wishlist" to "Unbedingt erleben", "flown" to "Schon geflogen", "not_interested" to "Uninteressant",
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val favorites: FavoritesStore,
    private val repository: SearchRepository,
    val catalog: AircraftCatalog,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val aircraft: StateFlow<Map<String, AircraftPreferenceEntity>> =
        favorites.aircraftPreferences.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _connection = MutableStateFlow<Pair<Boolean, String>?>(null)
    val connection = _connection.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsStore.update(transform) }

    fun setAircraft(id: String, status: String, rating: Double?) = viewModelScope.launch { favorites.setAircraft(id, status, rating) }

    fun testAndSave(url: String) {
        val value = url.trim().trimEnd('/')
        if (!isAllowedBackendUrl(value)) {
            _connection.value = false to "Nur HTTPS-Adressen sind erlaubt (Ausnahme: http://10.0.2.2 im Emulator)."
            return
        }
        viewModelScope.launch {
            val previous = settings.value?.backendUrl ?: ""
            settingsStore.update { it.copy(backendUrl = value) }
            when (val r = repository.meta()) {
                is ApiResult.Success -> _connection.value = true to
                    "Verbunden: Backend v${r.value.backendVersion}, Provider ${r.value.provider.name} (${r.value.provider.mode})."
                is ApiResult.Failure -> {
                    settingsStore.update { it.copy(backendUrl = previous) }
                    _connection.value = false to r.error.message
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: ProfileViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val aircraft by vm.aircraft.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val s = settings ?: return
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Profil", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verbindung zum Backend", style = MaterialTheme.typography.titleMedium)
                    var url by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
                    OutlinedTextField(url, { url = it }, label = { Text("Backend-URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.testAndSave(url) }) { Text("Verbindung testen & speichern") }
                    connection?.let { (ok, msg) -> Banner(if (ok) "Verbunden" else "Fehler", msg, if (ok) BannerKind.OK else BannerKind.ERROR) }
                    Text("Dieselbe API wie die Webversion. API-Schlüssel liegen nur im Backend, nie in der App.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Optimierung & Gewichtung", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Format.MODES.forEach { (k, l) ->
                            FilterChip(selected = s.mode == k && s.customWeights == null, onClick = { vm.update { it.copy(mode = k, customWeights = null) } }, label = { Text(l) })
                        }
                    }
                    val w = s.customWeights ?: MODE_WEIGHTS.getValue(s.mode)
                    val total = listOf(w.price, w.aircraft, w.travelTime, w.stops, w.stay, w.airline, w.comfort).sum().coerceAtLeast(1.0)
                    WeightSlider("Preis", w.price, total) { v -> vm.update { it.copy(customWeights = w.copy(price = v)) } }
                    WeightSlider("Flugzeugtyp / Coolness", w.aircraft, total) { v -> vm.update { it.copy(customWeights = w.copy(aircraft = v)) } }
                    WeightSlider("Reisezeit", w.travelTime, total) { v -> vm.update { it.copy(customWeights = w.copy(travelTime = v)) } }
                    WeightSlider("Umstiege", w.stops, total) { v -> vm.update { it.copy(customWeights = w.copy(stops = v)) } }
                    WeightSlider("Aufenthaltsdauer", w.stay, total) { v -> vm.update { it.copy(customWeights = w.copy(stay = v)) } }
                    WeightSlider("Airline-Präferenz", w.airline, total) { v -> vm.update { it.copy(customWeights = w.copy(airline = v)) } }
                    WeightSlider("Komfort & Umsteigequalität", w.comfort, total) { v -> vm.update { it.copy(customWeights = w.copy(comfort = v)) } }
                    if (s.customWeights != null) TextButton(onClick = { vm.update { it.copy(customWeights = null) } }) { Text("Auf Modus zurücksetzen") }
                    Text("Bereits geflogene Typen: ${(s.flownFactor * 100).toInt()} % der Coolness")
                    Slider(s.flownFactor.toFloat(), { v -> vm.update { it.copy(flownFactor = (v * 10).toInt() / 10.0) } }, valueRange = 0f..1f, steps = 9)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reisepräferenzen", style = MaterialTheme.typography.titleMedium)
                    CodesField("Heimatflughäfen", s.homeAirports, Regex("^[A-Z]{3}$")) { v -> vm.update { it.copy(homeAirports = v) } }
                    CodesField("Bevorzugte Airlines (IATA)", s.preferredAirlines, Regex("^[A-Z0-9]{2}$")) { v -> vm.update { it.copy(preferredAirlines = v) } }
                    CodesField("Ausgeschlossene Airlines (IATA)", s.excludedAirlines, Regex("^[A-Z0-9]{2}$")) { v -> vm.update { it.copy(excludedAirlines = v) } }
                }
            }
        }
        item {
            Text("Flugzeuge & persönlicher Coolness-Score", style = MaterialTheme.typography.titleMedium)
            Text("Quelle: ${vm.catalog.source} (Stand ${vm.catalog.updated})", style = MaterialTheme.typography.labelSmall)
        }
        items(vm.catalog.types, key = { it.id }) { t ->
            val pref = aircraft[t.id]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${t.typicalUse} · Standard ${t.defaultCoolness}/10" + if (t.status == "historical") " · historisch – nicht regulär buchbar" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        AIRCRAFT_STATUS_OPTIONS.forEach { (k, l) ->
                            FilterChip(selected = (pref?.status ?: "neutral") == k, onClick = { vm.setAircraft(t.id, k, pref?.personalRating) }, label = { Text(l, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Eigene Bewertung: ${pref?.personalRating?.toInt() ?: "Standard"}", Modifier.width(170.dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = (pref?.personalRating ?: t.defaultCoolness).toFloat(), valueRange = 0f..10f, steps = 9,
                            onValueChange = { v -> vm.setAircraft(t.id, pref?.status ?: "neutral", v.toInt().toDouble()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightSlider(label: String, value: Double, total: Double, onChange: (Double) -> Unit) {
    Text("$label: ${value.toInt()} (${(value / total * 100).toInt()} %)", style = MaterialTheme.typography.bodyMedium)
    Slider(value.toFloat(), { onChange(it.toInt().toDouble()) }, valueRange = 0f..10f, steps = 9)
}

@Composable
private fun CodesField(label: String, value: List<String>, pattern: Regex, onSave: (List<String>) -> Unit) {
    var text by remember(value) { mutableStateOf(value.joinToString(", ")) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value) { error = null }
    OutlinedTextField(
        text, { text = it }, label = { Text(label) }, singleLine = true, isError = error != null,
        supportingText = { Text(error ?: "Kommagetrennt") }, modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = {
                val codes = SearchValidation.parseCodes(text)
                if (codes.all { pattern.matches(it) }) onSave(codes) else error = "Ungültiger Code"
            }) { Text("OK") }
        },
    )
}
