package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.MobileCampaign

@Composable
fun DailyBoardsScreen(session: GameSession) {
    val account = session.account
    val user by account.user.collectAsState()
    val board by account.board.collectAsState()
    val rank by account.dailyRank.collectAsState()
    val champions by account.champions.collectAsState()
    val notice by account.notice.collectAsState()
    var selected by remember { mutableStateOf("champions") }
    var date by remember { mutableStateOf(nativeUtcDay()) }
    val elections = remember { MobileCampaign.countries().flatMap { MobileCampaign.elections(it.id) } }
    val dateValid = Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)
    fun refresh() {
        if (selected == "champions") account.loadChampions()
        else if (dateValid) account.loadBoard(date, selected.takeIf { it != "daily" })
    }
    LaunchedEffect(selected, date, user?.id) { refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LEADERBOARDS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
        ChoicePicker("Board", selected, listOf("champions" to "Daily champions", "daily" to "Daily challenge") + elections.map { it.scenarioId to "${it.flag} ${it.label}" }) { selected = it }
        if (selected == "daily") OutlinedTextField(date, { date = it.take(10) }, label = { Text("Daily date (YYYY-MM-DD, UTC)") }, singleLine = true, isError = !dateValid, modifier = Modifier.fillMaxWidth())
        if (selected == "champions") {
            champions?.let { table ->
                Text("${table.totalDays} days played")
                if (table.entries.isEmpty()) Text("No daily champions yet.")
                table.entries.forEach { entry ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text("#${entry.rank} ${entry.username}", style = MaterialTheme.typography.titleMedium)
                        Text("${entry.wins} wins · ${entry.podiums} podiums · ${entry.played} played · ${entry.totalScore} points")
                    } }
                }
            }
        } else {
            rank?.let { Text("Your daily rank: #${it.rank} · ${it.score}", color = MaterialTheme.colorScheme.primary) }
            if (board.isEmpty()) Text("No scores to show yet. You can keep playing offline.")
            board.forEach { entry -> Text("#${entry.rank} ${entry.username} · ${entry.score}") }
        }
        TextButton(onClick = ::refresh) { Text("Refresh leaderboard") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
