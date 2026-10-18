package com.atomkarnickel.aviationoptimizer.ui.discover

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.remote.CompareResponseDto
import com.atomkarnickel.aviationoptimizer.data.remote.LegDto
import com.atomkarnickel.aviationoptimizer.data.remote.ScoredItineraryDto
import com.atomkarnickel.aviationoptimizer.domain.SearchField
import com.atomkarnickel.aviationoptimizer.domain.SearchForm
import com.atomkarnickel.aviationoptimizer.ui.common.Banner
import com.atomkarnickel.aviationoptimizer.ui.common.BannerKind
import com.atomkarnickel.aviationoptimizer.ui.common.Format
import com.atomkarnickel.aviationoptimizer.ui.common.KeyValue
import com.atomkarnickel.aviationoptimizer.ui.common.Tag
import com.atomkarnickel.aviationoptimizer.ui.theme.Demo

@Composable
fun DiscoverScreen(vm: DiscoverViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val response = (state.search as? SearchState.Success)?.response
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Entdecken", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Preis, Reisezeit, Aufenthalt und Flugzeugerlebnis optimal kombinieren.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ModeSelector(state.mode.takeUnless { state.customWeights }, vm::changeMode) }
        item { BackendBanner(state.backend, vm::checkBackend) }
        item {
            SearchFormCard(
                form = state.form, errors = state.fieldErrors, catalog = vm.catalog,
                loading = state.search is SearchState.Loading,
                enabled = state.backend is BackendState.Connected,
                onChange = vm::updateForm, onSearch = vm::search,
            )
        }
        state.message?.let { msg -> item { Banner("Hinweis", msg, BannerKind.WARN) } }
        when (val s = state.search) {
            is SearchState.Failed -> item { Banner("Suche fehlgeschlagen", s.message, BannerKind.ERROR) }
            SearchState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else -> Unit
        }
        if (response != null) {
            item {
                Text("${response.results.size} Reisevarianten · ${Format.MODES[response.mode]}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${response.stats.combinationsSearched} von ${response.stats.combinationsTotal} Kombinationen geprüft · " +
                        "${response.stats.offersReceived} Angebote" + (if (response.cached) " · zwischengespeichert" else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            statusBanner(response.status)?.let { (title, kind) -> item { Banner(title, "", kind) } }
            if (response.warnings.isNotEmpty()) {
                item { Banner("Hinweise zur Suche", response.warnings.joinToString("\n• ", prefix = "• "), BannerKind.INFO) }
            }
            if (response.calendar.isNotEmpty()) {
                item {
                    PriceCalendar(response.calendar, state.filter.departureDate, state.filter.nights) { d, n ->
                        vm.setFilter(state.filter.copy(departureDate = d, nights = n))
                    }
                }
            }
            item { SortAndFilter(state, vm) }
            state.compare?.let { cmp -> item { CompareCard(cmp, response.results, vm::closeCompare) } }
            items(visibleResults(response.results, state.filter, state.sort), key = { it.itinerary.id }) { item ->
                ResultCard(
                    item = item, catalog = vm.catalog,
                    history = state.history,
                    onLoadHistory = vm::loadHistory,
                    favorite = item.itinerary.id in state.favoriteIds,
                    inCompare = item.itinerary.id in state.compareIds,
                    onFavorite = { vm.toggleFavorite(item) },
                    onCompare = { vm.toggleCompare(item.itinerary.id) },
                )
            }
        }
    }
}

private fun statusBanner(status: String): Pair<String, BannerKind>? = when (status) {
    "no_results" -> "Keine passenden Reisen gefunden – Zeitraum, Budget oder Filter lockern." to BannerKind.INFO
    "provider_not_configured" -> "Keine Flugdatenquelle konfiguriert – es werden bewusst keine Ergebnisse angezeigt." to BannerKind.WARN
    "provider_error" -> "Der Flugdaten-Provider konnte nicht abgefragt werden." to BannerKind.ERROR
    "partial" -> "Einige Abfragen sind fehlgeschlagen – Ergebnisse unvollständig." to BannerKind.WARN
    else -> null
}

@Composable
private fun ModeSelector(selected: String?, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Format.MODES.entries.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = selected == key, onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(i, Format.MODES.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun BackendBanner(state: BackendState, onRetry: () -> Unit) {
    when (state) {
        BackendState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Verbindung zum Backend wird geprüft … Das kostenlose Hosting kann beim ersten Aufruf bis zu einer Minute zum Aufwachen brauchen.")
        }
        is BackendState.Unavailable -> Column {
            Banner("Backend nicht verbunden", state.message + " Ohne Backend ist keine Flugsuche möglich.", BannerKind.ERROR)
            TextButton(onClick = onRetry) { Text("Erneut versuchen") }
        }
        is BackendState.Connected -> {
            val p = state.provider
            when {
                !p.configured -> Banner("Keine Live-Flugdaten konfiguriert", p.message, BannerKind.WARN)
                p.mode == "estimated" -> Banner("Kostenlose Preisdaten (geschätzt)", p.message, BannerKind.INFO)
                p.mode == "demo" -> Banner("DEMO-MODUS", p.message, BannerKind.DEMO)
                p.mode == "test" -> Banner("TESTMODUS", p.message, BannerKind.DEMO)
                else -> Banner("Live-Daten aktiv (${p.name})", p.message, BannerKind.OK)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchFormCard(
    form: SearchForm,
    errors: Map<SearchField, String>,
    catalog: AircraftCatalog,
    loading: Boolean,
    enabled: Boolean,
    onChange: ((SearchForm) -> SearchForm) -> Unit,
    onSearch: () -> Unit,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Abflughäfen (IATA)", form.origins, errors[SearchField.ORIGINS], "z. B. ZRH, BSL") { v -> onChange { it.copy(origins = v) } }
            Field("Ziele (leer = offene Suche)", form.destinations, errors[SearchField.DESTINATIONS], "z. B. SIN, JFK") { v -> onChange { it.copy(destinations = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Frühester Abflug", form.earliestDeparture, errors[SearchField.EARLIEST], "JJJJ-MM-TT", Modifier.weight(1f)) { v -> onChange { it.copy(earliestDeparture = v) } }
                Field("Spätester Rückflug", form.latestReturn, errors[SearchField.LATEST], "JJJJ-MM-TT", Modifier.weight(1f)) { v -> onChange { it.copy(latestReturn = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Min. Nächte", form.minStay, errors[SearchField.MIN_STAY], null, Modifier.weight(1f), KeyboardType.Number) { v -> onChange { it.copy(minStay = v) } }
                Field("Max. Nächte", form.maxStay, errors[SearchField.MAX_STAY], null, Modifier.weight(1f), KeyboardType.Number) { v -> onChange { it.copy(maxStay = v) } }
                Field("Reisende", form.adults, errors[SearchField.ADULTS], null, Modifier.weight(1f), KeyboardType.Number) { v -> onChange { it.copy(adults = v) } }
            }
            Field("Max. Gesamtbudget (EUR)", form.maxBudget, errors[SearchField.BUDGET], "optional", keyboard = KeyboardType.Decimal) { v -> onChange { it.copy(maxBudget = v) } }
            Text("Wunschflugzeuge", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                catalog.types.filter { it.status != "historical" && it.defaultCoolness >= 5.5 }.forEach { t ->
                    val on = t.id in form.desiredAircraft
                    FilterChip(
                        selected = on,
                        onClick = { onChange { f -> f.copy(desiredAircraft = if (on) f.desiredAircraft - t.id else f.desiredAircraft + t.id) } },
                        label = { Text(t.displayName) },
                    )
                }
            }
            TextButton(onClick = { more = !more }) { Text(if (more) "Weniger Optionen" else "Weitere Optionen") }
            AnimatedVisibility(more) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Max. Umstiege je Richtung: ${if (form.maxStops == 0) "nur Nonstop" else form.maxStops}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..3).forEach { n -> FilterChip(selected = form.maxStops == n, onClick = { onChange { it.copy(maxStops = n) } }, label = { Text(if (n == 0) "Nonstop" else "$n") }) }
                    }
                    Field("Max. Reisezeit je Richtung (h)", form.maxTravelHours, errors[SearchField.TRAVEL_HOURS], "optional", keyboard = KeyboardType.Decimal) { v -> onChange { it.copy(maxTravelHours = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("economy" to "Economy", "premium_economy" to "Premium", "business" to "Business", "first" to "First").forEach { (k, l) ->
                            FilterChip(selected = form.cabinClass == k, onClick = { onChange { it.copy(cabinClass = k) } }, label = { Text(l) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = form.requireCheckedBag, onCheckedChange = { c -> onChange { it.copy(requireCheckedBag = c) } })
                        Spacer(Modifier.width(8.dp))
                        Text("Aufgabegepäck benötigt")
                    }
                    Text("Datumskombinationen je Route: ${form.maxDateCombinations}")
                    androidx.compose.material3.Slider(
                        value = form.maxDateCombinations.toFloat(), valueRange = 1f..20f, steps = 18,
                        onValueChange = { v -> onChange { it.copy(maxDateCombinations = v.toInt()) } },
                    )
                }
            }
            Button(onClick = onSearch, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth()) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Suche läuft …")
                } else Text("Reisen suchen")
            }
            if (!enabled) Text("Suche erst möglich, wenn ein Backend verbunden ist (Profil).", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    placeholder: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, isError = error != null, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

@Composable
private fun SortAndFilter(state: DiscoverUiState, vm: DiscoverViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SortKey.entries.forEach { k -> FilterChip(selected = state.sort == k, onClick = { vm.setSort(k) }, label = { Text(k.label) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.filter.nonstopOnly, onClick = { vm.setFilter(state.filter.copy(nonstopOnly = !state.filter.nonstopOnly)) }, label = { Text("Nur Nonstop") })
            FilterChip(selected = state.filter.wishlistOnly, onClick = { vm.setFilter(state.filter.copy(wishlistOnly = !state.filter.wishlistOnly)) }, label = { Text("Nur Wunschflugzeug") })
            Button(onClick = vm::runCompare, enabled = state.compareIds.size >= 2) { Text("Vergleichen (${state.compareIds.size}/4)") }
        }
    }
}

@Composable
private fun CompareCard(cmp: CompareResponseDto, results: List<ScoredItineraryDto>, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vergleich", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Schließen") }
            }
            val ranks = cmp.itineraryIds.map { id -> results.firstOrNull { it.itinerary.id == id }?.rank ?: 0 }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row { Cell("", header = true, w = 130); ranks.forEach { Cell("Rang $it", header = true) } }
                    cmp.rows.forEach { row ->
                        HorizontalDivider()
                        Row {
                            Cell(row.label, header = true, w = 130)
                            row.values.forEachIndexed { i, v -> Cell(v, best = row.bestIndex == i) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false, best: Boolean = false, w: Int = 120) {
    Box(Modifier.width(w.dp).padding(6.dp)) {
        Text(
            text + if (best) " ✓" else "",
            fontWeight = if (header || best) FontWeight.SemiBold else FontWeight.Normal,
            color = if (best) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = if (best) Modifier.semantics { contentDescription = "$text, bester Wert" } else Modifier,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultCard(
    item: ScoredItineraryDto,
    catalog: AircraftCatalog,
    favorite: Boolean,
    inCompare: Boolean,
    onFavorite: () -> Unit,
    onCompare: () -> Unit,
    history: Map<String, HistoryState> = emptyMap(),
    onLoadHistory: (String, String, String) -> Unit = { _, _, _ -> },
) {
    val it = item.itinerary
    var open by remember { mutableStateOf(item.rank == 1) }
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("#${item.rank}  ${it.origin} ⇄ ${it.destination}", style = MaterialTheme.typography.titleMedium)
                    Text("${Format.date(it.outboundDate)} – ${Format.date(it.returnDate)} · ${if (it.stops == 0) "Nonstop" else "${it.stops} Umstieg(e)"}")
                    Text("${Format.duration(it.totalTravelMinutes)} Flugzeit · ${it.fullStayDays} volle Tage vor Ort", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.money(it.price.total, it.price.currency), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(if (item.extraCostVsCheapest > 0) "+${Format.money(item.extraCostVsCheapest, it.price.currency)}" else "günstigste", style = MaterialTheme.typography.labelSmall)
                    Text("${item.totalScore.toInt()}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { contentDescription = "Score ${item.totalScore.toInt()} von 100" })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (it.isDemo) Tag("DEMO", Demo.copy(alpha = 0.18f), Demo)
                if (it.priceSource == "test_offer") Tag("TESTMODUS", Demo.copy(alpha = 0.18f), Demo)
                item.wishlistHits.forEach { id -> Tag("★ ${catalog.name(id)}", MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f), MaterialTheme.colorScheme.secondary) }
                it.aircraftTypeIds.filter { id -> id !in item.wishlistHits }.forEach { id -> Tag(catalog.name(id)) }
            }
            if (open) {
                HorizontalDivider()
                Text(item.explanation.summary)
                Section("Warum diese Reise", item.explanation.reasons)
                Section("Kompromisse", item.explanation.tradeoffs + listOfNotNull(item.explanation.betterValueNote?.let { n -> "💡 $n" }))
                Text("Bewertung", style = MaterialTheme.typography.titleMedium)
                item.components.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.label} ×${c.weight.toInt()}", Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { c.score.toFloat() }, modifier = Modifier.weight(1f).height(8.dp)
                            .semantics { contentDescription = "${c.label}: ${(c.score * 100).toInt()} Prozent. ${c.detail}" })
                        Text(" +${"%.1f".format(c.contribution)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                it.legs.forEach { leg -> LegView(leg, catalog, history, onLoadHistory) }
                Text("Preis", style = MaterialTheme.typography.titleMedium)
                KeyValue("Gesamt", "${Format.money(it.price.total, it.price.currency)} für ${it.price.travelers} Reisende(n)")
                it.price.base?.let { b -> KeyValue("Grundpreis", Format.money(b, it.price.currency)) }
                it.price.taxes?.let { t -> KeyValue("Steuern & Gebühren", Format.money(t, it.price.currency)) }
                KeyValue("Gepäck", it.price.baggageNote ?: "keine Angabe")
                KeyValue("Preisquelle", "${Format.PRICE_SOURCE[it.priceSource] ?: it.priceSource} (${it.provider})")
                KeyValue("Abgerufen", it.fetchedAt.take(16).replace('T', ' ') + " UTC")
                item.aircraftPremium?.let { p -> KeyValue("Wunschflugzeug-Aufpreis", Format.money(p, it.price.currency)) }
                Section("Unsicherheiten", (item.explanation.uncertainties + it.dataNotes).distinct())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "Weniger" else "Details") }
                OutlinedButton(onClick = onFavorite) { Text(if (favorite) "★ Gespeichert" else "☆ Merken") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = inCompare, onCheckedChange = { onCompare() })
                    Text("Vergleichen")
                }
                if (it.bookingOptions.isEmpty()) {
                    AssistChip(onClick = {}, enabled = false, label = { Text(if (it.isDemo) "Demo – nicht buchbar" else "Kein verifizierter Buchungslink") })
                } else it.bookingOptions.forEach { b ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.url))) }) {
                        Text(if (b.isDeepLink) "Buchen ↗" else if (b.kind == "meta_search") "Bei Aviasales prüfen ↗" else "Airline-Website ↗")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium)
    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun LegView(leg: LegDto, catalog: AircraftCatalog, history: Map<String, HistoryState>, onLoadHistory: (String, String, String) -> Unit) {
    Text(
        (if (leg.direction == "outbound") "Hinflug" else "Rückflug") + " · " + Format.duration(leg.durationMinutes),
        style = MaterialTheme.typography.titleMedium,
    )
    if (!leg.detailsComplete) {
        Text(
            "${leg.reportedStops ?: "?"} Umstieg(e) – Umsteigeflughafen und Anschlussflug vom Provider nicht übermittelt.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
        )
    }
    leg.segments.forEachIndexed { i, s ->
        Text("${Format.date(s.departureLocal)} ${Format.time(s.departureLocal)} → ${Format.time(s.arrivalLocal)}  ${s.origin} → ${s.destination}", fontWeight = FontWeight.SemiBold)
        Text(
            "${s.marketingCarrier}${if (s.flightNumber.isNotEmpty()) " " + s.flightNumber else " (Flugnummer unbekannt)"}${s.marketingCarrierName?.let { n -> " ($n)" } ?: ""} · " +
                "${s.aircraftTypeId?.let(catalog::name) ?: s.aircraftName ?: "Typ unbekannt"} · ${Format.AIRCRAFT_STATUS[s.aircraftStatus] ?: s.aircraftStatus}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        if (s.flightNumber.isNotEmpty()) HistoryRow(s.marketingCarrier, s.flightNumber, s.origin, history, onLoadHistory)
        leg.connections.getOrNull(i)?.let { c ->
            val warn = c.risk == "tight" || c.risk == "invalid"
            Text(
                "Umstieg in ${c.airport}: ${Format.duration(c.minutes)}${c.note?.let { n -> " – $n" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryRow(carrier: String, number: String, origin: String, history: Map<String, HistoryState>, onLoad: (String, String, String) -> Unit) {
    when (val h = history[historyKey(carrier, number, origin)]) {
        null -> TextButton(onClick = { onLoad(carrier, number, origin) }) { Text("Typverlauf $carrier $number prüfen") }
        HistoryState.Loading -> Text("Prüfe beobachtete Flüge …", style = MaterialTheme.typography.labelSmall)
        is HistoryState.Failed -> Text(h.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        is HistoryState.Loaded -> {
            val d = h.history
            if (d.status == "ok") {
                Text(
                    "${d.callsign} ab ${d.origin}, letzte ${d.daysChecked} Tage: " +
                        d.distribution.joinToString(", ") { t -> "${t.name} ${t.count}× (${(t.share * 100).toInt()} %)" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                )
                Text("Historisch beobachtet – keine Garantie für deinen Flugtag.", style = MaterialTheme.typography.labelSmall)
            } else {
                Text(d.message ?: "Keine Daten.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Heatmap: günstigster erhaltener Preis je Abflugtag (Spalten) und Aufenthaltsdauer (Zeilen). */
@Composable
private fun PriceCalendar(
    cells: List<com.atomkarnickel.aviationoptimizer.data.remote.CalendarCellDto>,
    selectedDate: String?,
    selectedNights: Int?,
    onSelect: (String?, Int?) -> Unit,
) {
    val dates = cells.map { it.departureDate }.distinct().sorted()
    val nights = cells.map { it.nights }.distinct().sorted()
    val byKey = cells.associateBy { it.departureDate to it.nights }
    val min = cells.minOf { it.minPrice }
    val max = cells.maxOf { it.minPrice }
    val cheapest = cells.first { it.minPrice == min }
    val accent = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preiskalender", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selectedDate != null || selectedNights != null) TextButton(onClick = { onSelect(null, null) }) { Text("Auswahl aufheben") }
            }
            Text(
                "Günstigste Kombination: ${Format.date(cheapest.departureDate)}, ${cheapest.nights} Nächte – ${Format.money(min, cheapest.currency)}. " +
                    "Leere Felder = keine Angebote.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row {
                        Box(Modifier.width(44.dp).padding(2.dp)) { Text("Nächte", style = MaterialTheme.typography.labelSmall) }
                        dates.forEach { d -> Box(Modifier.width(54.dp).padding(2.dp)) { Text(Format.date(d).take(6), style = MaterialTheme.typography.labelSmall) } }
                    }
                    nights.forEach { n ->
                        Row {
                            Box(Modifier.width(44.dp).padding(2.dp)) { Text("$n", fontWeight = FontWeight.SemiBold) }
                            dates.forEach { d ->
                                val c = byKey[d to n]
                                Box(Modifier.width(54.dp).padding(2.dp)) {
                                    if (c != null) {
                                        val t = if (max == min) 0f else ((c.minPrice - min) / (max - min)).toFloat()
                                        val on = selectedDate == d && selectedNights == n
                                        androidx.compose.material3.Surface(
                                            onClick = { if (on) onSelect(null, null) else onSelect(d, n) },
                                            color = androidx.compose.ui.graphics.lerp(surface, accent, (1f - t) * 0.65f + 0.08f),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                            border = if (on) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                            modifier = Modifier.fillMaxWidth().height(32.dp).semantics {
                                                contentDescription = "Abflug ${Format.date(d)}, $n Nächte, ab ${Format.money(c.minPrice, c.currency)}"
                                            },
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("${c.minPrice.toInt()}", style = MaterialTheme.typography.labelSmall, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
