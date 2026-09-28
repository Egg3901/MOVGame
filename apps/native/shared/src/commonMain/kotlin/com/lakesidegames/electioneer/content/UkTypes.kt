package com.lakesidegames.electioneer.content

import com.lakesidegames.electioneer.engine.Government
import com.lakesidegames.electioneer.engine.PartyId
import com.lakesidegames.electioneer.engine.PartyDef
import com.lakesidegames.electioneer.engine.PoliticalSystem
import kotlinx.serialization.Serializable

// UK + country content descriptors (mirrors content/uk/* and
// content/countries/*). Data tables are generated; functions are hand ports.

// ── UK ─────────────────────────────────────────────────────────────────────

@Serializable
data class UkLeader(
    val partyId: PartyId,
    val name: String,
    val charisma: Double,
    val energy: Double,
    val competence: Double,
    val machine: Double,
)

@Serializable
data class UkEventChoice(
    val id: String,
    val text: String,
    val resultText: String,
    val appeal: Double? = null,
    val momentum: Double? = null,
    val rivalAppeal: Double? = null,
)

@Serializable
data class UkEvent(
    val id: String,
    // {party} is substituted with the target party's short name.
    val headline: String,
    val role: String,
    val weight: Double,
    val appeal: Double? = null,
    val momentum: Double? = null,
    // Fixed target party regardless of role.
    val party: PartyId? = null,
    // Scheduled story beat: fires at this turn index instead of the draw.
    val turn: Int? = null,
    val prompt: String? = null,
    val choices: List<UkEventChoice>? = null,
)

@Serializable
data class UkMajority(
    val total: Int,
    val threshold: Int,
)

@Serializable
data class RegionResult(
    val v: Map<String, Double>,
    val s: Map<String, Int>,
)

@Serializable
data class UkElectionData(
    val id: String,
    val year: Int,
    val label: String,
    val tagline: String,
    val salience: Map<String, Double>,
    val regions: Map<String, RegionResult>,
    val majority: UkMajority? = null,
    val goalText: String? = null,
)

@Serializable
data class UkRegionMeta(
    val id: String,
    val name: String,
    val abbr: String,
    val nation: String,
    // 2024-boundary seat pool.
    val seats: Int,
    // Thousands of votes cast (approx).
    val electorate: Double,
    val profile: Map<String, Double>? = null,
)

@Serializable
data class UkBlocDef(
    val id: String,
    val name: String,
    val share: Double,
    val turnoutPropensity: Double,
    val tilt: Map<String, Double>,
)

@Serializable
data class UkIssue(
    val id: String,
    val name: String,
    val baseSalience: Double,
    val blurb: String,
)

// ── Countries ──────────────────────────────────────────────────────────────

@Serializable
data class CountryIssueDef(
    val id: String,
    val name: String,
    val blurb: String,
)

@Serializable
data class CountryBlocDef(
    val id: String,
    val name: String,
    // National electorate fraction (normalized per region).
    val share: Double,
    val turnoutPropensity: Double,
    // Per-party logit nudge vs the region baseline.
    val tilt: Map<String, Double>,
)

@Serializable
data class CountryRegionMeta(
    val id: String,
    val name: String,
    val abbr: String,
    // The region's awardable-unit pool.
    val seats: Int,
    // Thousands of votes cast (weights the national aggregate).
    val electorate: Double,
    val profile: Map<String, Double>? = null,
    val seatElasticity: Double? = null,
)

@Serializable
data class CountryElectionData(
    val id: String,
    val year: Int,
    val label: String,
    val tagline: String,
    val salience: Map<String, Double>,
    val campaignPower: Map<PartyId, Double>? = null,
    val regions: Map<String, RegionResult>,
    val majority: UkMajority? = null,
    val events: List<CountryEventDef> = emptyList(),
)

@Serializable
data class CountryLeader(
    val partyId: PartyId,
    val name: String,
    val charisma: Double,
    val energy: Double,
    val competence: Double,
    val machine: Double,
)

@Serializable
data class CountryEventChoice(
    val id: String,
    val text: String,
    val resultText: String,
    val appeal: Double? = null,
    val momentum: Double? = null,
    val rivalAppeal: Double? = null,
)

@Serializable
data class CountryEventDef(
    val id: String,
    val headline: String,
    val role: String,
    val weight: Double,
    val appeal: Double? = null,
    val momentum: Double? = null,
    val party: PartyId? = null,
    val turn: Int? = null,
    val choices: List<CountryEventChoice>? = null,
)

data class CountryBundle(
    val id: String,
    val label: String,
    val flag: String,
    val currency: String,
    val unitName: String,
    val unitNamePlural: String,
    val goalText: String,
    val system: PoliticalSystem,
    val playable: List<PartyId>,
    val abstaining: List<PartyId>,
    val defaultSeatElasticity: Double,
    val issues: List<CountryIssueDef>,
    val blocs: List<CountryBlocDef>,
    val regions: List<CountryRegionMeta>,
    val elections: Map<String, CountryElectionData>,
    val leaders: Map<String, Map<String, CountryLeader>>,
    val events: List<CountryEventDef>,
    val eventChance: Double,
    // Coalition-compatibility predicate (Germany's firewall, etc.).
    val compatible: ((lead: PartyId, partner: PartyId) -> Boolean)? = null,
    // Country-specific framing of the outcome.
    val governmentText: ((g: Government, partyName: (PartyId) -> String) -> String?)? = null,
)
