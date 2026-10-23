package com.atomkarnickel.overhead.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Teal = Color(0xFF5AD8CC)
val Amber = Color(0xFFF2B84B)
val SkyBlue = Color(0xFF4F97F0)
val AlertRed = Color(0xFFFF5A4F)

private val Light = lightColorScheme(
    primary = Color(0xFF2F7DE1),
    onPrimary = Color.White,
    secondary = Color(0xFF0F8F84),
    onSecondary = Color.White,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F1F38),
    surface = Color.White,
    onSurface = Color(0xFF0F1F38),
    surfaceVariant = Color(0xFFEEF3F9),
    onSurfaceVariant = Color(0xFF4F6380),
    surfaceContainer = Color(0xFFEEF3F9),
    outline = Color(0xFFB8C7DC),
    error = Color(0xFFC0392B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4F97F0),
    onPrimary = Color(0xFF061024),
    secondary = Teal,
    onSecondary = Color(0xFF061024),
    background = Color(0xFF081326),
    onBackground = Color(0xFFE6EEFB),
    surface = Color(0xFF0F1F3A),
    onSurface = Color(0xFFE6EEFB),
    surfaceVariant = Color(0xFF142A4D),
    onSurfaceVariant = Color(0xFF9FB3D1),
    surfaceContainer = Color(0xFF142A4D),
    outline = Color(0xFF2F5A93),
    error = Color(0xFFFF8A7A),
)

@Composable
fun OverheadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoCard(title: String, modifier: Modifier = Modifier, containerColor: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = containerColor?.let { CardDefaults.cardColors(containerColor = it) } ?: CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
