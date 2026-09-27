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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            title = "Store",
            body = "Scenario packs will appear here after store setup. " +
                "Nothing is for sale yet.",
        )
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
fun AccountScreen() {
    Shell(
        title = "Account",
        body = "Campaign progress is saved on this device. Account sign in and " +
            "cross-device sync are not available yet.",
    )
}

@Composable
private fun Shell(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
