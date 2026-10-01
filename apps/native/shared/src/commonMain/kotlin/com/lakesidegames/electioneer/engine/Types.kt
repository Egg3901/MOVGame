package com.lakesidegames.electioneer.engine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────
// Port of MOVGame `src/engine/types.ts` (+ the PartyId family from
// `src/engine/system.ts`, which types.ts imports) at web.pin
// 5647614f34fad7c05c004bf51c12040d6a9a774c.
//
// Mapping rules (documented once, applied everywhere):
// - Closed TS string unions  -> Kotlin enum with `serial` holding the exact
//   TS string, so Phase 2 JSON content bundles decode with no migration layer.
// - `Record<K, V>`            -> Map<String, V> keyed by the TS key string.
// - `Partial<Record<K, V>>`   -> Map<String, V> defaulting to emptyMap().
// - `X | undefined` / `X?`    -> nullable with null default.
// - Open unions (`CandidateId | "tie"`, Government, EventTrigger) -> sealed class.
// - TS `number`               -> Double, except discrete counts -> Int.
// - `keyof CandidateTraits`   -> String (validated at the Phase 1 port).
//
// @Serializable annotations land with the kotlinx.serialization wiring in
// Phase 2 (content bundles). This file must stay dependency-free.
// ─────────────────────────────────────────────────────────────────────────

typealias PartyId = String

@Serializable(with = IssueIdSerial::class)
enum class IssueId(val serial: String) {
    ECONOMY("economy"),
    COVID_RESPONSE("covid_response"),
    HEALTHCARE("healthcare"),
    IMMIGRATION("immigration"),
    RACE_POLICING("race_policing"),
    CLIMATE("climate"),
    TAXES("taxes"),
    LAW_AND_ORDER("law_and_order"),
    ABORTION("abortion"),
    TRADE("trade"),
}

@Serializable(with = BlocIdSerial::class)
enum class BlocId(val serial: String) {
    // US set (Phase 0 contract).
    NONCOLLEGE_WHITE("noncollege_white"),
    COLLEGE_WHITE("college_white"),
    SUBURBAN_WOMEN("suburban_women"),
    BLACK("black"),
    HISPANIC("hispanic"),
    ASIAN_OTHER("asian_other"),
    SENIORS("seniors"),
    YOUTH("youth"),
    // UK set (TS casts these unsoundly; the port types them exactly).
    YOUNG("young"),
    GRADUATE("graduate"),
    WORKINGCLASS("workingclass"),
    HOMEOWNER("homeowner"),
    RENTER("renter"),
    PENSIONER("pensioner"),
    // Country sets (shared ids reuse the UK members above).
    YOUNG_RENTER("young_renter"),
    MORTGAGE_BELT("mortgage_belt"),
    REGIONAL("regional"),
    SENIOR("senior"),
    MULTICULTURAL("multicultural"),
    WORKER("worker"),
    FRANCOPHONE("francophone"),
    URBAN_GRADUATE("urban_graduate"),
    PERIURBAN_WORKER("periurban_worker"),
    RURAL("rural"),
    ;

    companion object {
        fun fromSerial(serial: String): BlocId =
            entries.first { it.serial == serial }
    }
}

@Serializable(with = CandidateIdSerial::class)
enum class CandidateId(val serial: String) {
    DEM("dem"),
    REP("rep"),
}

@Serializable(with = EventModeSerial::class)
enum class EventMode(val serial: String) {
    HISTORICAL("historical"),
    PLAUSIBLE("plausible"),
}

@Serializable(with = PartySerial::class)
enum class Party(val serial: String) {
    DEMOCRATIC("Democratic"),
    REPUBLICAN("Republican"),
}

@Serializable(with = RegionSerial::class)
enum class Region(val serial: String) {
    NORTHEAST("Northeast"),
    SOUTH("South"),
    MIDWEST("Midwest"),
    WEST("West"),
    SWING("Swing"),
}

@Serializable(with = GamePhaseSerial::class)
enum class GamePhase(val serial: String) {
    SETUP("setup"),
    INTEL("intel"),
    EVENTS("events"),
    ALLOCATE("allocate"),
    RESULT("result"),
}

@Serializable(with = ActionTypeSerial::class)
enum class ActionType(val serial: String) {
    ADVERTISE("advertise"),
    RALLY("rally"),
    SURROGATE("surrogate"),
    FUNDRAISE("fundraise"),
    GROUND_GAME("ground_game"),
    GOTV("gotv"),
    OPPO_RESEARCH("oppo_research"),
    DEBATE_PREP("debate_prep"),
    POLICY_PREP("policy_prep"),
    ISSUE_PIVOT("issue_pivot"),
}

