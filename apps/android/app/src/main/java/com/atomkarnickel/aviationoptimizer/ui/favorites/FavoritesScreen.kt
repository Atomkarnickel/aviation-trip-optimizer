package com.atomkarnickel.aviationoptimizer.ui.favorites

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.FavoriteTrip
import com.atomkarnickel.aviationoptimizer.data.FavoritesStore
import com.atomkarnickel.aviationoptimizer.data.local.AircraftPreferenceEntity
import com.atomkarnickel.aviationoptimizer.ui.common.Format
import com.atomkarnickel.aviationoptimizer.ui.common.Tag
import com.atomkarnickel.aviationoptimizer.ui.profile.AIRCRAFT_STATUS_OPTIONS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val store: FavoritesStore, val catalog: AircraftCatalog) : ViewModel() {
    val trips: StateFlow<List<FavoriteTrip>?> = store.trips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val aircraft: StateFlow<Map<String, AircraftPreferenceEntity>> =
        store.aircraftPreferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun remove(id: String) = viewModelScope.launch { store.removeTrip(id) }
}

@Composable
fun FavoritesScreen(vm: FavoritesViewModel = hiltViewModel()) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val aircraft by vm.aircraft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Favoriten", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Gespeicherte Reisen und markierte Flugzeuge – lokal auf dem Gerät.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text("Gemerkte Reisen", style = MaterialTheme.typography.titleMedium) }
        if (trips?.isEmpty() == true) item { Text("Noch keine Reisen gemerkt. Nutze „☆ Merken“ in den Suchergebnissen.") }
        items(trips.orEmpty(), key = { it.itinerary.id }) { f ->
            val it = f.itinerary
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${it.origin} ⇄ ${it.destination} · ${Format.date(it.outboundDate)} – ${Format.date(it.returnDate)}", style = MaterialTheme.typography.titleMedium)
                    Text(Format.money(it.price.total, it.price.currency))
                    Text(f.summary, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (it.isDemo) Tag("DEMO")
                        it.aircraftTypeIds.take(3).forEach { id -> Tag(vm.catalog.name(id)) }
                    }
                    Text("Preisstand ${it.fetchedAt.take(10)} (${Format.PRICE_SOURCE[it.priceSource]}). Preise können sich inzwischen geändert haben.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        it.bookingOptions.firstOrNull()?.let { b ->
                            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.url))) }) { Text("Airline-Website ↗") }
                        }
                        OutlinedButton(onClick = { vm.remove(it.id) }) { Text("Entfernen") }
                    }
                }
            }
        }
        item { Text("Markierte Flugzeuge", style = MaterialTheme.typography.titleMedium) }
        val marked = aircraft.values.filter { it.status != "neutral" }
        if (marked.isEmpty()) item { Text("Noch keine Flugzeuge markiert – im Profil möglich.") }
        items(marked, key = { "ac-" + it.aircraftTypeId }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(vm.catalog.name(p.aircraftTypeId), style = MaterialTheme.typography.titleMedium)
                    Text("${AIRCRAFT_STATUS_OPTIONS[p.status] ?: p.status}${p.personalRating?.let { r -> " · eigene Bewertung ${r.toInt()}/10" } ?: ""}")
                }
            }
        }
    }
}
