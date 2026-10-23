package com.atomkarnickel.overhead.ui

import android.graphics.RectF
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.atomkarnickel.overhead.R
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.Snapshot
import com.atomkarnickel.overhead.widget.RadarRenderer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Kartenstile von OpenFreeMap – kostenlos, ohne API-Schlüssel (© OpenMapTiles, Daten © OpenStreetMap). */
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

private const val PLANE_ICON = "plane"
private const val SRC_PLANES = "planes"
private const val SRC_OBSERVER = "observer"
private const val SRC_RADIUS = "radius"
private const val LAYER_PLANES = "planes-layer"

@Composable
fun AircraftMap(
    snapshot: Snapshot?,
    selectedHex: String?,
    onSelect: (String) -> Unit,
    onLongPress: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centeredOn by remember { mutableStateOf<Pair<LatLon, Int>?>(null) }
    val select by rememberUpdatedState(onSelect)
    val longPress by rememberUpdatedState(onLongPress)

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(RectF(p.x - 28, p.y - 28, p.x + 28, p.y + 28), LAYER_PLANES).firstOrNull()
                hit?.getStringProperty("hex")?.let { select(it); true } ?: false
            }
            m.addOnMapLongClickListener { latLng -> longPress(LatLon(latLng.latitude, latLng.longitude)); true }
            map = m
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { s ->
            ContextCompat.getDrawable(context, R.drawable.ic_plane)?.toBitmap()?.let { s.addImage(PLANE_ICON, it, true) }
            s.addSource(GeoJsonSource(SRC_RADIUS))
            s.addSource(GeoJsonSource(SRC_OBSERVER))
            s.addSource(GeoJsonSource(SRC_PLANES))
            s.addLayer(FillLayer("radius-fill", SRC_RADIUS).withProperties(PropertyFactory.fillColor("#5AD8CC"), PropertyFactory.fillOpacity(0.06f)))
            s.addLayer(LineLayer("radius-line", SRC_RADIUS).withProperties(
                PropertyFactory.lineColor("#5AD8CC"), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.8f),
            ))
            s.addLayer(CircleLayer("observer-layer", SRC_OBSERVER).withProperties(
                PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#5AD8CC"),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            ))
            s.addLayer(SymbolLayer(LAYER_PLANES, SRC_PLANES).withProperties(
                PropertyFactory.iconImage(PLANE_ICON),
                PropertyFactory.iconRotate(Expression.get("track")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.iconSize(Expression.switchCase(Expression.eq(Expression.get("selected"), Expression.literal(true)), Expression.literal(1.4f), Expression.literal(1.0f))),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor(if (dark) "#E6EEFB" else "#0F1F38"),
                PropertyFactory.textHaloColor(if (dark) "#0B1A33" else "#FFFFFF"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textOptional(true),
            ))
            style = s
        }
    }

    LaunchedEffect(style, snapshot, selectedHex) {
        val s = style ?: return@LaunchedEffect
        val snap = snapshot ?: return@LaunchedEffect
        val o = snap.observer.position
        (s.getSource(SRC_OBSERVER) as? GeoJsonSource)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(o.lon, o.lat)))
        val ring = (0..72).map { i -> Geo.destination(o.lat, o.lon, i * 5.0, snap.radiusKm.toDouble()).let { Point.fromLngLat(it.lon, it.lat) } }
        (s.getSource(SRC_RADIUS) as? GeoJsonSource)?.setGeoJson(Polygon.fromLngLats(listOf(ring)))
        val features = snap.aircraft.map { a ->
            Feature.fromGeometry(Point.fromLngLat(a.raw.lon, a.raw.lat)).apply {
                addStringProperty("hex", a.hex)
                addNumberProperty("track", a.raw.trackDeg ?: 0.0)
                addStringProperty("label", a.raw.callsign ?: a.raw.registration ?: "")
                addStringProperty("color", String.format("#%06X", RadarRenderer.colorFor(a) and 0xFFFFFF))
                addBooleanProperty("selected", a.hex == selectedHex)
            }
        }
        (s.getSource(SRC_PLANES) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))

        // Nur neu zentrieren, wenn sich Standort (> 1 km) oder Radius geändert haben – sonst bleibt die Ansicht des Nutzers.
        val last = centeredOn
        val moved = last == null || last.second != snap.radiusKm || Geo.distanceKm(last.first.lat, last.first.lon, o.lat, o.lon) > 1.0
        if (moved) {
            val ne = Geo.destination(o.lat, o.lon, 45.0, snap.radiusKm * 1.42)
            val sw = Geo.destination(o.lat, o.lon, 225.0, snap.radiusKm * 1.42)
            val bounds = LatLngBounds.Builder().include(LatLng(ne.lat, ne.lon)).include(LatLng(sw.lat, sw.lon)).build()
            // Schlägt fehl, solange die Karte noch keine Größe hat – dann beim nächsten Abruf erneut.
            if (runCatching { map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 24)) }.isSuccess) centeredOn = o to snap.radiusKm
        }
    }

    // Ausgewähltes Flugzeug in die Mitte holen.
    LaunchedEffect(selectedHex, map) {
        val a = snapshot?.aircraft?.firstOrNull { it.hex == selectedHex } ?: return@LaunchedEffect
        map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(a.raw.lat, a.raw.lon)))
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        if (snapshot == null) {
            Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
                Text("Lange auf die Karte drücken, um deinen Standort festzulegen.", Modifier.padding(12.dp))
            }
        }
    }
}
