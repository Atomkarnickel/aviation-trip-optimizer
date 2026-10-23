package com.atomkarnickel.overhead.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.AircraftKind
import com.atomkarnickel.overhead.domain.AltitudeUnit
import com.atomkarnickel.overhead.domain.Format
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.ReferenceData
import com.atomkarnickel.overhead.domain.Snapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AircraftDetailSheet(
    aircraft: Aircraft,
    snapshot: Snapshot,
    reference: ReferenceData,
    unit: AltitudeUnit,
    photo: PhotoState,
    onShowOnMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    val r = aircraft.raw
    val kind = AircraftKind.of(r.category)
    val route = snapshot.routeFor(aircraft)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhotoBox(photo, open)
            Text(reference.typeName(r.typeCode, r.typeDescription) ?: kind.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(r.callsign, reference.airlineName(r.callsign), r.registration).distinct().joinToString(" · ").ifEmpty { "Kennung ${r.hex.uppercase()}" },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            route?.let { rt ->
                InfoCard("Route") {
                    Text(rt.airports.joinToString("  →  ") { a -> listOfNotNull(a.shortCode, a.name).joinToString(" ") }, style = MaterialTheme.typography.bodyLarge)
                    val since = rt.updatedEpochSec?.let { DateTimeFormatter.ofPattern("MM.yyyy").format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) }
                    Text(
                        when (rt.plausible) {
                            false -> "⚠ Passt nicht zur aktuellen Position – der Eintrag ist vermutlich veraltet."
                            null -> "Laut Rufzeichen-Datenbank (nicht prüfbar)."
                            true -> "Laut Rufzeichen-Datenbank, passt zur aktuellen Position."
                        } + (since?.let { " Stand des Eintrags: $it." } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            InfoCard("Wo am Himmel?") {
                Text(
                    "Blick nach ${Geo.compass(aircraft.bearingDeg)} (${aircraft.bearingDeg.roundToInt()}°), ${Format.elevation(aircraft.elevationDeg)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                KeyValue("Entfernung", Format.distance(aircraft.distanceKm))
                KeyValue("Höhe", Format.altitude(r.altitudeFt, r.onGround, unit))
            }

            InfoCard("Flugdaten") {
                KeyValue("Geschwindigkeit", Format.speed(r.groundSpeedKt))
                KeyValue("Kurs", r.trackDeg?.let { "${it.roundToInt()}° (${Geo.compass(it)})" } ?: "–")
                KeyValue("Steig-/Sinkrate", Format.verticalRate(r.verticalRateFpm))
                KeyValue("Squawk", r.squawk ?: "–")
                KeyValue("Kategorie", kind.label)
                KeyValue("Typcode (ICAO)", r.typeCode ?: "–")
                KeyValue("Transponder", r.hex.uppercase())
                KeyValue("Position", r.seenPosSec?.let { "vor ${it.roundToInt()} s empfangen" } ?: "–")
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowOnMap) { Text("Auf Karte zeigen") }
                OutlinedButton(onClick = { open("https://globe.adsb.lol/?icao=${r.hex}") }) { Text("Verlauf ↗") }
            }
            Text("Daten: ${snapshot.source} · Stand ${Format.time(snapshot.fetchedAtMillis)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoBox(photo: PhotoState, open: (String) -> Unit) {
    when (photo) {
        is PhotoState.Loaded -> {
            val bitmap = remember(photo.bytes) { BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)?.asImageBitmap() }
            if (bitmap != null) {
                Column(Modifier.clickable { open(photo.photo.pageUrl) }) {
                    Image(
                        bitmap, contentDescription = "Foto des Flugzeugs",
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop,
                    )
                    Text("© ${photo.photo.photographer} · planespotters.net ↗", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        PhotoState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Unit
    }
}