@Serializable(with = AdModeSerial::class)
enum class AdMode(val serial: String) {
    POSITIVE("positive"),
    CONTRAST("contrast"),
    ISSUE("issue"),
}

@Serializable(with = PartyScopeSerial::class)
enum class PartyScope(val serial: String) {
    NATIONAL("national"),
    SCOTLAND("scotland"),
    WALES("wales"),
    NORTHERN_IRELAND("northern_ireland"),
}

@Serializable(with = AllocationStrategyIdSerial::class)
enum class AllocationStrategyId(val serial: String) {
    WINNER_TAKE_ALL_EV("winner_take_all_ev"),
    REGIONAL_SEATS_CURVE("regional_seats_curve"),
}

@Serializable
data class Issue(
    val id: IssueId,
    val name: String,
    // National salience 0..1. Mutated by events.
    val baseSalience: Double,
    val blurb: String,
)

// All var: debate/policy prep raise these, turn decay relaxes them back.
@Serializable
data class CandidateTraits(
    var charisma: Double,
    var energy: Double,
    var debatePrep: Double,
    var intelligence: Double,
    var policyKnowledge: Double,
    var debatingSkill: Double,
    var fundraisingProwess: Double,
) {
    // String-indexed write for staff/VP trait bonuses (TS: traits[key] = ...).
    operator fun set(name: String, value: Double) = when (name) {
        "charisma" -> charisma = value
        "energy" -> energy = value
        "debatePrep" -> debatePrep = value
        "intelligence" -> intelligence = value
        "policyKnowledge" -> policyKnowledge = value
        "debatingSkill" -> debatingSkill = value
        "fundraisingProwess" -> fundraisingProwess = value
        else -> Unit
    }

    // String-indexed read for trait-gated event choices (TS: traits[trait]).
    operator fun get(name: String): Double = when (name) {
        "charisma" -> charisma
        "energy" -> energy
        "debatePrep" -> debatePrep
        "intelligence" -> intelligence
        "policyKnowledge" -> policyKnowledge
        "debatingSkill" -> debatingSkill
        "fundraisingProwess" -> fundraisingProwess
        else -> 0.0
    }
}

@Serializable
data class Candidate(
    val id: CandidateId,
    val name: String,
    val shortName: String,
    val party: Party,
    var runningMate: String,
    val color: String,
    val traits: CandidateTraits,
    // Immutable snapshot of starting traits; prep buffs relax back toward these.
    var baseTraits: CandidateTraits? = null,
    // Issue stance left(-1) <-> right(+1), keyed by IssueId.serial.
    val issuePositions: MutableMap<String, Double> = mutableMapOf(),
    // Baseline favorability per bloc (-1..+1), keyed by BlocId.serial.
    val baseFavorability: MutableMap<String, Double> = mutableMapOf(),
)

@Serializable
data class RunningMate(
    val id: String,
    val name: String,
    val ticket: CandidateId,
    val blurb: String,
    // The real 2020 running mate (each side's default).
    val historical: Boolean = false,
    val traitBonuses: Map<String, Double> = emptyMap(),
    val favorability: Map<String, Double> = emptyMap(),
    val cashBonus: Double? = null,
    val candidateDayBonus: Double? = null,
)

@Serializable
data class StateBloc(
    val blocId: BlocId,
    val size: Double,
    // 0..1 baseline turnout propensity.
    val turnoutPropensity: Double,
    // Biden-minus-Trump baseline appeal margin (logit space). var: the
    // easy-mode environment shift tilts baselines at setup.
    var baselineMargin: Double,
    // Two-party support per ticket (sums to 1); N-party share under a
    // multiparty system, keyed by PartyId. var: decay/environment refresh it.
    var support: Map<String, Double>,
    // Accumulated campaign margin shift (Biden - Trump). var: the engine
    // mutates blocs in place exactly like the TS engine does.
    var campaignMargin: Double,
    // Enthusiasm multiplier on turnout, around 1.0.
    var enthusiasm: Double,
    // ── Multiparty (UK): present only under an N-party system ──
    val appeal: MutableMap<String, Double>? = null,
    var campaignAppeal: MutableMap<String, Double>? = null,
)

@Serializable
data class StateContest(
    val id: String,
    val name: String,
    val abbr: String,
    val electoralVotes: Int,
    val region: Region,
    // Real 2020 two-party Biden share, used only to solve baselineMargin.
    val prior2020DemShare: Double,
    // Cost multiplier for ads (1.0 = national average).
    val mediaMarketCost: Double,
    val battleground: Boolean,
    val blocs: List<StateBloc>,
    val groundGame: MutableMap<String, Double>,
    // Per-state momentum, -100..+100.
    var momentum: Double,
    // Aggregate units (ME-AL / NE-AL) carry no blocs.
    val aggregateOf: List<String>? = null,
    // ── Multiparty (UK) seat allocation ──
    val seats: Int? = null,
    val baselineSeats: Map<String, Int>? = null,
    val baselineShare: Map<String, Double>? = null,
    val seatElasticity: Double? = null,
)

