package com.lakesidegames.electioneer.content

import com.lakesidegames.electioneer.engine.BlocId
import com.lakesidegames.electioneer.engine.Candidate
import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.EventTrigger
import com.lakesidegames.electioneer.engine.GameEvent
import com.lakesidegames.electioneer.engine.Issue
import com.lakesidegames.electioneer.engine.RunningMate
import kotlinx.serialization.Serializable
import com.lakesidegames.electioneer.engine.EngineJson
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Content bundles: JSON-first source of truth, exported from the web game by
// scripts/export-content-bundles.ts (web.pin revision in manifest.json).
// Values decode lazily on first access; the aggregate tests pin every table.

// ── US blocs ─────────────────────────────────────────────────────────────
@Serializable
private data class BlocsFile(val blocs: List<BlocArchetype>)

private val blocsFile: BlocsFile by lazy {
    EngineJson.decodeFromString<BlocsFile>(bundleText("us-blocs"))
}

val BLOCS: Map<BlocId, BlocArchetype> by lazy {
    blocsFile.blocs.associateBy { it.id }
}

// Bloc ids in content order (TS: Object.keys(BLOCS)).
val BLOC_IDS: List<BlocId> by lazy { BLOCS.keys.toList() }

// Logit helper (exported for the state-shift solver in setup).
fun logit(p: Double): Double {
    val clamped = min(0.999, max(0.001, p))
    return ln(clamped / (1 - clamped))
}

// ── US candidates ────────────────────────────────────────────────────────
@Serializable
private data class CandidatesFile(
    val candidates: Map<String, Candidate>,
    val opponentOf: Map<String, String>,
)

private val candidatesFile: CandidatesFile by lazy {
    EngineJson.decodeFromString<CandidatesFile>(bundleText("us-candidates"))
}

private fun cid(s: String): CandidateId =
    CandidateId.entries.first { it.serial == s }

val CANDIDATES: Map<CandidateId, Candidate> by lazy {
    candidatesFile.candidates.mapKeys { (k, _) -> cid(k) }
}

val OPPONENT_OF: Map<CandidateId, CandidateId> by lazy {
    candidatesFile.opponentOf.mapKeys { (k, _) -> cid(k) }.mapValues { (_, v) -> cid(v) }
}

// ── US staff ─────────────────────────────────────────────────────────────
@Serializable
private data class StaffFile(val pool: List<StaffDef>)

private val staffFile: StaffFile by lazy {
    EngineJson.decodeFromString<StaffFile>(bundleText("us-staff"))
}

val STAFF_POOL: List<StaffDef> by lazy { staffFile.pool }

val STAFF_BY_ID: Map<String, StaffDef> by lazy { STAFF_POOL.associateBy { it.id } }

const val MAX_STAFF = 3

// ── US events ────────────────────────────────────────────────────────────
@Serializable
private data class UsEventsFile(
    val events: List<GameEvent>,
    val endorsements: List<GameEvent>,
    val debates: List<GameEvent>,
    val historical: Map<String, List<GameEvent>> = emptyMap(),
)

private val usEventsFile: UsEventsFile by lazy {
    EngineJson.decodeFromString<UsEventsFile>(bundleText("us-events"))
}

val EVENTS: List<GameEvent> by lazy { usEventsFile.events }

val ENDORSEMENT_EVENTS: List<GameEvent> by lazy { usEventsFile.endorsements }

val GENERIC_DEBATES: List<GameEvent> by lazy { usEventsFile.debates }

// Year-agnostic random pool, drawn in both modes.
val GENERIC_EVENTS: List<GameEvent> by lazy {
    EVENTS.filter { it.trigger is EventTrigger.Stochastic } + ENDORSEMENT_EVENTS
}

// Scripted real beats per scenario (used in "historical" mode).
val HISTORICAL_EVENTS: Map<String, List<GameEvent>> by lazy { usEventsFile.historical }

val EVENTS_BY_ID: Map<String, GameEvent> by lazy {
    (EVENTS + ENDORSEMENT_EVENTS + GENERIC_DEBATES + HISTORICAL_EVENTS.values.flatten())
        .associateBy { it.id }
}

// ── US setup ─────────────────────────────────────────────────────────────
@Serializable
private data class SetupFile(
    val seeds: List<StateSeed>,
    val totalEv: Int,
    val issues: List<Issue>,
    val mates: Map<String, List<RunningMate>>,
    val scenarios: List<Scenario>,
    val ids: List<String>,
)

private val setupFile: SetupFile by lazy {
    EngineJson.decodeFromString<SetupFile>(bundleText("us-setup"))
}

val STATE_SEEDS: List<StateSeed> by lazy { setupFile.seeds }

val TOTAL_EV: Int by lazy { setupFile.totalEv }

val ISSUES: Map<String, Issue> by lazy { setupFile.issues.associateBy { it.id.serial } }

val RUNNING_MATES: Map<String, List<RunningMate>> by lazy { setupFile.mates }

// The default (historical) VP in a roster.
fun defaultRunningMate(roster: List<RunningMate>): RunningMate =
    roster.find { it.historical } ?: roster.first()

