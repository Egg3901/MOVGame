package com.lakesidegames.electioneer.billing

import android.content.Context
import android.util.Base64
import com.lakesidegames.electioneer.store.CachedEntitlement
import com.lakesidegames.electioneer.store.pruneCache
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.json.JSONObject

// Signed local entitlement cache (Phase 5, #8, docs/billing.md "offline and
// refund behaviour"). Stores the verbatim Play purchase data + signature per
// pack and re-verifies the RSA signature on every read, so a stale or forged
// entry cannot re-grant a refunded pack. The Play public key arrives via
// BuildConfig (Play Console > Monetization setup); empty until then, which
// fails closed: no cached packs.
class EntitlementCache(context: Context, private val playPublicKeyBase64: String) {
    private val prefs = context.getSharedPreferences("entitlements", Context.MODE_PRIVATE)

    fun load(nowMillis: Long): List<CachedEntitlement> {
        val out = mutableListOf<CachedEntitlement>()
        for ((packId, raw) in prefs.all) {
            val parts = (raw as? String)?.split("|") ?: continue
            if (parts.size != 3) continue
            val (data, signature, confirmedAt) = parts
            if (!verify(data, signature)) continue
            out.add(CachedEntitlement(packId, confirmedAt.toLongOrNull() ?: continue))
        }
        val pruned = pruneCache(out, nowMillis)
        if (pruned.size != out.size) {
            val keep = pruned.map { it.packId }.toSet()
            val editor = prefs.edit()
            for (key in prefs.all.keys) {
                if (key !in keep) editor.remove(key)
            }
            editor.apply()
        }
        return pruned
    }

    fun store(packId: String, purchaseData: String, signature: String, nowMillis: Long) {
        if (!verify(purchaseData, signature)) return
        prefs.edit().putString(packId, "$purchaseData|$signature|$nowMillis").apply()
    }

    fun drop(packId: String) {
        prefs.edit().remove(packId).apply()
    }

    // Extracts the pack's purchase data JSON for the receipt bridge (#23):
    // the campaign-server endpoint (web repo, out of scope) will accept this
    // payload to mint a Lakeside entitlement under option 2.
    fun receiptFor(packId: String): Pair<String, String>? {
        val parts = prefs.getString(packId, null)?.split("|") ?: return null
        if (parts.size != 3) return null
        return parts[0] to parts[1]
    }

    fun verify(purchaseData: String, signature: String): Boolean {
        if (playPublicKeyBase64.isBlank() || purchaseData.isBlank() || signature.isBlank()) {
            return false
        }
        return try {
            val keyBytes = Base64.decode(playPublicKeyBase64, Base64.DEFAULT)
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            // Play signs with SHA1withRSA; accept SHA256withRSA for rotation.
            verifyWith(key, purchaseData, signature, "SHA1withRSA") ||
                verifyWith(key, purchaseData, signature, "SHA256withRSA")
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyWith(
        key: java.security.PublicKey,
        data: String,
        signature: String,
        algorithm: String,
    ): Boolean = try {
        val sig = Signature.getInstance(algorithm)
        sig.initVerify(key)
        sig.update(data.toByteArray(Charsets.UTF_8))
        sig.verify(Base64.decode(signature, Base64.DEFAULT))
    } catch (_: Exception) {
        false
    }

    companion object {
        fun packIdFromPurchaseData(purchaseData: String): String? = try {
            // INAPP purchase data carries productId (7.x) or productIds (8.x+).
            val json = JSONObject(purchaseData)
            json.optString("productId").ifBlank { null }
                ?: json.optJSONArray("productIds")?.optString(0)?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
