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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.CandidateId

// Phase 3 Results screen (#21): winner banner, EV/popular totals, state
// table, post-mortem, play again.
@Composable
fun ResultsScreen(session: GameSession) {
    val game by session.game.collectAsState()
    val g = game ?: return
    val result = g.result
    if (result == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No result yet.")
            OutlinedButton(onClick = { session.go(Screen.SETUP) }) {
                Text("Back to Setup")
            }
        }
        return
    }

    val demEv = result.electoralVotes[CandidateId.DEM.serial] ?: 0
    val repEv = result.electoralVotes[CandidateId.REP.serial] ?: 0
    val winnerName = when (result.winner) {
        CandidateId.DEM.serial -> g.candidates[CandidateId.DEM.serial]?.name ?: "Democrat"
        CandidateId.REP.serial -> g.candidates[CandidateId.REP.serial]?.name ?: "Republican"
        else -> "Tie"
    }
    val stateNames = remember(g) { g.states.associate { it.id to it.name } }
    val sorted = remember(result) {
        result.stateResults.sortedWith(
            compareByDescending<com.lakesidegames.electioneer.engine.StateResult> { it.margin }
                .thenBy { stateNames[it.stateId] },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Final Result", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(winnerName, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "DEM $demEv - $repEv REP",
            style = MaterialTheme.typography.titleMedium,
        )
        val demPop = (result.popularShare[CandidateId.DEM.serial] ?: 0.5) * 100
        Text(
            "Popular vote: Dem ${"%.1f".format(demPop)}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        for (sr in sorted) {
            val color = if (sr.winner == CandidateId.DEM) {
                Color(0xFF1D4ED8)
            } else {
                Color(0xFFB91C1C)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(
                    stateNames[sr.stateId] ?: sr.stateId,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${sr.electoralVotes} EV",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "  +${"%.1f".format(sr.margin)}",
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (result.postMortem.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("What decided it", style = MaterialTheme.typography.titleSmall)
            for (cause in result.postMortem.take(5)) {
                Text(
                    cause.cause,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { session.playAgain() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Play Again") }
    }
}
