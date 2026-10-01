package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.CandidateId

// Native campaign desk: projection, state map, seven-day plan, events and recap.
@Composable
fun GameScreen(session: GameSession) {
    val game by session.game.collectAsState()
    val projection by session.projection.collectAsState()
    val selectedId by session.selected.collectAsState()
    val pending by session.pendingDialog.collectAsState()
    val eventResult by session.eventResult.collectAsState()
    val recap by session.recap.collectAsState()
    val g = game ?: return
    var lastShortcut by remember { mutableIntStateOf(session.shortcutSequence) }
    LaunchedEffect(session.shortcutSequence) {
        if (lastShortcut == session.shortcutSequence) return@LaunchedEffect
        lastShortcut = session.shortcutSequence
        if (session.shortcut == "escape") { if (recap != null) session.dismissRecap() else session.select(null) }
        if (session.shortcut in listOf("enter", " ") && pending == null && recap == null && g.queuedActions.isNotEmpty()) session.endTurn()
    }

    val abbrToStateId = remember(g) {
        g.states.associate { it.abbr.uppercase() to it.id }
    }
    val idToAbbr = remember(g) {
        g.states.associate { it.id to it.abbr.uppercase() }
    }
    val selectedAbbr = selectedId?.let { idToAbbr[it] }
    val res = g.resources[g.playerCandidate.serial]
    val demEv = projection?.ev?.get(CandidateId.DEM.serial) ?: 0
    val repEv = projection?.ev?.get(CandidateId.REP.serial) ?: 0
    val tossEv = projection?.tossupEv ?: 0
    val selectedState = g.states.firstOrNull { it.id == selectedId }
    val selectedContest = projection?.contests?.firstOrNull { it.stateId == selectedId }

    Column(Modifier.fillMaxSize()) {
      NativeCampaignCoach(session, "US", "Win 270 electoral votes in ${g.totalTurns} weeks.", g.turn, selectedId, g.queuedActions.size)
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("CAMPAIGN DESK", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(session.campaigns().firstOrNull { it.id == g.scenarioId }?.label ?: "The election", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (session.canViewReplay()) TextButton(onClick = { session.go(Screen.REPLAY) }) { Text("Campaign replay and report") }
        TextButton(onClick = { session.go(Screen.ANALYSIS) }) { Text("Campaign analysis") }
        TextButton(onClick = session::undo, enabled = session.canUndo()) { Text("Undo") }
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("DEMOCRATS", color = Color(0xFF7EA9FF), style = MaterialTheme.typography.labelSmall)
                        Text("$demEv", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("270 TO WIN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        Text("$tossEv tossup", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("REPUBLICANS", color = Color(0xFFFF8A83), style = MaterialTheme.typography.labelSmall)
                        Text("$repEv", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    }
                }
                LinearProgressIndicator(progress = { (demEv + tossEv / 2f) / 538f }, modifier = Modifier.fillMaxWidth())
                Row {
                    Text("WEEK ${g.turn + 1}/${g.totalTurns}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("\$${"%.1f".format((res?.cash ?: 0.0) / 1_000_000)}M", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("${session.slotsLeft()} ACTIONS", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        TileMap(
            projection = projection,
            abbrToStateId = abbrToStateId,
            selectedAbbr = selectedAbbr,
            onSelect = { id, _ -> session.select(id) },
        )
        Spacer(Modifier.height(8.dp))
        if (selectedState != null && selectedContest != null) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${selectedState.name} · ${selectedState.electoralVotes} EV", fontWeight = FontWeight.Bold)
                    Text("Democratic projection ${"%.1f".format(selectedContest.demShare * 100)}%", style = MaterialTheme.typography.bodySmall)
                    Text("Select another state to retarget your plan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        ActionPlanner(session, g, selectedId)
        Spacer(Modifier.height(12.dp))
      }
      Surface(shadowElevation = 8.dp) {
        Button(
            onClick = { session.endTurn() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("End week · ${g.queuedActions.size} planned →") }
      }
    }

    // Turn recap dialog.
    recap?.let { lines ->
        AlertDialog(
            onDismissRequest = { session.dismissRecap() },
            title = { Text("Week ${g.turn} recap") },
            text = {
                Column {
                    for (line in lines) {
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { session.dismissRecap() }) { Text("OK") }
            },
        )
    }

    // Pending event dialog (player-side events after each turn).
    pending?.let { pe ->
        val def = session.eventDef(pe.eventId)
        if (def != null) {
            val result = eventResult
            AlertDialog(
                onDismissRequest = {},
                title = { Text(def.title) },
                text = {
                    Column {
                        Text(result ?: def.prompt)
                    }
                },
                confirmButton = {},
                dismissButton = {
                    if (result == null) {
                        Column {
                            for (choice in session.availableChoices(pe.eventId)) {
                                TextButton(
                                    onClick = {
                                        session.answerEvent(pe.eventId, choice.id)
                                    },
                                ) { Text(choice.text) }
                            }
                        }
                    } else {
                        TextButton(onClick = { session.closeEventDialog() }) {
                            Text("Continue")
                        }
                    }
                },
            )
        }
    }
}
