package com.lakesidegames.electioneer.content

import com.lakesidegames.electioneer.engine.Government
import com.lakesidegames.electioneer.engine.PartyDef
import com.lakesidegames.electioneer.engine.PartyId

// Hand-ported UK + country content functions (the data tables are generated in
// UkData.kt). Mirrors content/uk/leaders.ts, events.ts, elections.ts and the
// per-country compatible/governmentText predicates.

fun leaderFor(electionId: String, partyId: PartyId, partyName: String): UkLeader {
    return UK_LEADERS[electionId]?.get(partyId) ?: UkLeader(
        partyId = partyId,
        name = "$partyName leader",
        charisma = 55.0, energy = 60.0, competence = 60.0, machine = 55.0,
    )
}

fun headlineFor(ev: UkEvent, partyShort: String): String =
    ev.headline.replace("{party}", partyShort)

val UK_MAJORS: List<PartyId> = listOf("lab", "con", "ld", "ref", "grn", "snp", "pc")

// Parties a human may lead (GB-wide majors). NI parties + 'oth' are AI/fixed.
val UK_PLAYABLE: List<PartyId> = UK_MAJORS.toList()

val PARTY_BY_ID: Map<String, PartyDef> = UK_SYSTEM.parties.associateBy { it.id }

fun pickTarget(
    role: String,
    player: PartyId,
    leader: PartyId,
    parties: List<PartyId>,
    pick: (List<PartyId>) -> PartyId,
): PartyId {
    val majors = parties.filter { UK_MAJORS.contains(it) }
    return when (role) {
        "player" -> player
        "leader" -> leader
        "challenger" -> majors.filter { it != leader }.let { if (it.isNotEmpty()) pick(it) else player }
        else -> if (majors.isNotEmpty()) pick(majors) else player
    }
}

fun majorityForElection(electionId: String): UkMajority =
    UK_ELECTION_MAJORITY[electionId] ?: UkMajority(total = 650, threshold = 326)

fun poolFor(electionId: String, regionId: String): Int =
    UK_BOUNDARY_POOLS[electionId]?.get(regionId)
        ?: UK_BOUNDARY_POOLS.getValue("2024")[regionId]
        ?: 0

// ── Country predicates ─────────────────────────────────────────────────────

private fun auCompatible(lead: PartyId, partner: PartyId): Boolean {
    val rivals = setOf("alp", "lnp")
    if (rivals.contains(lead) && rivals.contains(partner)) return false
    if (lead == "grn" || partner == "grn") return lead == "alp" || partner == "alp"
    if (lead == "ind" || partner == "ind") return false
    if (lead == "onp" || partner == "onp") return false
    return true
}

private fun auGovernmentText(g: Government, partyName: (PartyId) -> String): String? = when (g) {
    is Government.Majority -> "${partyName(g.party)} forms majority government with ${g.seats} seats."
    is Government.Coalition -> "${g.parties.map(partyName).joinToString(" and ")} strike a governing agreement (${g.seats} seats)."
    is Government.ConfidenceSupply -> "${partyName(g.lead)} governs with confidence and supply from ${partyName(g.partner)}."
    is Government.Minority -> "${partyName(g.party)} forms minority government, and the crossbench holds the balance of power."
    is Government.Hung -> "Hung parliament: the crossbench decides who governs."
}

private fun caCompatible(lead: PartyId, partner: PartyId): Boolean {
    val rivals = setOf("lpc", "cpc")
    if (rivals.contains(lead) && rivals.contains(partner)) return false
    if (partner == "bq" || lead == "bq") return false
    return true
}

private fun frGovernmentText(g: Government, partyName: (PartyId) -> String): String? = when (g) {
    is Government.Majority -> "${partyName(g.party)} wins the presidency with ${g.seats} points."
    is Government.Minority -> "${partyName(g.party)} scrapes the narrowest of wins: ${g.seats} points."
    is Government.Hung -> "Dead heat. France recounts through the night; the Republic holds its breath."
    else -> null
}

private fun deCompatible(lead: PartyId, partner: PartyId): Boolean {
    if (lead == "afd" || partner == "afd") return false
    val pair = setOf(lead, partner)
    if (pair.contains("cdu") && pair.contains("lnk")) return false
    return true
}

val AUSTRALIA: CountryBundle = AUSTRALIA_DATA.copy(
    compatible = ::auCompatible,
    governmentText = ::auGovernmentText,
)

val CANADA: CountryBundle = CANADA_DATA.copy(compatible = ::caCompatible)

val FRANCE: CountryBundle = FRANCE_DATA.copy(
    compatible = { _, _ -> false },
    governmentText = ::frGovernmentText,
)

val GERMANY: CountryBundle = GERMANY_DATA.copy(compatible = ::deCompatible)

val COUNTRIES: Map<String, CountryBundle> = mapOf(
    "CA" to CANADA,
    "DE" to GERMANY,
    "FR" to FRANCE,
    "AU" to AUSTRALIA,
)

fun getCountry(id: String): CountryBundle? = COUNTRIES[id]
