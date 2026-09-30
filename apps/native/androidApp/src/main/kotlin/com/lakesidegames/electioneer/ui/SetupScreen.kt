package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.EventMode

@Composable
fun HomeScreen(session: GameSession) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("THE ROAD TO 270", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("Margin of\nVictory", style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black)
        Text("Every state has a story. Every decision moves the map.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (session.hasSave()) {
            Card(Modifier.fillMaxWidth().clickable { session.resumeGame() }) {
                Column(Modifier.padding(20.dp)) {
                    Text("CONTINUE CAMPAIGN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(session.savedCampaignLabel() ?: "Your campaign", style = MaterialTheme.typography.headlineSmall)
                    Text("Return to the campaign trail →", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = { session.go(Screen.LIBRARY) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start a new campaign")
        }
        val daily = session.dailyAssignment()
        Card(Modifier.fillMaxWidth().clickable { session.openDaily() }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DAILY CHALLENGE · ${daily.date}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("${daily.flag} ${daily.label}", style = MaterialTheme.typography.titleMedium)
            Text("Play as ${daily.roleName} · same seed and events for everyone", style = MaterialTheme.typography.bodySmall)
            Text(session.dailyBest()?.let { "Played · Best $it · ${session.dailyStreak()}-day streak" } ?: "Play today's challenge", color = MaterialTheme.colorScheme.primary)
        } }
        Text("Six countries. Historical campaigns and today's political battles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SetupScreen(session: GameSession) {
    val campaigns = remember { session.campaigns() }
    var scenarioId by remember { mutableStateOf(session.setupScenarioId) }
    var player by remember { mutableStateOf(if (session.dailySetup?.role == "rep") CandidateId.REP else CandidateId.DEM) }
    var mateId by remember { mutableStateOf("") }
    var staffIds by remember { mutableStateOf(setOf<String>()) }
    var difficulty by remember { mutableStateOf("normal") }
    var mode by remember { mutableStateOf(EventMode.HISTORICAL) }
    var turns by remember { mutableIntStateOf(9) }
    var seed by remember { mutableStateOf(session.dailySetup?.seed ?: (System.currentTimeMillis() % 1_000_000).toString().padStart(6, '0')) }
    LaunchedEffect(Unit) { session.dailySetup = null }
    var whatIfState by remember { mutableStateOf("") }
    var mirrorMatch by remember { mutableStateOf(false) }
    var pandemic by remember { mutableStateOf(false) }
    val campaign = campaigns.first { it.id == scenarioId }
    val mates = remember(scenarioId, player) { session.mates(scenarioId, player) }
    val selectedMate = mates.firstOrNull { it.id == mateId } ?: mates.firstOrNull { it.historical } ?: mates.first()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = { session.go(Screen.HOME) }) { Text("← Campaign menu") }
            Text("NEW CAMPAIGN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("Choose your path", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Build the ticket. Assemble the team. Rewrite the map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SetupSection("01  THE ELECTION") {
                Text("SWIPE THROUGH 17 ELECTIONS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(campaigns) { c ->
                        val selected = scenarioId == c.id
                        OutlinedCard(onClick = { scenarioId = c.id; mateId = "" }, modifier = Modifier.width(248.dp), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(c.year.toString(), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(c.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${c.demName}  v.  ${c.repName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(campaign.tagline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SetupSection("02  YOUR TICKET") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = player == CandidateId.DEM, onClick = { player = CandidateId.DEM; mateId = "" }, label = { Text(campaign.demName) })
                    FilterChip(selected = player == CandidateId.REP, onClick = { player = CandidateId.REP; mateId = "" }, label = { Text(campaign.repName) })
                }
                Text("Running mate", style = MaterialTheme.typography.labelMedium)
                mates.forEach { mate ->
                    val selected = selectedMate.id == mate.id
                    OutlinedCard(onClick = { mateId = mate.id }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(mate.name + if (mate.historical) " • Historical" else "", fontWeight = FontWeight.Bold)
                            Text(mate.blurb, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            SetupSection("03  WAR ROOM  ·  ${staffIds.size}/3") {
                Text("Hire up to three advisers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                session.staffChoices().forEach { staff ->
                    val selected = staff.id in staffIds
                    OutlinedCard(onClick = { staffIds = if (selected) staffIds - staff.id else if (staffIds.size < 3) staffIds + staff.id else staffIds }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${if (selected) "✓  " else ""}${staff.name} · ${staff.role}", fontWeight = FontWeight.Bold)
                            Text(staff.blurb, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            SetupSection("04  CAMPAIGN BRIEFING") {
                Text("Difficulty", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DIFFICULTIES.forEach { d -> FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(d.replaceFirstChar(Char::titlecase)) }) }
                }
                Text("Events", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.serial.replaceFirstChar(Char::titlecase)) }) }
                }
                Text("Campaign length", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 9, 14).forEach { n -> FilterChip(selected = turns == n, onClick = { turns = n }, label = { Text("$n weeks") }) }
                }
                Text("What if: make one state a tossup", style = MaterialTheme.typography.labelMedium)
                listOf("", "TX", "FL", "OH", "PA", "MI", "WI", "GA", "AZ", "NC", "NY").chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { state -> FilterChip(selected = whatIfState == state, onClick = { whatIfState = state }, label = { Text(state.ifEmpty { "Off" }) }) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Mirror match · underdog boost", modifier = Modifier.weight(1f))
                    Switch(checked = mirrorMatch, onCheckedChange = { mirrorMatch = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pandemic era issues", modifier = Modifier.weight(1f))
                    Switch(checked = pandemic, onCheckedChange = { pandemic = it })
                }
                OutlinedTextField(value = seed, onValueChange = { seed = it.take(32) }, label = { Text("Campaign seed") }, supportingText = { Text("Use the same seed to replay the same campaign") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            Button(onClick = { session.newGame(scenarioId, player, selectedMate.id, staffIds.toList(), difficulty, mode, turns, seed, whatIfState, mirrorMatch, pandemic) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Launch campaign →") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SetupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = {
            Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            content()
        })
    }
}
