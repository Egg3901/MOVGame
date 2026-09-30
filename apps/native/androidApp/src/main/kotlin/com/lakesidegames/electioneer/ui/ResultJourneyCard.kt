package com.lakesidegames.electioneer.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.NativeResultsJourney

@Composable
internal fun ResultJourneyCard(session: GameSession) {
    val snapshot = session.currentSnapshot() ?: return
    val journey = remember(snapshot, nativeUtcDay()) { NativeResultsJourney.create(snapshot, nativeUtcDay()) } ?: return
    val context = LocalContext.current
    val account = session.account
    val user by account.user.collectAsState()
    val board by account.board.collectAsState()
    val rank by account.dailyRank.collectAsState()
    val boardKey by account.boardKey.collectAsState()
    LaunchedEffect(journey.shareText, user?.id) { if (journey.daily) account.loadBoard(nativeUtcDay()) }
    Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (journey.daily && journey.score >= 0) {
                Text("DAILY · ${nativeUtcDay()} · ${journey.score} / 1000")
                val percentile = if (boardKey == "daily:${nativeUtcDay()}")
                    NativeResultsJourney.percentile(journey.score, board.map { it.score }, rank?.rank) else null
                percentile?.let { Text("Top $it% vs today's board${rank?.let { mine -> " · Rank #${mine.rank}" }.orEmpty()}") }
                TextButton(onClick = { account.loadBoard(nativeUtcDay()) }) { Text("Refresh daily ranking") }
            }
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, journey.shareText) }
                context.startActivity(Intent.createChooser(intent, "Share campaign result"))
            }) { Text("Share campaign result") }
            journey.next?.let { next ->
                Text("NEXT CAMPAIGN")
                Text(next.label)
                Text(next.blurb)
                Button(onClick = session::playNextCampaign) { Text("Play ${next.electionId}") }
            }
        }
    }
}
