package com.lakesidegames.electioneer.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun ElectionNightScreen(session: GameSession) {
    val night by session.electionNight.collectAsState()
    val reveal = night ?: return
    val context = LocalContext.current
    val reduced = session.reducedMotion() || Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var index by remember(reveal) { mutableIntStateOf(if (reduced) reveal.count() else 0) }
    var speed by remember(reveal) { mutableIntStateOf(1) }
    var instant by remember(reveal) { mutableStateOf(reduced) }
    val data = reveal.data()
    val board = reveal.board(index)
    LaunchedEffect(reveal, index, speed, instant) {
        if (index < reveal.count()) { delay(reveal.delay(index, speed).toLong()); index++ }
        else if (instant) { delay(1500); session.finishReveal() }
    }
    fun color(hex: String) = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LIVE · ELECTION NIGHT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(data.title, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { speed = 1; instant = false }) { Text("1×") }
            TextButton(onClick = { speed = 2; instant = false }) { Text("2×") }
            TextButton(onClick = { instant = true; index = reveal.count() }) { Text("Instant") }
            TextButton(onClick = session::finishReveal) { Text("Skip") }
        }
        Text(board.status, color = MaterialTheme.colorScheme.primary)
        board.projection?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        board.rows.forEach { row ->
            Text("${row.name}: ${row.units} ${data.unitLabel}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { row.units.toFloat() / data.totalUnits }, modifier = Modifier.fillMaxWidth(), color = color(row.color))
        }
        Text("${board.calledUnits} of ${data.totalUnits} called · ${data.threshold} to win · ${board.remaining} remaining")
        val map = reveal.map()
        val called = board.called.associateBy { it.id }
        Canvas(Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = "Election map. " + board.called.joinToString { "${it.name}: ${it.winnerShort} leads" } }) {
            val factor = minOf(size.width / map.width, size.height / map.height).toFloat()
            val left = (size.width - map.width.toFloat() * factor) / 2
            val top = (size.height - map.height.toFloat() * factor) / 2
            for (shape in map.shapes) {
                val path = Path()
                for (polygon in shape.polygons) {
                    polygon.points.forEachIndexed { i, point ->
                        val x = left + point.x.toFloat() * factor; val y = top + point.y.toFloat() * factor
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                }
                drawPath(path, called[shape.id]?.let { color(it.winnerColor) } ?: Color(0xFF283649))
                drawPath(path, Color(0xFF0A0F14), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }
        }
        board.lastCall?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        if (board.finished) Button(onClick = session::finishReveal, modifier = Modifier.fillMaxWidth()) { Text("View full results") }
    }
}
