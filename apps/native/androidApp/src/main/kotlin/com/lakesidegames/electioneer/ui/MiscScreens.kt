package com.lakesidegames.electioneer.ui

import android.app.Activity
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lakesidegames.electioneer.engine.MobileCampaign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Phase 5 storefront (#8): lists Play products when the SKU table is
// filled, sells through Play Billing, restores on demand. Until the first
// Play Console product exists the table is empty and the screen keeps its
// "nothing for sale yet" posture.
@Composable
fun StoreScreen(session: GameSession, activity: Activity) {
    val products by session.products.collectAsState()
    val owned by session.owned.collectAsState()
    val notice by session.storeNotice.collectAsState()

    if (products.isEmpty()) {
        Shell(
            eyebrow = "CAMPAIGN LIBRARY",
            title = "History is yours to play",
            feature = "Campaigns across six countries",
            body = "Explore U.S., UK, Canadian, German, French, and Australian elections. There are no purchases in the app yet.",
        )
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Button(onClick = { session.go(Screen.LIBRARY) }, modifier = Modifier.padding(24.dp)) { Text("Choose an election") }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Store", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        for (product in products) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(product.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (product.packId in owned) "Owned" else product.price,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (product.packId !in owned) {
                    Button(onClick = { session.buy(activity, product.packId) }) {
                        Text("Buy")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { session.restorePurchases() }) {
            Text("Restore purchases")
        }
    }
}

@Composable
fun AccountScreen(session: GameSession) {
    val account = session.account
    val user by account.user.collectAsState()
    val busy by account.busy.collectAsState()
    val notice by account.notice.collectAsState()
    val unlocked by account.unlocked.collectAsState()
    val board by account.board.collectAsState()
    val dailyRank by account.dailyRank.collectAsState()
    val purchases by account.purchases.collectAsState()
    val purchasesLoaded by account.purchasesLoaded.collectAsState()
    val awards by account.awards.collectAsState()
    var showingLakeside by remember { mutableStateOf(false) }
    var showingAwards by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var selectedBoard by remember { mutableStateOf("daily") }
    val elections = remember { MobileCampaign.countries().flatMap { MobileCampaign.elections(it.id) } }
    LaunchedEffect(user) { if (user != null) password = "" }
    LaunchedEffect(selectedBoard, user?.id) { account.loadBoard(nativeUtcDay(), selectedBoard.takeIf { it != "daily" }) }
    if (showingLakeside) LakesideLogin(onCode = { code -> showingLakeside = false; account.exchangeLakeside(code) }, onClose = { showingLakeside = false })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("YOUR ACCOUNT", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        if (user != null) {
            Text("Welcome, ${user!!.username}", style = MaterialTheme.typography.headlineSmall)
            Text(user!!.email)
            Text(if (user!!.ahdLinked) "Lakeside Games account linked" else "Lakeside Games account not linked", style = MaterialTheme.typography.bodySmall)
            if (!user!!.ahdLinked) OutlinedButton(onClick = { showingLakeside = true }, enabled = !busy) { Text("Sign in with Lakeside Games") }
            Row {
                TextButton(onClick = account::refresh, enabled = !busy) { Text("Refresh account") }
                TextButton(onClick = account::signOut, enabled = !busy) { Text("Sign out") }
            }
            Text("PURCHASES", style = MaterialTheme.typography.titleSmall)
            if (!purchasesLoaded) Text(if (busy) "Loading purchase history…" else "Refresh your account to load purchase history.")
            else if (purchases.isEmpty()) Text("No purchases yet. Campaigns are free to play during the open beta.", style = MaterialTheme.typography.bodySmall)
            purchases.forEach { purchase ->
                val amount = if (purchase.amountCents == 0) "Code" else runCatching {
                    java.text.NumberFormat.getCurrencyInstance().apply { currency = java.util.Currency.getInstance(purchase.currency.uppercase()) }.format(purchase.amountCents / 100.0)
                }.getOrElse { "${purchase.amountCents / 100.0} ${purchase.currency.uppercase()}" }
                val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(purchase.createdAt))
                Text("${purchase.name} · $amount · $date${if (purchase.refunded) " · Refunded" else ""}")
            }
            Text("${unlocked.size} campaigns activated on this account", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(code, { code = it }, label = { Text("Activation code") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { account.activate(code) }, enabled = !busy && code.isNotBlank()) { Text("Activate code") }
        } else {
            Text(if (registering) "Create an account" else "Sign in", style = MaterialTheme.typography.headlineSmall)
            Text("Use the same Margin of Victory account as the web game.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showingLakeside = true }, enabled = !busy) { Text("Sign in with Lakeside Games") }
            Text("Or use email", style = MaterialTheme.typography.bodySmall)
            if (registering) OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { account.authenticate(email, password, if (registering) username else null) },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty() && (!registering || username.isNotBlank())) {
                Text(if (busy) "Please wait…" else if (registering) "Create account" else "Sign in")
            }
            TextButton(onClick = { registering = !registering }, enabled = !busy) { Text(if (registering) "Already have an account? Sign in" else "New here? Create an account") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        OutlinedButton(onClick = { session.go(Screen.SAVES) }) { Text("Manage saved campaigns") }
        TextButton(onClick = { showingAwards = !showingAwards }) { Text("Achievements (${awards.size})") }
        if (showingAwards) {
            if (awards.isEmpty()) Text("Finish a U.S. campaign to earn achievements.")
            awards.forEach { entry ->
                Text("${entry.award.icon} ${entry.award.name} · ${entry.scenarioLabel}", style = MaterialTheme.typography.titleSmall)
                Text(entry.award.blurb, style = MaterialTheme.typography.bodySmall)
            }
            if (user != null) TextButton(onClick = account::syncAchievements, enabled = !busy) { Text("Sync achievements") }
            else Text("Achievements are saved on this device. Sign in to sync them.", style = MaterialTheme.typography.bodySmall)
        }
        Text("LEADERBOARDS", style = MaterialTheme.typography.titleSmall)
        ChoicePicker("Election", selectedBoard, listOf("daily" to "Today's daily challenge") + elections.map { it.scenarioId to "${it.flag} ${it.label}" }) { selectedBoard = it }
        dailyRank?.let { Text("Your daily rank: #${it.rank} · ${it.score}", color = MaterialTheme.colorScheme.primary) }
        if (board.isEmpty()) Text("No scores to show yet. Daily challenges also work offline.", style = MaterialTheme.typography.bodySmall)
        board.forEach { entry -> Text("#${entry.rank} ${entry.username} · ${entry.score}") }
        TextButton(onClick = { account.loadBoard(nativeUtcDay(), selectedBoard.takeIf { it != "daily" }) }) { Text("Refresh leaderboard") }
        Text(session.savedCampaignLabel() ?: "No campaign on this device", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ScorePosting(session: GameSession) {
    val payload = session.scorePayload() ?: return
    val user by session.account.user.collectAsState()
    val busy by session.account.busy.collectAsState()
    val notice by session.account.notice.collectAsState()
    val daily = session.isDaily()
    if (daily) Text("DAILY CHALLENGE · Best ${session.dailyBest() ?: 0} · ${session.dailyStreak()}-day streak", color = MaterialTheme.colorScheme.primary)
    if (user == null) TextButton(onClick = { session.go(Screen.ACCOUNT) }) { Text("Sign in to post your score") }
    else Button(onClick = { session.account.postScore(payload, daily) }, enabled = !busy) { Text(if (busy) "Posting…" else if (daily) "Post daily score" else "Post to leaderboard") }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (daily) TextButton(onClick = { session.openDaily(restart = true) }) { Text("Replay today's challenge") }
}

@Composable
private fun Shell(eyebrow: String, title: String, feature: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(feature, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
