package com.atomkarnickel.overhead.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.atomkarnickel.overhead.R
import com.atomkarnickel.overhead.container
import com.atomkarnickel.overhead.domain.WidgetModel
import com.atomkarnickel.overhead.domain.WidgetModelBuilder
import com.atomkarnickel.overhead.domain.WidgetRow
import com.atomkarnickel.overhead.ui.MainActivity
import com.atomkarnickel.overhead.work.RefreshScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Homescreen-Widget „Flugzeuge über dir“. Inhalt aus dem letzten gespeicherten Abruf. */
class OverheadWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val snapshot = c.snapshots.load()
        val settings = c.settings.settings.first()
        val model = WidgetModelBuilder.build(snapshot, c.reference, settings.altitudeUnit, System.currentTimeMillis(), maxRows = 5)
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val radar = snapshot?.let { RadarRenderer.render(it, 280, dark) }
        provideContent { GlanceTheme { WidgetContent(model, radar) } }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 50.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 220.dp)
        val HEX_KEY = ActionParameters.Key<String>(MainActivity.EXTRA_HEX)

        suspend fun updateAll(context: Context) = runCatching { OverheadWidget().updateAll(context) }.let { }
    }
}

private val Alert = ColorProvider(Color(0xFFFF5A4F))

@Composable
private fun WidgetContent(model: WidgetModel, radar: Bitmap?) {
    val size = LocalSize.current
    val compact = size.width < 200.dp || size.height < 100.dp
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(10.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Header(model)
        Spacer(GlanceModifier.height(4.dp))
        if (compact) {
            model.rows.firstOrNull()?.let { RowItem(it) } ?: Message(model.message ?: "")
        } else {
            Row(GlanceModifier.fillMaxSize()) {
                if (radar != null) {
                    val radarSize = min(size.height - 44.dp, size.width * 0.42f)
                    Image(ImageProvider(radar), contentDescription = "Radar mit Flugzeugen um dich", modifier = GlanceModifier.size(radarSize))
                    Spacer(GlanceModifier.width(8.dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    if (model.rows.isEmpty()) Message(model.message ?: "")
                    val maxRows = ((size.height.value - 44f) / 38f).toInt().coerceIn(1, 5)
                    model.rows.take(maxRows).forEach { RowItem(it) }
                }
            }
        }
    }
}

@Composable
private fun Header(model: WidgetModel) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(model.headline, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            Text(
                model.status, maxLines = 1,
                style = TextStyle(color = if (model.stale) Alert else GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
        Image(
            ImageProvider(R.drawable.ic_refresh), contentDescription = "Aktualisieren",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(28.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

@Composable
private fun RowItem(row: WidgetRow) {
    Column(
        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(OverheadWidget.HEX_KEY to row.hex))),
    ) {
        Text(
            row.title, maxLines = 1,
            style = TextStyle(color = if (row.emergency) Alert else GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        )
        Text(
            "${row.direction} · ${row.distance} · ${row.altitude}  ${row.subtitle}", maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(text, maxLines = 3, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
}

/** Aktualisieren-Knopf im Widget: Abruf im Hintergrund anstoßen. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshScheduler.refreshNow(context)
    }
}

class OverheadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OverheadWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val c = context.container
        c.appScope.launch {
            RefreshScheduler.syncWithWidgets(context)
            val last = c.snapshots.load()
            if (last == null || System.currentTimeMillis() - last.fetchedAtMillis > 5 * 60_000L) RefreshScheduler.refreshNow(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.container.appScope.launch { RefreshScheduler.syncWithWidgets(context) }
    }
}
