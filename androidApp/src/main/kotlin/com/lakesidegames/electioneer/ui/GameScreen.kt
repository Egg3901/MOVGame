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
        // EV tally bar: 270 to win.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DEM $demEv", color = Color(0xFF1D4ED8))
            Spacer(Modifier.weight(1f))
            Text("270 to win", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("$repEv REP", color = Color(0xFFB91C1C))
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (demEv + tossEv / 2f) / 538f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                "Week ${g.turn + 1}/${g.totalTurns}",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Cash \$${"%.1f".format((res?.cash ?: 0.0) / 1_000_000)}M",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Actions ${session.slotsLeft()}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))

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
            Text(
                "${sel.name}: ${sel.electoralVotes} EV, Dem $pct%",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("Ads") {
                    session.queueAction(ActionType.ADVERTISE, sel.id)
                }
                ActionButton("Rally") {
                    session.queueAction(ActionType.RALLY, sel.id)
                }
                ActionButton("Ground") {
                    session.queueAction(ActionType.GROUND_GAME, sel.id)
                }
                ActionButton("GOTV") {
                    session.queueAction(ActionType.GOTV, sel.id)
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
        ) { Text("End Week") }
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
