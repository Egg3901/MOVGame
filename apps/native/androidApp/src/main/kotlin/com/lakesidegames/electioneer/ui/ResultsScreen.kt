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
import androidx.compose.material3.Card
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
import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.NativeResults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton

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
    val summary = remember(g, session.campaignDifficulty) { NativeResults.us(g, session.campaignDifficulty) }
    val achievements = remember(g, session.campaignDifficulty) { NativeResults.achievements(g, session.campaignDifficulty) }
    var showHistory by remember { mutableStateOf(false) }
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
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ELECTION NIGHT", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(if (result.winner == g.playerCandidate.serial) "Victory" else "The race is over", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text(if (result.winner == "tie") "Electoral College tied. No candidate reaches 270." else "$winnerName wins the presidency", style = MaterialTheme.typography.titleMedium)
                Text("DEM $demEv   ·   $repEv REP", style = MaterialTheme.typography.headlineSmall)
                val demPop = (result.popularShare[CandidateId.DEM.serial] ?: 0.5) * 100
                Text("Democratic popular vote ${"%.1f".format(demPop)}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("Republican popular vote ${"%.1f".format(100 - demPop)}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(14.dp))
        summary?.let { info ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (info.score >= 0) "CAMPAIGN SCORE ${info.score} / 1000" else "Score unavailable for this older save", style = MaterialTheme.typography.titleMedium)
                if (info.score >= 0) Text("${info.difficulty.replaceFirstChar { it.uppercase() }} difficulty · EV margin ${info.unitMargin}")
                Text("Popular vote margin ${"%+.1f".format(info.popularMargin)} points")
                if (!info.standardLength) Text("Short and long campaigns are casual runs. Standard nine-week campaigns are comparable on the leaderboard.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        ScorePosting(session)
        if (achievements.isNotEmpty()) {
            Text("ACHIEVEMENTS EARNED", Modifier.fillMaxWidth().padding(top = 16.dp), color = MaterialTheme.colorScheme.primary)
            achievements.forEach { award -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${award.icon} ${award.name}", fontWeight = FontWeight.Bold)
                Text(award.blurb, style = MaterialTheme.typography.bodySmall)
            } }
        }
        TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide historical comparison" else "Compare with history") }
        if (showHistory) NativeResults.historicalUs(g).forEach { region -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(region.name, fontWeight = FontWeight.Bold)
            Text("${region.units} EV now · ${region.historicalUnits} historically", style = MaterialTheme.typography.bodySmall)
            Text("Vote share swing ${"%+.1f".format(region.shareSwing)} points", style = MaterialTheme.typography.bodySmall)
        } }
        Spacer(Modifier.height(18.dp))
        Text("STATE RESULTS", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

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
            Text("WHAT DECIDED IT", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
