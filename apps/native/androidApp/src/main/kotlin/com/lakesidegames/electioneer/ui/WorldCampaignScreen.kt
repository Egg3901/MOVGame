package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.*
import java.util.Locale

private fun partyColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
private fun number(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
private fun actionName(value: String): String = value.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun NativeCountryMap(map: NativeMap, regions: List<NativeRegion>, selected: String, onSelect: (String) -> Unit) {
    val paths = remember(map) { map.shapes.associate { shape ->
        shape.id to Path().apply {
            shape.polygons.forEach { polygon ->
                polygon.points.firstOrNull()?.let { first ->
                    moveTo(first.x.toFloat(), first.y.toFloat())
                    polygon.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                    close()
                }
            }
        }
    } }
    val hits = remember(map) { map.shapes.associate { shape ->
        val path = android.graphics.Path().apply {
            shape.polygons.forEach { polygon ->
                polygon.points.firstOrNull()?.let { first ->
                    moveTo(first.x.toFloat(), first.y.toFloat())
                    polygon.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                    close()
                }
            }
        }
        shape.id to android.graphics.Region().apply { setPath(path, android.graphics.Region(0, 0, map.width.toInt() + 1, map.height.toInt() + 1)) }
    } }
    val byId = regions.associateBy { it.id }
    Canvas(Modifier.fillMaxWidth().height(300.dp).pointerInput(map, onSelect) {
        detectTapGestures { point ->
            val factor = minOf(size.width / map.width, size.height / map.height)
            val x = (point.x - (size.width - map.width * factor) / 2) / factor
            val y = (point.y - (size.height - map.height * factor) / 2) / factor
            hits.entries.firstOrNull { it.value.contains(x.toInt(), y.toInt()) }?.let { onSelect(it.key) }
        }
    }) {
        val factor = minOf(size.width / map.width, size.height / map.height).toFloat()
        translate((size.width - map.width.toFloat() * factor) / 2, (size.height - map.height.toFloat() * factor) / 2) {
            scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                paths.forEach { (id, path) ->
                    drawPath(path, partyColor(byId[id]?.color ?: "#94a3b8"))
                    drawPath(path, if (id == selected) Color.White else Color(0xFF0A0F14), style = Stroke(if (id == selected) 3f / factor else 1f / factor))
                }
            }
        }
    }
}

@Composable
internal fun ChoicePicker(label: String, selected: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(choices.firstOrNull { it.first == selected }?.second ?: "Choose")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(id); open = false }) }
            }
        }
    }
}