// Resolve a chosen VP id within a roster, falling back to the default.
fun resolveRunningMate(roster: List<RunningMate>, id: String? = null): RunningMate {
    if (id == null) return defaultRunningMate(roster)
    return roster.find { it.id == id } ?: defaultRunningMate(roster)
}

val SCENARIOS: Map<String, Scenario> by lazy { setupFile.scenarios.associateBy { it.id } }

val SCENARIO_IDS: List<String> by lazy { setupFile.ids }

fun getScenario(id: String? = null): Scenario =
    (if (id != null) SCENARIOS[id] else null) ?: SCENARIOS.getValue("2020")

// ── UK ───────────────────────────────────────────────────────────────────
@Serializable
private data class UkFile(
    val abstaining: List<String>,
    val system: com.lakesidegames.electioneer.engine.PoliticalSystem,
    val regions: List<UkRegionMeta>,
    val blocs: List<UkBlocDef>,
    val issues: List<UkIssue>,
    val leaders: Map<String, Map<String, UkLeader>>,
    val events: List<UkEvent>,
    val eventChance: Double,
    val electionEvents: Map<String, List<UkEvent>> = emptyMap(),
    val pools: Map<String, Map<String, Int>> = emptyMap(),
    val majorities: Map<String, UkMajority> = emptyMap(),
    val elections: Map<String, UkElectionData> = emptyMap(),
    val ids: List<String> = emptyList(),
)

private val ukFile: UkFile by lazy {
    EngineJson.decodeFromString<UkFile>(bundleText("uk"))
}

val UK_ABSTAINING: List<String> by lazy { ukFile.abstaining }

val UK_SYSTEM: com.lakesidegames.electioneer.engine.PoliticalSystem by lazy { ukFile.system }

val UK_REGIONS: List<UkRegionMeta> by lazy { ukFile.regions }

val UK_REGIONS_BY_ID: Map<String, UkRegionMeta> by lazy { UK_REGIONS.associateBy { it.id } }

val UK_TOTAL_SEATS: Int by lazy { UK_REGIONS.sumOf { it.seats } }

val UK_BLOCS: List<UkBlocDef> by lazy { ukFile.blocs }

val UK_BLOCS_BY_ID: Map<String, UkBlocDef> by lazy { UK_BLOCS.associateBy { it.id } }

val UK_BLOC_IDS: List<String> by lazy { UK_BLOCS.map { it.id } }

val UK_ISSUES: List<UkIssue> by lazy { ukFile.issues }

val UK_LEADERS: Map<String, Map<String, UkLeader>> by lazy { ukFile.leaders }

val UK_EVENTS: List<UkEvent> by lazy { ukFile.events }

val UK_EVENT_CHANCE: Double by lazy { ukFile.eventChance }

val UK_ELECTION_EVENTS: Map<String, List<UkEvent>> by lazy { ukFile.electionEvents }

val UK_BOUNDARY_POOLS: Map<String, Map<String, Int>> by lazy { ukFile.pools }

val UK_ELECTION_MAJORITY: Map<String, UkMajority> by lazy { ukFile.majorities }

val UK_ELECTIONS: Map<String, UkElectionData> by lazy { ukFile.elections }

val UK_ELECTION_IDS: List<String> by lazy { ukFile.ids }

// ── Countries ────────────────────────────────────────────────────────────
private fun loadCountry(name: String): CountryBundle {
    val decoded = EngineJson.decodeFromString<CountryBundleWire>(bundleText("countries/$name"))
    return decoded.toBundle()
}

@Serializable
private data class CountryBundleWire(
    val id: String,
    val label: String,
    val flag: String,
    val currency: String,
    val unitName: String,
    val unitNamePlural: String,
    val goalText: String,
    val system: com.lakesidegames.electioneer.engine.PoliticalSystem,
    val playable: List<String>,
    val abstaining: List<String>,
    val defaultSeatElasticity: Double,
    val issues: List<CountryIssueDef>,
    val blocs: List<CountryBlocDef>,
    val regions: List<CountryRegionMeta>,
    val elections: Map<String, CountryElectionData>,
    val leaders: Map<String, Map<String, CountryLeader>>,
    val events: List<CountryEventDef>,
    val eventChance: Double,
) {
    fun toBundle(): CountryBundle = CountryBundle(
        id = id, label = label, flag = flag, currency = currency,
        unitName = unitName, unitNamePlural = unitNamePlural, goalText = goalText,
        system = system, playable = playable, abstaining = abstaining,
        defaultSeatElasticity = defaultSeatElasticity, issues = issues, blocs = blocs,
        regions = regions, elections = elections, leaders = leaders, events = events,
        eventChance = eventChance,
    )
}

val AUSTRALIA_DATA: CountryBundle by lazy { loadCountry("au") }
val CANADA_DATA: CountryBundle by lazy { loadCountry("ca") }
val FRANCE_DATA: CountryBundle by lazy { loadCountry("fr") }
val GERMANY_DATA: CountryBundle by lazy { loadCountry("de") }