@Serializable
data class Resources(
    var cash: Double,
    var actions: Int,
    val maxActions: Int,
    var staffCapacity: Int,
    var nationalMomentum: Double,
    var mediaNarrative: Double,
)

@Serializable
data class CampaignAction(
    val type: ActionType,
    val candidate: CandidateId,
    val stateId: String? = null,
    val blocId: BlocId? = null,
    val issueId: IssueId? = null,
    val adMode: AdMode? = null,
    val spend: Double? = null,
    // Day of the 7-day plan (1-7). Legacy `days` no longer drives cost.
    val day: Int? = null,
    val days: Int? = null,
    // For issue_pivot: new position -1..+1.
    val newPosition: Double? = null,
)

@Serializable
data class BlocDelta(
    val blocId: BlocId,
    // Direct shift to campaignMargin for this bloc, nationwide.
    val margin: Double? = null,
    val enthusiasm: Double? = null,
)

@Serializable
data class EventEffect(
    val blocDeltas: List<BlocDelta>? = null,
    val salienceDeltas: Map<String, Double>? = null,
    // National momentum delta for the subject.
    val momentum: Double? = null,
    val cash: Double? = null,
    // Media narrative delta.
    val narrative: Double? = null,
    val favorability: Map<String, Double>? = null,
    // Shifts the answering candidate's own issue positions, clamped [-1, 1].
    val positionShifts: Map<String, Double>? = null,
)

// Gate on a candidate trait threshold (TS: { trait?: keyof CandidateTraits }).
@Serializable
data class TraitRequirement(
    val trait: String? = null,
    val min: Double? = null,
)

@Serializable
data class EventChoice(
    val id: String,
    val text: String,
    // Side-tagged choices are only offered to that ticket; untagged to both.
    val side: CandidateId? = null,
    val requires: TraitRequirement? = null,
    val effects: EventEffect,
    val resultText: String,
)

@Serializable
sealed class EventTrigger {
    // Fires on a specific turn.
    @Serializable
    @SerialName("scheduled")
    data class Scheduled(val turn: Int) : EventTrigger()
    // Drawn from the random pool.
    @Serializable
    @SerialName("stochastic")
    data class Stochastic(val baseWeight: Double) : EventTrigger()
}

@Serializable
data class GameEvent(
    val id: String,
    val title: String,
    val prompt: String,
    // Whose beat this is. "both"/"player"/"opponent" are legacy perspective
    // tags; informational/flavor only.
    val subject: String,
    val trigger: EventTrigger,
    val choices: List<EventChoice>,
    val gate: EventGate? = null,
    val isDebate: Boolean = false,
    val oncePerGame: Boolean = false,
)

@Serializable
data class EventGate(
    val minTurn: Int? = null,
    val maxTurn: Int? = null,
    val requiresLowStamina: Boolean = false,
)

@Serializable
data class PendingEvent(
    val eventId: String,
    val forCandidate: CandidateId,
)

@Serializable
data class CauseEntry(
    val turn: Int,
    val stateId: String? = null,
    val blocId: BlocId? = null,
    // Human-readable, e.g. "Positive ads in PA".
    val cause: String,
    // Biden - Trump contribution.
    val marginDelta: Double,
    val actor: CandidateId? = null,
)

@Serializable
data class TurnRecapItem(
    val label: String,
    val detail: String,
    val marginDelta: Double? = null,
    val stateId: String? = null,
)

@Serializable
data class TurnPoint(
    // 0 = opening baseline, then 1..totalTurns.
    val turn: Int,
    // National poll average, Dem two-party share (0..1).
    val demPoll: Double,
    val demEV: Int,
    val repEV: Int,
    val tossupEV: Int,
    val demMomentum: Double,
    val repMomentum: Double,
    val demCash: Double,
    val repCash: Double,
    // True two-party Dem share per vote-bearing state this week.
    val demShareByState: Map<String, Double> = emptyMap(),
)

@Serializable
data class GameModifiers(
    // Flip this contest's prior to a pure tossup.
    val whatIfState: String? = null,
    // Underdog boost: extra cash + action slots.
    val mirrorMatch: Boolean = false,
    // COVID-era salience in any year.
    val pandemic: Boolean = false,
)

