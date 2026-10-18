package com.atomkarnickel.aviationoptimizer.ui.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.FavoritesStore
import com.atomkarnickel.aviationoptimizer.data.LogbookStore
import com.atomkarnickel.aviationoptimizer.data.local.FlightLogEntryEntity
import com.atomkarnickel.aviationoptimizer.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class LogbookViewModel @Inject constructor(
    private val store: LogbookStore,
    private val favorites: FavoritesStore,
    val catalog: AircraftCatalog,
) : ViewModel() {
    val entries: StateFlow<List<FlightLogEntryEntity>> = store.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Gibt eine Fehlermeldung zurück oder null bei Erfolg. */
    fun add(entry: FlightLogEntryEntity): String? {
        if (runCatching { LocalDate.parse(entry.date) }.isFailure) return "Datum im Format JJJJ-MM-TT angeben."
        val iata = Regex("^[A-Z]{3}$")
        if (entry.origin.isNotEmpty() && !iata.matches(entry.origin)) return "Abflughafen als IATA-Code."
        if (entry.destination.isNotEmpty() && !iata.matches(entry.destination)) return "Ziel als IATA-Code."
        viewModelScope.launch {
            store.add(entry)
            // Geflogene Typen automatisch markieren, sofern nicht bereits anders markiert.
            if (entry.status == "flown") {
                val existing = favorites.aircraftPreferences.first()[entry.aircraftTypeId]
                if (existing == null || existing.status == "neutral") favorites.setAircraft(entry.aircraftTypeId, "flown", existing?.personalRating)
            }
        }
        return null
    }

    fun delete(id: Long) = viewModelScope.launch { store.delete(id) }
}

@Composable
fun LogbookScreen(vm: LogbookViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var flight by rememberSaveable { mutableStateOf("") }
    var airline by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(vm.catalog.types.firstOrNull()?.id ?: "A380") }
    var status by rememberSaveable { mutableStateOf("flown") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var typeMenu by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Flugzeuglogbuch", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            val flown = entries.filter { it.status == "flown" }
            Text("${flown.size} Flüge · ${flown.map { it.aircraftTypeId }.toSet().size} Flugzeugtypen", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Eintrag hinzufügen", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = status == "flown", onClick = { status = "flown" }, label = { Text("Geflogen") })
                        FilterChip(selected = status == "planned", onClick = { status = "planned" }, label = { Text("Geplant") })
                    }
                    OutlinedTextField(date, { date = it }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { typeMenu = true }) { Text("Flugzeug: ${vm.catalog.name(type)} ▾") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        vm.catalog.types.forEach { t -> DropdownMenuItem(text = { Text(t.displayName) }, onClick = { type = t.id; typeMenu = false }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(airline, { airline = it.uppercase().take(2) }, label = { Text("Airline") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(flight, { flight = it.uppercase().take(8) }, label = { Text("Flugnr.") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(origin, { origin = it.uppercase().take(3) }, label = { Text("Von") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(destination, { destination = it.uppercase().take(3) }, label = { Text("Nach") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(notes, { notes = it.take(200) }, label = { Text("Notiz") }, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = {
                        error = vm.add(FlightLogEntryEntity(date = date, flightNumber = flight, airline = airline, origin = origin,
                            destination = destination, aircraftTypeId = type, status = status, notes = notes))
                        if (error == null) { flight = ""; origin = ""; destination = ""; notes = "" }
                    }) { Text("Speichern") }
                }
            }
        }
        if (entries.isEmpty()) item { Text("Noch keine Einträge.") }
        items(entries, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${Format.date(e.date)} · ${if (e.status == "flown") "Geflogen" else "Geplant"}", style = MaterialTheme.typography.titleMedium)
                        Text(listOf("${e.airline} ${e.flightNumber}".trim(), if (e.origin.isNotEmpty()) "${e.origin} → ${e.destination}" else "").filter { it.isNotEmpty() }.joinToString(" · "))
                        Text(vm.catalog.name(e.aircraftTypeId), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { vm.delete(e.id) }) { Text("Löschen") }
                }
            }
        }
    }
}
