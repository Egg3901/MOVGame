package com.lakesidegames.electioneer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.ActionType
import com.lakesidegames.electioneer.engine.AdMode
import com.lakesidegames.electioneer.engine.GameState
import com.lakesidegames.electioneer.engine.IssueId
import com.lakesidegames.electioneer.engine.plannedActionRows

private val ACTIONS = listOf(
    ActionType.ADVERTISE to "Advertising", ActionType.RALLY to "Rally",
    ActionType.SURROGATE to "Surrogate", ActionType.FUNDRAISE to "Fundraise",
    ActionType.GROUND_GAME to "Field offices", ActionType.GOTV to "GOTV",
    ActionType.OPPO_RESEARCH to "Oppo research", ActionType.DEBATE_PREP to "Debate prep",
    ActionType.POLICY_PREP to "Policy prep", ActionType.ISSUE_PIVOT to "Issue pivot",
)

@Composable
fun ActionPlanner(session: GameSession, game: GameState, selectedId: String?) {
    var type by remember { mutableStateOf(ActionType.ADVERTISE) }
    var target by remember { mutableStateOf("PA") }
    var day by remember { mutableIntStateOf(1) }
    var adMode by remember { mutableStateOf(AdMode.POSITIVE) }
    var spend by remember { mutableFloatStateOf(8f) }
    var issue by remember { mutableStateOf(IssueId.ECONOMY) }
    var position by remember(game.scenarioId) {
        mutableFloatStateOf((game.candidates[game.playerCandidate.serial]?.issuePositions?.get(IssueId.ECONOMY.serial) ?: 0.0).toFloat())
    }
    var stateMenu by remember { mutableStateOf(false) }
    var issueMenu by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val states = remember(game.scenarioId) { game.states.filter { it.blocs.isNotEmpty() } }
    LaunchedEffect(selectedId) {
        if (selectedId != null && states.any { it.id == selectedId }) target = selectedId
    }
    val targetsState = type in setOf(ActionType.ADVERTISE, ActionType.RALLY, ActionType.SURROGATE, ActionType.GROUND_GAME, ActionType.GOTV, ActionType.FUNDRAISE)
    val dayCount = game.queuedActions.count { (it.day ?: 1) == day }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WEEK PLAN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text("Choose an action, set the target, then add it to a day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ACTIONS.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { (action, label) ->
                        FilterChip(selected = type == action, onClick = { type = action; notice = null }, label = { Text(label) })
                    }
                }
            }
            if (targetsState) {
                Box {
                    OutlinedButton(onClick = { stateMenu = true }) {
                        Text("Target: ${states.firstOrNull { it.id == target }?.name ?: "Pennsylvania"}  ▾")
                    }
                    DropdownMenu(expanded = stateMenu, onDismissRequest = { stateMenu = false }) {
                        states.forEach { state ->
                            DropdownMenuItem(text = { Text("${state.name} · ${state.electoralVotes} EV") }, onClick = { target = state.id; stateMenu = false })
                        }
                    }
                }
            }
            if (type == ActionType.ADVERTISE) {
                Text("Ad mode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdMode.entries.forEach { mode ->
                        FilterChip(selected = adMode == mode, onClick = { adMode = mode }, label = { Text(mode.serial.replaceFirstChar(Char::titlecase)) })
                    }
                }
                Text("Spend: $${spend.toInt()}M", style = MaterialTheme.typography.labelMedium)
                Slider(value = spend, onValueChange = { spend = it }, valueRange = 1f..30f, steps = 28)
            }
            if (type == ActionType.ISSUE_PIVOT || (type == ActionType.ADVERTISE && adMode == AdMode.ISSUE)) {
                Box {
                    OutlinedButton(onClick = { issueMenu = true }) { Text("Issue: ${game.issues[issue.serial]?.name ?: issue.serial}  ▾") }
                    DropdownMenu(expanded = issueMenu, onDismissRequest = { issueMenu = false }) {
                        IssueId.entries.forEach { id ->
                            DropdownMenuItem(text = { Text(game.issues[id.serial]?.name ?: id.serial) }, onClick = {
                                issue = id
                                position = (game.candidates[game.playerCandidate.serial]?.issuePositions?.get(id.serial) ?: 0.0).toFloat()
                                issueMenu = false
                            })
                        }
                    }
                }
            }
            if (type == ActionType.ISSUE_PIVOT) {
                Text("Position: ${"%.2f".format(position)}  ·  left −1 to right +1", style = MaterialTheme.typography.labelMedium)
                Slider(value = position, onValueChange = { position = it }, valueRange = -1f..1f)
            }
            Text("Add to day", style = MaterialTheme.typography.labelMedium)
            listOf(1, 2, 3, 4, 5, 6, 7).chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { n -> FilterChip(selected = day == n, onClick = { day = n }, label = { Text("$n") }) }
                }
            }
            Text("Day $day · $dayCount/3 actions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val added = session.queueConfiguredAction(type, if (targetsState) target else null, day,
                    if (type == ActionType.ADVERTISE) adMode else null,
                    if (type == ActionType.ADVERTISE) spend.toInt().toDouble() else null,
                    if (type == ActionType.ISSUE_PIVOT || (type == ActionType.ADVERTISE && adMode == AdMode.ISSUE)) issue else null,
                    if (type == ActionType.ISSUE_PIVOT) position.toDouble() else null)
                notice = if (added) null else "That day is full or your action pool is spent."
            }, enabled = session.slotsLeft() > 0 && dayCount < 3, modifier = Modifier.fillMaxWidth()) { Text("Add to day $day") }
            if (dayCount >= 3) Text("Day $day is full. Choose another day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (session.slotsLeft() == 0) Text("Weekly action pool is spent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row {
                Text("${game.queuedActions.size} planned", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { session.clearQueue() }, enabled = game.queuedActions.isNotEmpty()) { Text("Clear all") }
            }
            val rows = plannedActionRows(game)
            (1..7).forEach { n ->
                val items = rows.filter { it.day == n }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DAY $n", modifier = Modifier.width(52.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    if (items.isEmpty()) Text("Open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Column {
                        items.forEach { item ->
                            TextButton(onClick = { session.removeAction(item.index) }) { Text("${item.label}  ×") }
                        }
                    }
                }
            }
        }
    }
}