@Composable
fun CampaignLibraryScreen(session: GameSession) {
    val countries = remember { MobileCampaign.countries() }
    val daily = remember { session.dailySetup }
    var countryId by remember { mutableStateOf(daily?.countryId ?: "US") }
    val elections = remember(countryId) { MobileCampaign.elections(countryId) }
    var electionId by remember(countryId) { mutableStateOf(daily?.electionId?.takeIf { id -> elections.any { it.nativeId == id } } ?: elections.first().nativeId) }
    val election = elections.first { it.nativeId == electionId }
    val parties = remember(countryId, electionId) {
        if (countryId == "US") emptyList() else MobileCampaign.parties(countryId, electionId)
    }
    var partyId by remember(countryId, electionId) { mutableStateOf(daily?.role?.takeIf { id -> parties.any { it.id == id } } ?: parties.firstOrNull()?.id ?: "") }
    var difficulty by remember { mutableStateOf("normal") }
    var seed by remember { mutableStateOf(daily?.seed ?: System.currentTimeMillis().toString()) }
    LaunchedEffect(Unit) { session.dailySetup = null }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("CAMPAIGN LIBRARY", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("Choose your election", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(countries) { country -> FilterChip(selected = countryId == country.id,
                    onClick = { countryId = country.id }, label = { Text("${country.flag} ${country.name}") }) }
            }
        }
        item { ChoicePicker("Election", electionId, elections.map { it.nativeId to it.label }) { electionId = it } }
        item { Text(election.blurb, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (countryId == "US") {
            item { Button(onClick = { session.setupScenarioId = electionId; session.go(Screen.SETUP) }, modifier = Modifier.fillMaxWidth()) { Text("Choose your presidential ticket") } }
        } else {
            item { ChoicePicker("Your party", partyId, parties.map { it.id to "${it.name} · ${it.leader}" }) { partyId = it } }
            item {
                val party = parties.first { it.id == partyId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(party.leader, style = MaterialTheme.typography.titleLarge)
                        Text("Charisma ${party.charisma.toInt()} · Energy ${party.energy.toInt()}")
                        Text("Competence ${party.competence.toInt()} · Machine ${party.machine.toInt()}")
                    }
                }
            }
            item { ChoicePicker("Difficulty", difficulty, DIFFICULTIES.map { it to actionName(it) }) { difficulty = it } }
            item { OutlinedTextField(seed, { seed = it.take(64) }, label = { Text("Campaign seed") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { Button(onClick = { session.startCampaign(countryId, electionId, partyId, difficulty, seed) }, enabled = seed.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Launch campaign") } }
        }
    }
}

@Composable
private fun StandingRows(rows: List<NativeStanding>, units: String) {
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).background(partyColor(row.color), RoundedCornerShape(3.dp)))
            Text(row.name, Modifier.weight(1f))
            Text("${row.units} $units · ${number(row.voteShare * 100)}%")
        }
    }
}

@Composable
fun WorldCampaignScreen(session: GameSession) {
    val campaign by session.campaign.collectAsState()
    val version by session.campaignVersion.collectAsState()
    val game = campaign ?: return
    val rows = remember(game, version) { game.standings() }
    val regions = remember(game, version) { game.regions() }
    var regionId by remember(game) { mutableStateOf("") }
    var type by remember(game) { mutableStateOf("broadcast") }
    var mode by remember(game) { mutableStateOf("positive") }
    var spend by remember(game) { mutableFloatStateOf(1.5f) }
    var issueId by remember(game) { mutableStateOf(game.issues().first().id) }
    var rival by remember(game) { mutableStateOf("") }
    var day by remember(game) { mutableIntStateOf(1) }
    var showRecap by remember(game) { mutableStateOf(false) }
    var notice by remember(game) { mutableStateOf<String?>(null) }
    var showHistory by remember(game) { mutableStateOf(false) }
    val unit = game.unitName()
    val regional = type in listOf("rally", "surrogate", "ground_game", "gotv", "canvass")
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(game.label(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (game.isOver()) "ELECTION RESULT" else "WEEK ${game.turn() + 1} OF ${game.totalTurns()}", color = MaterialTheme.colorScheme.primary)
            Text(game.goalText(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(if (game.isOver()) game.outcome() else "Projected standings", style = MaterialTheme.typography.titleMedium)
                StandingRows(rows, unit)
                Text("${game.majority()} of ${game.totalUnits()} $unit to win outright", style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (game.hasPendingEvent()) {
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(game.eventTitle(), style = MaterialTheme.typography.titleMedium)
                    Text(game.eventPrompt())
                    game.eventChoices().forEach { choice -> Button(onClick = {
                        if (game.answerEvent(choice.id)) { notice = choice.resultText; session.campaignChanged() }
                    }, modifier = Modifier.fillMaxWidth()) { Text(choice.text) } }
                } }
            }
        }
        if (notice != null) item { Text(notice!!, color = MaterialTheme.colorScheme.primary) }
        item { Text("REGIONAL ${if (game.isOver()) "RESULTS" else "PROJECTIONS"}", style = MaterialTheme.typography.labelLarge) }
        item { NativeCountryMap(game.map(), regions, regionId) { regionId = it } }
        items(regions.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { region ->
                    Card(Modifier.weight(1f).clickable { regionId = if (regionId == region.id) "" else region.id },
                        colors = CardDefaults.cardColors(containerColor = if (regionId == region.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(region.name, fontWeight = FontWeight.Bold)
                            Text("${region.winner} leads", color = partyColor(region.color))
                            Text("${region.totalUnits} $unit in region", style = MaterialTheme.typography.bodySmall)
                            Text("You: ${region.playerUnits} $unit · ${number(region.playerShare * 100)}%", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (regionId.isNotEmpty()) item { Card { Column(Modifier.padding(16.dp)) {
            Text(regions.first { it.id == regionId }.name, style = MaterialTheme.typography.titleMedium)
            StandingRows(game.regionStandings(regionId), unit)
        } } }
        item { TextButton(onClick = { session.go(Screen.ANALYSIS) }) { Text("Campaign analysis") } }
        if (!game.isOver()) item { TextButton(onClick = session::undo, enabled = session.canUndo()) { Text("Undo week") } }
        if (!game.isOver()) {
            item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Plan your week", style = MaterialTheme.typography.titleLarge)
                Text("${game.currency()}${number(game.funds())}M cash · ${game.slotsLeft()} moves left")
                Text("Planned ${game.currency()}${number(game.plannedSpend())}M · Available ${game.currency()}${number(game.availableFunds())}M")
                Text("Plan estimate: ${game.previewPlayerUnits()} $unit after your queued actions. Rival moves and events can change the result.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                PlanBonusHints(game.planBonuses())
                ChoicePicker("Action", type, game.actionTypes().map { it to actionName(it) }) { type = it }
                ChoicePicker("Target", regionId, listOf("" to "National") + regions.map { it.id to it.name }) { regionId = it }
                if (regional && regionId.isEmpty()) Text("Choose a region for this action.", color = MaterialTheme.colorScheme.primary)
                ChoicePicker("Day", day.toString(), (1..7).map { it.toString() to "Day $it" }) { day = it.toInt() }
                if (type == "broadcast") {
                    ChoicePicker("Broadcast mode", mode, listOf("positive", "contrast", "issue").map { it to actionName(it) }) { mode = it }
                    Text("Spend ${game.currency()}${number(spend.toDouble())}M")
                    Slider(spend, { spend = it }, valueRange = 0.5f..10f)
                    Text(if (regionId.isEmpty()) "National reaches every region where your party stands." else "Regional spending concentrates on this target.", style = MaterialTheme.typography.bodySmall)
                }
                if (type == "issue_pivot" || (type == "broadcast" && mode == "issue")) {
                    ChoicePicker("Issue", issueId, game.issues().map { it.id to "${it.name} · ${number(it.salience * 100)}% salience" }) { issueId = it }
                    Text(game.issues().first { it.id == issueId }.blurb, style = MaterialTheme.typography.bodySmall)
                }
                if (type == "oppo_research" || (type == "broadcast" && mode == "contrast"))
                    ChoicePicker("Rival", rival, listOf("" to "Leading rival") + game.rivals().map { it.partyId to it.name }) { rival = it }
                Button(onClick = {
                    val added = game.queue(type, regionId.ifEmpty { null }, day, if (type == "broadcast") mode else null,
                        if (type == "broadcast") spend.toDouble() else null,
                        if (type == "issue_pivot" || (type == "broadcast" && mode == "issue")) issueId else null,
                        if (type == "oppo_research" || (type == "broadcast" && mode == "contrast")) rival.ifEmpty { null } else null)
                    if (added) { notice = null; session.campaignChanged() } else notice = "Check your target, cash, remaining moves, and the three-move daily limit."
                }, enabled = game.slotsLeft() > 0 && !game.hasPendingEvent() && (!regional || regionId.isNotEmpty()), modifier = Modifier.fillMaxWidth()) { Text("Add to plan") }
            } } }
            items(game.plan()) { action ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Text("Day ${action.day} · ${action.title}"); Text("${action.detail} · ${game.currency()}${number(action.cost)}M", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { game.removeAction(action.index); session.campaignChanged() }) { Text("Remove") }
                }
            }
            item { Button(onClick = { if (game.endWeek()) { session.campaignChanged(); showRecap = game.recap().isNotEmpty() } },
                enabled = !game.hasPendingEvent(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("End week") } }
        } else {
            item { ScorePosting(session) }
            item { game.resultSummary()?.let { summary -> Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CAMPAIGN SCORE ${summary.score} / 1000", style = MaterialTheme.typography.titleMedium)
                Text("${summary.difficulty.replaceFirstChar { it.uppercase() }} difficulty · $unit above majority: ${summary.unitMargin}")
                Text("Vote margin vs. leading rival: ${"%+.1f".format(summary.popularMargin)} points")
            } } } }
            item { TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide historical comparison" else "Compare with history") } }
            if (showHistory) items(game.historicalRegions()) { region -> Column(Modifier.fillMaxWidth()) {
                Text(region.name, fontWeight = FontWeight.Bold)
                Text("${region.units} $unit now · ${region.historicalUnits} historically", style = MaterialTheme.typography.bodySmall)
                Text("Vote share swing ${"%+.1f".format(region.shareSwing)} points", style = MaterialTheme.typography.bodySmall)
            } }
            item { Text("WHAT DECIDED IT", style = MaterialTheme.typography.labelLarge) }
            items(game.resultCauses()) { Text(it) }
            item { Button(onClick = { session.go(Screen.LIBRARY) }, modifier = Modifier.fillMaxWidth()) { Text("Choose another campaign") } }
        }
        item { Text("CAMPAIGN NEWS", style = MaterialTheme.typography.labelLarge) }
        items(game.news()) { Text("Week ${it.turn + 1} · ${it.text}", style = MaterialTheme.typography.bodySmall) }
    }
    if (showRecap) AlertDialog(onDismissRequest = { showRecap = false }, title = { Text("Week in review") },
        text = { LazyColumn { items(game.recap()) { Text("${it.label}: ${it.detail}", Modifier.padding(vertical = 6.dp)) } } },
        confirmButton = { TextButton(onClick = { showRecap = false }) { Text("Continue") } })
}
