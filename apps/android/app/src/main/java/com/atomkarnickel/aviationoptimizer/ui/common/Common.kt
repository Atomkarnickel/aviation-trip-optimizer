package com.atomkarnickel.aviationoptimizer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

enum class BannerKind { INFO, WARN, ERROR, DEMO, OK }

@Composable
fun Banner(title: String, text: String, kind: BannerKind, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminanceApprox() < 0.3f
    val (bg, fg) = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        BannerKind.WARN -> (if (dark) Color(0xFF3A2C0B) else Color(0xFFFFF4D6)) to (if (dark) Color(0xFFFFD27A) else Color(0xFF8A5A00))
        BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        BannerKind.DEMO -> (if (dark) Color(0xFF2B1A45) else Color(0xFFF2E9FB)) to (if (dark) Color(0xFFD4B0FF) else Color(0xFF7A3FB0))
        BannerKind.OK -> (if (dark) Color(0xFF0D3328) else Color(0xFFE2F6EF)) to (if (dark) Color(0xFF6FE0BD) else Color(0xFF12795F))
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(title, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        if (text.isNotBlank()) Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = textColor,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

object Format {
    fun money(value: Double, currency: String): String = try {
        NumberFormat.getCurrencyInstance(Locale.GERMANY).apply {
            this.currency = Currency.getInstance(currency)
            maximumFractionDigits = 0
        }.format(value)
    } catch (e: IllegalArgumentException) {
        "${value.toInt()} $currency"
    }

    fun duration(minutes: Int) = "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"

    fun date(iso: String): String {
        val p = iso.take(10).split("-")
        return if (p.size == 3) "${p[2]}.${p[1]}.${p[0]}" else iso
    }

    fun time(iso: String) = iso.drop(11).take(5)

    val PRICE_SOURCE = mapOf(
        "live_offer" to "Live-Angebot",
        "test_offer" to "Testmodus – kein echter Preis",
        "estimated" to "Schätzung – nicht buchbar",
        "demo" to "DEMO – fiktive Daten",
    )
    val AIRCRAFT_STATUS = mapOf("confirmed" to "bestätigt", "scheduled" to "geplant", "historical" to "historisch", "unknown" to "unbekannt")
    val MODES = linkedMapOf("best_value" to "Best Value", "aircraft_hunter" to "Aircraft Hunter", "cheapest" to "Cheapest")
}
