package pl.kret.w9.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import pl.kret.w9.App
import pl.kret.w9.trip.Trip
import pl.kret.w9.trip.TripPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen() {
    val app = App.instance
    val trips by app.trips.trips.collectAsStateWithLifecycle()
    val recording by app.recorder.recording.collectAsStateWithLifecycle()
    val live by app.recorder.live.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Trip?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LargeTitle("Trasy")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Łącznie", "${f1(Ui.dist(app.trips.totalKm()))} ${Ui.distUnit}", Modifier.weight(1f))
                    Stat("Przejazdów", "${trips.size}", Modifier.weight(1f))
                    Stat(
                        "km / 1% bat.",
                        app.trips.kmPerPercent()?.let { f1(Ui.dist(it)) } ?: "–",
                        Modifier.weight(1f),
                    )
                }
            }
        }
        if (recording && live.points.size > 1) {
            item {
                Panel(title = "Trwa nagrywanie • ${f1(Ui.dist(live.distanceM / 1000))} ${Ui.distUnit}") {
                    RouteMap(live.points, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
        }
        if (trips.isEmpty()) {
            item {
                Text(
                    "Brak zapisanych tras. Na zakładce Licznik kliknij „Start nagrywania trasy”.",
                    color = Ui.Muted, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(trips, key = { it.id }) { t -> TripRow(t) { open = t } }
        item { Text("", Modifier.padding(8.dp)) }
    }

    open?.let { t -> TripDetail(t, onClose = { open = null }, onDelete = { app.trips.delete(t); open = null }) }
}

private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("pl"))

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    Appear {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card)
                .bounceClick { onClick() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Ui.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Route, null, tint = Ui.Accent) }
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${f1(Ui.dist(t.distanceM / 1000))} ${Ui.distUnit}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(dateFmt.format(Date(t.start)), color = Ui.Muted, fontSize = 13.sp)
                Text(
                    "${duration(t.durationS)} · śr. ${f0(Ui.speed(t.avgKmh))} · max ${f0(Ui.speed(t.maxKmh))} ${Ui.speedUnit}",
                    color = Ui.Muted, fontSize = 13.sp,
                )
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Ui.Track)
        }
    }
}

@Composable
private fun TripDetail(t: Trip, onClose: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ui.Bg)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Wstecz", tint = Color.White) }
                    Text(dateFmt.format(Date(t.start)), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    IconButton(onClick = { shareGpx(ctx, t) }) { Icon(Icons.Filled.Share, "Eksport GPX", tint = Color.White) }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Usuń", tint = Ui.Danger) }
                }
                RouteMap(t.points, Modifier.fillMaxWidth().weight(1f))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Dystans", "${f1(Ui.dist(t.distanceM / 1000))} ${Ui.distUnit}", Modifier.weight(1f))
                        Stat("Czas", duration(t.durationS), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Średnia", "${f0(Ui.speed(t.avgKmh))} ${Ui.speedUnit}", Modifier.weight(1f))
                        Stat("Max", "${f0(Ui.speed(t.maxKmh))} ${Ui.speedUnit}", Modifier.weight(1f))
                        Stat("Bateria", t.batteryUsed?.let { "-$it%" } ?: "–", Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun RouteMap(points: List<TripPoint>, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            val geo = points.map { GeoPoint(it.lat, it.lon) }
            if (geo.isNotEmpty()) {
                val line = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = Ui.Accent.toArgb()
                    outlinePaint.strokeWidth = 12f
                }
                map.overlays.add(line)
                if (geo.size > 1) {
                    val box = BoundingBox.fromGeoPointsSafe(geo)
                    map.post { map.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
                } else {
                    map.controller.setCenter(geo.first())
                }
            }
            map.invalidate()
        },
    )
}

/** Eksport trasy do GPX (Strava, Komoot, Google Earth…). */
fun shareGpx(ctx: android.content.Context, t: Trip) {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"W9 Scooter\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    sb.append("<trk><name>Hulajnoga ${iso.format(Date(t.start))}</name><type>e-scooter</type><trkseg>\n")
    t.points.forEach { p ->
        sb.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><time>${iso.format(Date(p.t))}</time></trkpt>\n")
    }
    sb.append("</trkseg></trk></gpx>\n")
    val dir = java.io.File(ctx.cacheDir, "gpx").apply { mkdirs() }
    val f = java.io.File(dir, "trasa-${t.id}.gpx")
    f.writeText(sb.toString())
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Eksportuj trasę GPX"))
}
