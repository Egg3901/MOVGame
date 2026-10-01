package com.lakesidegames.electioneer.ui

import com.lakesidegames.electioneer.engine.NativeDailyChampions
import com.lakesidegames.electioneer.engine.NativePlayerRankings
import com.lakesidegames.electioneer.engine.NativePlayerRanking
import android.content.Context
import com.lakesidegames.electioneer.engine.NativeStoreWallet
import java.security.MessageDigest
import com.lakesidegames.electioneer.engine.NativeAccountProgress
import com.lakesidegames.electioneer.engine.NativeCollectedAward
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AccountUser(val id: String, val username: String, val email: String, val ahdLinked: Boolean = false)
data class AccountPurchase(val name: String, val amountCents: Int?, val currency: String?, val provider: String, val refunded: Boolean, val createdAt: Long)
data class PersonalDailyRank(val rank: Int, val score: Int)
data class BoardEntry(val rank: Int, val username: String, val score: Int)
data class CloudSaveMeta(val id: String, val name: String, val turn: Int, val updatedAt: Long)

private class SessionToken(context: Context, namespace: String = "mov_account") {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = namespace + "_session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun read(): String? = runCatching {
        val data = prefs.getString("token", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString("token", Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}

private class CampaignRequestError(val status: Int, message: String, val conflict: Boolean = false) : RuntimeException(message)

class CampaignAccount(context: Context, private val scope: CoroutineScope) {
    private val vault = SessionToken(context.applicationContext)
    @Volatile private var token = vault.read()
    private val walletVault = SessionToken(context.applicationContext, "mov_store_wallet")
    private val storeWallet = NativeStoreWallet.restore(walletVault.read())
    private val _storeOwned = MutableStateFlow(cachedStorePacks())
    val storeOwned: StateFlow<Set<String>> = _storeOwned
    fun storeSessionKey(): String? = token?.let { credential ->
        MessageDigest.getInstance("SHA-256").digest(credential.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun cachedStorePacks(): Set<String> = storeWallet.packs(storeSessionKey(), System.currentTimeMillis()).toSet()
    suspend fun storeBinding(): JSONObject {
        check(_user.value?.ahdLinked == true) { "Sign in with your Lakeside account to manage shared purchases." }
        val owner = _user.value!!.id
        val response = call("/api/store/binding", "POST", JSONObject())
        check(response.getString("owner") == owner && _user.value?.id == owner) { "Account changed during store setup." }
        return response
    }
    suspend fun verifyStorePurchase(purchaseToken: String): JSONObject =
        call("/api/store/verify", "POST", JSONObject().put("store", "google").put("purchaseToken", purchaseToken))
    suspend fun refreshStoreOwnership(): Set<String> {
        val session = storeSessionKey() ?: error("Sign in to refresh shared purchases.")
        val response = call("/api/store/ownership", "POST", JSONObject())
        val owner = _user.value?.id ?: response.getString("owner")
        check(session == storeSessionKey() && storeWallet.accept(response.toString(), session, owner, System.currentTimeMillis())) {
            "Shared account ownership could not be read."
        }
        storeWallet.json()?.let { walletVault.save(it) }
        _storeOwned.value = cachedStorePacks()
        return _storeOwned.value
    }
    private val _user = MutableStateFlow<AccountUser?>(null)
    val user: StateFlow<AccountUser?> = _user
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    private val _board = MutableStateFlow<List<BoardEntry>>(emptyList())
    val board: StateFlow<List<BoardEntry>> = _board
    private val _unlocked = MutableStateFlow<List<String>>(emptyList())
    val unlocked: StateFlow<List<String>> = _unlocked
    private val _cloudSaves = MutableStateFlow<List<CloudSaveMeta>>(emptyList())
    val cloudSaves: StateFlow<List<CloudSaveMeta>> = _cloudSaves
    private var cloudVersionChecks = false
    private var boardGeneration = 0
    private val _boardKey = MutableStateFlow("")
    val boardKey: StateFlow<String> = _boardKey
    private val _champions = MutableStateFlow<NativeDailyChampions?>(null)
    val champions: StateFlow<NativeDailyChampions?> = _champions
    private val _rankings = MutableStateFlow<List<NativePlayerRanking>>(emptyList())
    val rankings: StateFlow<List<NativePlayerRanking>> = _rankings
    private val prefs = context.applicationContext.getSharedPreferences("mov_account_progress", Context.MODE_PRIVATE)
    private val progress = NativeAccountProgress.restore(prefs.getString("achievements_v1", null))
    private val _awards = MutableStateFlow(progress.awards())
    val awards: StateFlow<List<NativeCollectedAward>> = _awards
    private val _purchases = MutableStateFlow<List<AccountPurchase>>(emptyList())
    val purchases: StateFlow<List<AccountPurchase>> = _purchases
    private val _purchasesLoaded = MutableStateFlow(false)
    val purchasesLoaded: StateFlow<Boolean> = _purchasesLoaded
    private val _dailyRank = MutableStateFlow<PersonalDailyRank?>(null)
    val dailyRank: StateFlow<PersonalDailyRank?> = _dailyRank


    init { if (token != null) refresh() }

    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val credential = token
        val (status, response) = withContext(Dispatchers.IO) {
        val connection = URL("https://sim.ahousedividedgame.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = if (path.startsWith("/api/store/")) 75_000 else 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(text) }.getOrNull()
            status to response
        } finally { connection.disconnect() }
        }
        check(credential == token) { "Account changed during the request. Try again." }
        if (status == 401 && credential != null && path !in listOf("/api/auth/login", "/api/auth/register", "/api/lakeside/exchange")) signOut()
        if (status !in 200..299) throw CampaignRequestError(status, response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Account request failed ($status)", response?.optBoolean("conflict", false) == true)
        return response ?: error("The server returned an unreadable response.")
    }

    private fun operation(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true; _notice.value = null
        scope.launch {
            try { block() } catch (error: Exception) { _notice.value = error.message ?: "Could not reach the server." }
            finally { _busy.value = false }
        }
    }

    fun authenticate(email: String, password: String, username: String?) = operation {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (username != null) body.put("username", username.trim())
        val response = call("/api/auth/${if (username == null) "login" else "register"}", "POST", body)
        val credential = response.getString("token")
        withContext(Dispatchers.IO) { vault.save(credential) }
        token = credential
        readProfile(response)
        readProfile(call("/api/auth/me"))
        readDetails()
    }

    private fun readProfile(response: JSONObject) {
        val user = response.getJSONObject("user")
        _user.value = AccountUser(user.getString("id"), user.getString("username"), user.getString("email"), user.optBoolean("ahdLinked", false))
        response.optJSONObject("unlocked")?.optJSONArray("scenarioIds")?.let { ids ->
            _unlocked.value = (0 until ids.length()).map { ids.getString(it) }
        }
    }

    fun exchangeLakeside(code: String) = operation {
        val response = call("/api/lakeside/exchange", "POST", JSONObject().put("code", code))
        val credential = response.getString("token")
        withContext(Dispatchers.IO) { vault.save(credential) }
        token = credential
        readProfile(response)
        readProfile(call("/api/auth/me"))
        readDetails()
    }

    private fun persistProgress() {
        prefs.edit().putString("achievements_v1", progress.json()).apply()
        _awards.value = progress.awards()
    }
    fun recordAchievementSnapshot(snapshot: String) {
        if (!progress.recordSnapshot(snapshot)) return
        persistProgress()
        if (_user.value != null && !_busy.value) syncAchievements()
    }
    private suspend fun syncProgress() {
        val owner = _user.value?.id ?: return
        val response = call("/api/achievements")
        check(_user.value?.id == owner) { "Account changed during achievement sync." }
        val uploads = progress.uploads(response.toString()) ?: error("Achievement response unreadable.")
        persistProgress()
        for (upload in uploads) {
            check(_user.value?.id == owner) { "Account changed during achievement sync." }
            call("/api/achievements", "POST", JSONObject(upload.payload))
        }
    }
    fun syncAchievements() = operation { syncProgress(); _notice.value = "Achievements synced." }
    private suspend fun readDetails() {
        runCatching {
            val response = call("/api/leaderboard/me")
            _rankings.value = (NativePlayerRankings.parse(response.toString()) ?: error("Rankings unreadable.")).rankings
        }.onFailure { _notice.value = "Personal rankings unavailable. Refresh your account to try again." }
        runCatching {
            val response = call("/api/my-entitlements")
            val rows = response.getJSONArray("purchases")
            _purchases.value = (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                AccountPurchase(row.optString("packName").takeIf { it != "null" && it.isNotBlank() } ?: row.optString("packId", "Campaign"),
                    if (row.isNull("amountCents")) null else row.getInt("amountCents"), if (row.isNull("currency")) null else row.getString("currency"), row.optString("provider", "code"), row.getString("status") == "refunded", row.getLong("createdAt"))
            } }
            _purchasesLoaded.value = true
        }.onFailure { _notice.value = "Purchase history unavailable. Refresh your account to try again." }
        if (_user.value != null) runCatching { syncProgress() }.onFailure {
            _notice.value = "Achievements are saved on this device. Refresh your account to retry sync."
        }
    }
    fun refresh() = operation { readProfile(call("/api/auth/me")); readDetails() }
    fun activate(code: String) = operation {
        call("/api/auth/activate", "POST", JSONObject().put("code", code.trim()))
        readProfile(call("/api/auth/me"))
        readDetails()
        _notice.value = "Campaign code activated."
    }
    fun signOut() {
        _rankings.value = emptyList(); _boardKey.value = ""
        boardGeneration++; _board.value = emptyList(); _dailyRank.value = null; _purchases.value = emptyList(); _purchasesLoaded.value = false
        vault.clear(); token = null; _storeOwned.value = emptySet(); _user.value = null; _unlocked.value = emptyList(); _cloudSaves.value = emptyList(); _notice.value = null
    }

    fun loadBoard(date: String, scenarioId: String? = null) {
        val generation = ++boardGeneration
        _boardKey.value = scenarioId ?: "daily:$date"
        _board.value = emptyList(); _dailyRank.value = null
        scope.launch {
            runCatching {
                val response = call(if (scenarioId != null) "/api/leaderboard?scenario=$scenarioId&limit=20" else "/api/daily/board?date=$date")
                val entries = response.optJSONArray("entries") ?: return@runCatching
                if (generation != boardGeneration) return@runCatching
                response.optJSONObject("me")?.let { _dailyRank.value = PersonalDailyRank(it.getInt("rank"), it.getInt("score")) }
                _board.value = (0 until entries.length()).map { i -> entries.getJSONObject(i).let {
                    BoardEntry(it.getInt("rank"), it.getString("username"), it.getInt("score"))
                } }
            }.onFailure { if (generation == boardGeneration) _notice.value = "Leaderboard unavailable. You can keep playing offline." }
        }
    }

    fun loadChampions() {
        val generation = ++boardGeneration
        _boardKey.value = "champions"; _champions.value = null
        scope.launch {
            runCatching {
                val data = call("/api/daily/champions")
                if (generation == boardGeneration) _champions.value = NativeDailyChampions.parse(data.toString()) ?: error("Champions unreadable.")
            }.onFailure { if (generation == boardGeneration) _notice.value = "Daily champions unavailable. You can keep playing offline." }
        }
    }

    fun postScore(payload: String, daily: Boolean) = operation {
        val response = call(if (daily) "/api/daily" else "/api/leaderboard", "POST", JSONObject(payload))
        if (daily) loadBoard(nativeUtcDay())
        _notice.value = if (response.getBoolean("posted")) "Score posted. Rank #${response.getInt("rank")}."
            else "Kept your personal best (${response.getInt("personalBest")}). Rank #${response.getInt("rank")}."
    }

    suspend fun mirrorSave(id: String, payload: String, owner: String): com.lakesidegames.electioneer.engine.NativeCloudOutcome {
        if (_user.value?.id != owner || _busy.value) return com.lakesidegames.electioneer.engine.NativeCloudOutcome("retry", message = "Sign in to resume cloud sync.")
        _busy.value = true
        return try {
            val list = call("/api/saves")
            if (!list.optBoolean("versionPreconditions", false)) return com.lakesidegames.electioneer.engine.NativeCloudOutcome("unsupported", message = "Cloud save service needs an update.")
            val response = call("/api/saves/$id", "PUT", JSONObject(payload))
            check(_user.value?.id == owner) { "Account changed during sync." }
            val version = response.getLong("updatedAt")
            val record = JSONObject(payload)
            _cloudSaves.value = (_cloudSaves.value.filterNot { it.id == id } + CloudSaveMeta(id, record.getString("name"), record.getInt("turn"), version)).sortedByDescending { it.updatedAt }
            com.lakesidegames.electioneer.engine.NativeCloudOutcome("synced", version)
        } catch (error: Exception) {
            val status = (error as? CampaignRequestError)?.status
            com.lakesidegames.electioneer.engine.NativeCloudOutcome(if ((error as? CampaignRequestError)?.conflict == true) "conflict" else if (status != null && status in 400..499 && status !in listOf(401, 408, 429)) "rejected" else "retry", message = error.message ?: "Cloud sync will retry.")
        } finally { _busy.value = false }
    }

    private suspend fun readCloudList() {
        val response = call("/api/saves")
        cloudVersionChecks = response.optBoolean("versionPreconditions", false)
        val entries = response.getJSONArray("saves")
        _cloudSaves.value = (0 until entries.length()).map { i -> entries.getJSONObject(i).let {
            CloudSaveMeta(it.getString("id"), it.getString("name"), it.getInt("turn"), it.getLong("updatedAt"))
        } }
    }
    fun loadCloudSaves() = operation { readCloudList() }
    fun uploadSave(id: String, payload: String, onSynced: (String, Long) -> Unit) = operation {
        require(com.lakesidegames.electioneer.engine.NativeSaveLibrary.validId(id))
        val owner = _user.value?.id ?: error("Sign in to sync saves.")
        readCloudList()
        check(cloudVersionChecks) { "Cloud save service needs an update. Your campaign is saved on this device." }
        val response = call("/api/saves/$id", "PUT", JSONObject(payload))
        onSynced(owner, response.getLong("updatedAt"))
        _notice.value = "Campaign saved to the cloud."
        readCloudList()
    }
    fun downloadSave(id: String, onLoaded: (String, String, String, String, Long, String?) -> Unit) = operation {
        require(com.lakesidegames.electioneer.engine.NativeSaveLibrary.validId(id))
        val owner = _user.value?.id ?: error("Sign in to sync saves.")
        val response = call("/api/saves/$id")
        onLoaded(id, response.getString("name"), response.getJSONObject("state").toString(), owner, response.getLong("updatedAt"), response.optJSONObject("replay")?.toString())
        _notice.value = "Downloaded. Any different local copy was kept as a backup."
    }
    fun deleteCloudSave(id: String) = operation {
        require(com.lakesidegames.electioneer.engine.NativeSaveLibrary.validId(id))
        call("/api/saves/$id", "DELETE")
        readCloudList()
    }
}
