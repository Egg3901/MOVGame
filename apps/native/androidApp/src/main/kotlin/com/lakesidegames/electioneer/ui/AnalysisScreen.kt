package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.NativeTrendChart

@Composable
fun AnalysisScreen(session: GameSession) {
    var region by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf("polls")) }
    val document = session.analysis(region) ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CAMPAIGN ANALYSIS", style = MaterialTheme.typography.headlineSmall)
        document.charts.forEach { chart -> TrendChart(chart) }
        ChoicePicker("State or region", document.selectedRegion, document.regions.map { it.id to it.name }) { region = it }
        document.sections.forEach { section ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { expanded = if (section.id in expanded) expanded - section.id else expanded + section.id }) {
                        Text("${if (section.id in expanded) "▾" else "▸"} ${section.title}", style = MaterialTheme.typography.titleMedium)
                    }
                    if (section.id in expanded) section.rows.forEach { row ->
                        Text(row.label, style = MaterialTheme.typography.titleSmall)
                        Text(row.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedButton(onClick = session::resumeGame) { Text("Back to campaign") }
    }
}

@Composable
private fun TrendChart(chart: NativeTrendChart) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chart.title, style = MaterialTheme.typography.titleMedium)
            Text("${chart.minimum.toInt()} to ${chart.maximum.toInt()} · ${chart.referenceLabel}", style = MaterialTheme.typography.bodySmall)
            val maxTurn = chart.series.flatMap { it.points }.maxOfOrNull { it.turn }?.coerceAtLeast(1) ?: 1
            Canvas(Modifier.fillMaxWidth().height(160.dp).semantics {
                contentDescription = chart.title + ". " + chart.series.joinToString(". ") { series ->
                    series.label + ": " + series.points.joinToString { "Week ${it.turn}: ${it.value}" }
                }
            }) {
                fun position(turn: Int, value: Double) = Offset(
                    6f + (size.width - 12f) * turn / maxTurn,
                    6f + (size.height - 12f) * (1 - (value - chart.minimum) / (chart.maximum - chart.minimum)).toFloat())
                for (fraction in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    val y = 6f + (size.height - 12f) * fraction.toFloat()
                    drawLine(Color.Gray.copy(alpha = 0.25f), Offset(6f, y), Offset(size.width - 6f, y))
                }
                val reference = position(0, chart.reference).y
                drawLine(Color.Gray, Offset(6f, reference), Offset(size.width - 6f, reference))
                chart.series.forEach { series ->
                    val color = runCatching { Color(android.graphics.Color.parseColor(series.color)) }.getOrDefault(Color.White)
                    val path = Path()
                    series.points.forEachIndexed { index, point ->
                        val at = position(point.turn, point.value)
                        if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                        drawCircle(color, radius = 3.dp.toPx(), center = at)
                    }
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Text("Start → Week $maxTurn · ${chart.series.joinToString(" / ") { it.label }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
