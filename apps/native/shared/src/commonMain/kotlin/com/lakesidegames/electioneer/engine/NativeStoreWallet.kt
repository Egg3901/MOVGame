package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val storePacks = setOf("us-historical", "uk-elections", "canada", "germany", "france", "australia", "complete", "global")
private const val storeGraceMillis = 7L * 86_400_000L
@Serializable
private data class StoreOwnership(val owner: String, val packIds: List<String>, val verifiedAt: Long)
@Serializable
private data class StoredWallet(val version: Int = 1, val sessionKey: String, val owner: String, val packIds: List<String>, val confirmedAt: Long, val sourceAt: Long)

/** Platform code protects the serialized wallet in Keychain or Keystore. Only
 * an authenticated server snapshot may replace it; cache reads never renew it. */
class NativeStoreWallet private constructor(private var wallet: StoredWallet?) {
    companion object {
        fun restore(json: String?): NativeStoreWallet = NativeStoreWallet(json?.let {
            runCatching { EngineJson.decodeFromString<StoredWallet>(it) }.getOrNull()
        }?.takeIf { it.version == 1 && it.owner.isNotBlank() && it.packIds.all { pack -> pack in storePacks } })
    }
    fun accept(responseJson: String, sessionKey: String, expectedOwner: String, nowMillis: Long): Boolean {
        val snapshot = runCatching { EngineJson.decodeFromString<StoreOwnership>(responseJson) }.getOrNull() ?: return false
        if (sessionKey.isBlank() || expectedOwner.isBlank() || snapshot.owner != expectedOwner ||
            snapshot.packIds.any { it !in storePacks } || snapshot.verifiedAt <= 0 ||
            snapshot.verifiedAt < nowMillis - 300_000 || snapshot.verifiedAt > nowMillis + 300_000) return false
        val previous = wallet?.takeIf { it.sessionKey == sessionKey && it.owner == expectedOwner }
        if (previous != null && snapshot.verifiedAt < previous.sourceAt) return false
        val packs = if (previous != null && snapshot.verifiedAt == previous.sourceAt)
            snapshot.packIds.filter { it in previous.packIds } else snapshot.packIds
        wallet = StoredWallet(sessionKey = sessionKey, owner = expectedOwner,
            packIds = packs.distinct().sorted(), confirmedAt = minOf(nowMillis, snapshot.verifiedAt), sourceAt = snapshot.verifiedAt)
        return true
    }
    fun packs(sessionKey: String?, nowMillis: Long): List<String> {
        val saved = wallet ?: return emptyList()
        if (sessionKey.isNullOrBlank() || saved.sessionKey != sessionKey || saved.confirmedAt <= 0 ||
            nowMillis < saved.confirmedAt || nowMillis - saved.confirmedAt > storeGraceMillis) return emptyList()
        return saved.packIds
    }
    fun json(): String? = wallet?.let { EngineJson.encodeToString(it) }
}
