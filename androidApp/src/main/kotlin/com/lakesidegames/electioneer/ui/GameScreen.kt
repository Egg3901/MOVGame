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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.ActionType
import com.lakesidegames.electioneer.engine.CandidateId

// Phase 3 Game screen (#21): EV bar, tile map, state panel with queued
// actions, end turn, event dialogs, turn recap.
@Composable
fun GameScreen(session: GameSession) {
    val game by session.game.collectAsState()
    val projection by session.projection.collectAsState()
    val selectedId by session.selected.collectAsState()
    val pending by session.pendingDialog.collectAsState()
    val eventResult by session.eventResult.collectAsState()
    val recap by session.recap.collectAsState()
    val g = game ?: return

    val abbrToStateId = remember(g) {
        g.states.associate { it.abbr.uppercase() to it.id }
    }
    val idToAbbr = remember(g) {
        g.states.associate { it.id to it.abbr.uppercase() }
    }
    val selectedAbbr = selectedId?.let { idToAbbr[it] }
    val contestById = remember(projection) {
        projection?.contests?.associateBy { it.stateId } ?: emptyMap()
    }
    val res = g.resources[g.playerCandidate.serial]
    val demEv = projection?.ev?.get(CandidateId.DEM.serial) ?: 0
    val repEv = projection?.ev?.get(CandidateId.REP.serial) ?: 0
    val tossEv = projection?.tossupEv ?: 0

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("CAMPAIGN DESK", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(session.campaigns().firstOrNull { it.id == g.scenarioId }?.label ?: "The election", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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

        // Selected state panel.
        val sel = g.states.firstOrNull { it.id == selectedId }
        val selContest = selectedId?.let { contestById[it] }
        if (sel != null && selContest != null) {
            val pct = (selContest.demShare * 100).toInt()
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${sel.name}  ·  ${sel.electoralVotes} EV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Democratic projection $pct%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionButton("Ads") { session.queueAction(ActionType.ADVERTISE, sel.id) }
                        ActionButton("Rally") { session.queueAction(ActionType.RALLY, sel.id) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionButton("Ground") { session.queueAction(ActionType.GROUND_GAME, sel.id) }
                        ActionButton("GOTV") { session.queueAction(ActionType.GOTV, sel.id) }
                    }
                }
            }
        } else {
            Text(
                "Tap a state, then queue actions.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("Fundraise") {
                session.queueAction(ActionType.FUNDRAISE)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Queued ${g.queuedActions.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { session.clearQueue() }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { session.endTurn() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("End week  →") }
        Spacer(Modifier.height(8.dp))
    }

    // Turn recap dialog.
    recap?.let { lines ->
        AlertDialog(
            onDismissRequest = { session.dismissRecap() },
            title = { Text("Week ${g.turn + 1} recap") },
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

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}
