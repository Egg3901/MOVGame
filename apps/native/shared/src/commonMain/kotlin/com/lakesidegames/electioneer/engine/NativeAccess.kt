package com.lakesidegames.electioneer.engine

/** Bundles the retired Global pack still unlocks for players who own it. */
private val globalCountries = setOf("canada", "germany", "france", "australia")

/**
 * Scenario access for the native clients. Locks apply only while the server
 * turns native gating on, which it does only once a native purchase has been
 * proven to unlock a pack; with gating off every election stays playable.
 */
object NativeAccess {
    /** True when owning [ownedPacks] covers [packId], directly or through a bundle. */
    fun covers(packId: String, ownedPacks: List<String>): Boolean =
        packId in ownedPacks || "complete" in ownedPacks || ("global" in ownedPacks && packId in globalCountries)

    /**
     * The pack a new campaign on [country]/[nativeId] needs, or null when it is
     * playable: gating off, free, today's daily challenge, unknown, or owned.
     */
    fun lockedPack(country: String, nativeId: String, ownedPacks: List<String>, gating: Boolean, dailyScenarioId: String?): String? {
        if (!gating) return null
        val election = nativeElections.firstOrNull { it.country.equals(country, ignoreCase = true) && it.nativeId == nativeId }
            ?: return null
        if (election.free || election.scenarioId == dailyScenarioId) return null
        val pack = election.packId ?: return null
        return if (covers(pack, ownedPacks)) null else pack
    }
}