@Serializable
data class GameState(
    // Long: seeds and rng states are uint32 (may exceed Int.MAX).
    val seed: Long,
    var rngState: Long,
    // 0-based; 0 = first playable week.
    var turn: Int,
    val totalTurns: Int,
    val granularity: String,
    var phase: GamePhase,
    val playerCandidate: CandidateId,
    val scenarioId: String? = null,
    val customScenario: String? = null,
    val eventMode: EventMode? = null,
    // Difficulty handicap on the player's own campaigning (1.0 = no edge).
    val playerEdge: Double? = null,
    val candidates: Map<String, Candidate>,
    val issues: Map<String, Issue>,
    // Live national salience, starts from issue.baseSalience.
    val salience: MutableMap<String, Double>,
    val states: List<StateContest>,
    val resources: Map<String, Resources>,
    var pendingEvents: MutableList<PendingEvent>,
    val firedEventIds: MutableList<String>,
    // Actions queued this turn by the player (resolved on endTurn).
    var queuedActions: List<CampaignAction>,
    val causes: MutableList<CauseEntry>,
    var lastRecap: List<TurnRecapItem>,
    val runningMates: Map<String, String>? = null,
    val staff: Map<String, List<String>>? = null,
    var locations: MutableMap<String, String>? = null,
    var adSpend: MutableMap<String, Double>? = null,
    var fundsRaised: MutableMap<String, Double>? = null,
    var debateHistory: MutableList<DebateResult>? = null,
    val modifiers: GameModifiers? = null,
    var timeline: MutableList<TurnPoint>? = null,
    var result: GameResult? = null,
)

@Serializable
data class DebateResult(
    val eventId: String,
    val title: String,
    val scores: Map<String, Double>,
    val choiceText: Map<String, String>,
    val resultText: Map<String, String>,
    // CandidateId.serial or "tie".
    val winner: String,
    val margin: Double,
    val meltdown: Boolean,
    // +winner / -loser.
    val momentumSwing: Double,
)

@Serializable
data class StateResult(
    val stateId: String,
    val electoralVotes: Int,
    // Two-party Dem share.
    val demShare: Double,
    val winner: CandidateId,
    // Winner's two-party margin in points.
    val margin: Double,
)

// One region's seat split under the regional seats curve.
@Serializable
data class SeatResult(
    val contestId: String,
    val name: String,
    val totalSeats: Int,
    val seatsByParty: Map<String, Int>,
    val voteShare: Map<String, Double>,
    // Largest party in the region.
    val winner: PartyId,
)

// Who can form a government after the seats fall.
@Serializable
sealed class Government {
    @Serializable
    @SerialName("majority")
    data class Majority(val party: PartyId, val seats: Int) : Government()
    @Serializable
    @SerialName("minority")
    data class Minority(val party: PartyId, val seats: Int) : Government()
    @Serializable
    @SerialName("coalition")
    data class Coalition(val parties: List<PartyId>, val seats: Int) : Government()
    @Serializable
    @SerialName("confidence_supply")
    data class ConfidenceSupply(val lead: PartyId, val partner: PartyId, val seats: Int) : Government()
    @Serializable
    @SerialName("hung")
    data class Hung(val largest: PartyId) : Government()
}

@Serializable
data class GameResult(
    val electoralVotes: Map<String, Int>,
    // CandidateId.serial or "tie".
    val winner: String,
    // Raw vote totals.
    val popularVote: Map<String, Double>,
    // Two-party share.
    val popularShare: Map<String, Double>,
    val stateResults: List<StateResult>,
    // Biggest swings the player caused.
    val postMortem: List<CauseEntry>,
    // ── Multiparty (UK): present only under an N-party system ──
    val seats: Map<String, Int>? = null,
    val voteShare: Map<String, Double>? = null,
    val seatResults: List<SeatResult>? = null,
    val largestParty: PartyId? = null,
    val hung: Boolean = false,
    val government: Government? = null,
)

// ── Political system (from src/engine/system.ts) ─────────────────────────

@Serializable
data class PartyDef(
    val id: PartyId,
    val name: String,
    val shortName: String,
    val color: String,
    val scope: PartyScope = PartyScope.NATIONAL,
)

@Serializable
data class AllocationStrategy(
    val id: AllocationStrategyId,
    val label: String,
    // What one awardable unit is called ("electoral vote", "seat").
    val unit: String,
)

@Serializable
data class MajorityRule(
    // Total awardable units (538 EV, 650 seats).
    val total: Int,
    // Units to win outright (270, 326).
    val threshold: Int,
    // Effective threshold net of abstentions/Speaker (UK ~= 320).
    val effectiveThreshold: Int? = null,
)

@Serializable
data class PoliticalSystem(
    val id: String,
    val label: String,
    val parties: List<PartyDef>,
    val allocation: AllocationStrategy,
    val majority: MajorityRule,
)
