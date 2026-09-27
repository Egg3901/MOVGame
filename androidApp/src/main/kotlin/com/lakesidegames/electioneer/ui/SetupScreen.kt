package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.CandidateId

// Phase 3 Setup screen (#21): ticket + difficulty, then deal into the game.
@Composable
fun SetupScreen(session: GameSession) {
    var player by remember { mutableStateOf(CandidateId.DEM) }
    var difficulty by remember { mutableStateOf("normal") }
    val candidates = remember { session.candidates() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Margin of Victory", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("2020 Presidential Campaign", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Text("Choose your ticket", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in listOf(CandidateId.DEM, CandidateId.REP)) {
                val c = candidates[id]
                FilterChip(
                    selected = player == id,
                    onClick = { player = id },
                    label = { Text(c?.shortName ?: id.serial) },
                )
            }
        }
        candidates[player]?.let {
            Text(
                "${it.name} (${it.party.serial})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))

        Text("Difficulty", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in DIFFICULTIES) {
                FilterChip(
                    selected = difficulty == d,
                    onClick = { difficulty = d },
                    label = { Text(d.replaceFirstChar(Char::titlecase)) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { session.newGame(player, difficulty) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start Campaign") }
    }
}
