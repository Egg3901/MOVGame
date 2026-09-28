package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.BLOCS
import com.lakesidegames.electioneer.content.BLOC_IDS
import com.lakesidegames.electioneer.content.ISSUES
import com.lakesidegames.electioneer.content.OPPONENT_OF
import com.lakesidegames.electioneer.content.STAFF_BY_ID
import com.lakesidegames.electioneer.content.STATE_SEEDS
import com.lakesidegames.electioneer.content.ScenarioTicket
import com.lakesidegames.electioneer.content.StateSeed
import com.lakesidegames.electioneer.content.defaultRunningMate
import com.lakesidegames.electioneer.content.getScenario
import com.lakesidegames.electioneer.content.logit
import com.lakesidegames.electioneer.content.resolveRunningMate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

// Game setup: state solver, candidates, resources, scenarios. Port of
// src/engine/setup.ts. sigmoid lives in VoteModel.kt (same package).

// Solve a single additive shift so the turnout-weighted Dem share of a
// state's blocs equals the real target. Bisection, deterministic, no RNG.
private fun solveStateShift(
    blocs: List<Triple<Double, Double, Double>>,
    targetDemShare: Double,
): Double {
    fun weightedShare(shift: Double): Double {
        var num = 0.0
        var den = 0.0
        for ((size, turnout, nationalLogit) in blocs) {
            val w = size * turnout
            num += w * sigmoid(nationalLogit + shift)
            den += w
        }
        return if (den > 0) num / den else 0.5
    }
    var lo = -10.0
    var hi = 10.0
    for (i in 0 until 60) {
        val mid = (lo + hi) / 2
        if (weightedShare(mid) < targetDemShare) lo = mid
        else hi = mid
    }
    return (lo + hi) / 2
}

private fun buildBlocsForState(seed: StateSeed, targetDemShare: Double): List<StateBloc> {
    // Normalize the (national share x profile multiplier) into shares summing to 1.
    val raw = BLOC_IDS.map { id ->
        val mult = seed.profile?.get(id.serial) ?: 1.0
        Triple(id, BLOCS.getValue(id).nationalShare * mult, BLOCS.getValue(id))
    }
    val totalShare = raw.sumOf { it.second }

    val prelim = raw.map { (id, share, arche) ->
        val size = (seed.electorate * share) / totalShare
        Triple(id, size, arche.turnoutPropensity to logit(arche.nationalDemShare))
    }

    val shift = solveStateShift(
        prelim.map { Triple(it.second, it.third.first, it.third.second) },
        targetDemShare,
    )

    return prelim.map { (id, size, turnLogit) ->
        val (turnout, nationalLogit) = turnLogit
        val baselineMargin = nationalLogit + shift
        val demSupport = sigmoid(baselineMargin)
        StateBloc(
            blocId = id,
            size = size,
            turnoutPropensity = turnout,
            baselineMargin = baselineMargin,
            support = mapOf("dem" to demSupport, "rep" to 1 - demSupport),
            campaignMargin = 0.0,
            enthusiasm = 1.0,
        )
    }
}

// Build the contest list. A scenario supplies per-state two-party Dem priors
// (falling back to the 2020 default) and EV overrides for its apportionment.
fun buildStates(
    priors: Map<String, Double>? = null,
    evOverrides: Map<String, Int>? = null,
): List<StateContest> {
    return STATE_SEEDS.map { seed ->
        val isAggregate = seed.electorate <= 0 && seed.aggregateOf != null
        val demShare = priors?.get(seed.id) ?: seed.prior2020DemShare
        val ev = evOverrides?.get(seed.id) ?: seed.ev
        StateContest(
            id = seed.id,
            name = seed.name,
            abbr = seed.abbr,
            electoralVotes = ev,
            region = seed.region,
            prior2020DemShare = demShare,
            mediaMarketCost = seed.mediaCost,
            battleground = seed.battleground,
            blocs = if (isAggregate) emptyList() else buildBlocsForState(seed, demShare),
            groundGame = mutableMapOf("dem" to 0.0, "rep" to 0.0),
            momentum = 0.0,
            aggregateOf = seed.aggregateOf,
        )
    }
}

// Player-side handicap by difficulty.
@Serializable
data class PlayerHandicap(
    val actions: Int,
    val cash: Double,
    val persuasion: Double,
    val environment: Double,
)

private val HANDICAP: Map<String, PlayerHandicap> = mapOf(
    "easy" to PlayerHandicap(actions = 5, cash = 110_000_000.0, persuasion = 1.55, environment = 0.55),
    "normal" to PlayerHandicap(actions = 1, cash = 25_000_000.0, persuasion = 1.15, environment = 0.0),
    "hard" to PlayerHandicap(actions = 0, cash = 0.0, persuasion = 1.0, environment = 0.0),
)

// Tilts every bloc's baseline toward the player by env logits. No-op at 0.
private fun withEnvironment(
    states: List<StateContest>,
    player: CandidateId,
    env: Double,
): List<StateContest> {
    if (env == 0.0) return states
    val sign = if (player == CandidateId.DEM) 1.0 else -1.0
    for (st in states) for (bloc in st.blocs) bloc.baselineMargin += sign * env
    return states
}

private fun startingResources(
    candidate: CandidateId,
    vp: RunningMate,
    baseEnergy: Double,
    handicap: PlayerHandicap = PlayerHandicap(actions = 0, cash = 0.0, persuasion = 1.0, environment = 0.0),
): Resources {
    // Base 7 plus half the candidate's energy (0-10 scale, rounded up), plus
    // any energetic-VP bonus, plus the player handicap.
    val maxActions = 7 + ceil(baseEnergy / 20).toInt() + (vp.candidateDayBonus?.toInt() ?: 0) + handicap.actions
    return Resources(
        // The Democratic ticket enters with a cash edge. A fundraiser VP adds
        // a one-time war-chest bump.
        cash = (if (candidate == CandidateId.DEM) 220_000_000.0 else 180_000_000.0) +
            (vp.cashBonus ?: 0.0) + handicap.cash,
        actions = maxActions,
        maxActions = maxActions,
        staffCapacity = 6,
        nationalMomentum = 0.0,
        mediaNarrative = 0.0,
    )
}

// Fold a chosen running mate's bonuses into the ticket. Mutates the clone.
private fun applyRunningMate(cand: Candidate, vp: RunningMate) {
    cand.runningMate = vp.name
    if (vp.traitBonuses.isNotEmpty()) {
        for ((k, v) in vp.traitBonuses) {
            cand.traits[k] = max(0.0, min(100.0, cand.traits[k] + v))
        }
    }
    if (vp.favorability.isNotEmpty()) {
        for ((b, v) in vp.favorability) {
            cand.baseFavorability[b] = (cand.baseFavorability[b] ?: 0.0) + v
        }
    }
}

// Build a slot candidate from a scenario ticket.
private fun buildCandidate(slot: CandidateId, t: ScenarioTicket): Candidate {
    return Candidate(
        id = slot,
        name = t.name,
        shortName = t.shortName,
        party = t.party,
        runningMate = defaultRunningMate(t.runningMates).name,
        color = t.color,
        traits = t.traits.copy(),
        baseTraits = t.traits.copy(),
        issuePositions = t.issuePositions.toMutableMap(),
        baseFavorability = t.baseFavorability.toMutableMap(),
    )
}

data class NewGameOptions(
    val seed: Any? = null, // Long, Int, or String
    val playerCandidate: CandidateId = CandidateId.DEM,
    val totalTurns: Int = 9,
    val granularity: String = "week",
    // Chosen running mate id for the player's ticket.
    val runningMate: String? = null,
    // Election scenario id; defaults to "2020".
    val scenario: String? = null,
    // Event source; defaults to historical.
    val eventMode: EventMode = EventMode.HISTORICAL,
    // Difficulty; defaults to normal.
    val difficulty: String = "normal",
    // Hired staffer ids for the player's ticket (up to MAX_STAFF).
    val staff: List<String> = emptyList(),
    // Free replayability modifiers.
    val modifiers: GameModifiers? = null,
)

// Builds a fresh, fully-initialized game state. Deterministic given the seed.
fun createGame(opts: NewGameOptions = NewGameOptions()): GameState {
    val seedInput = opts.seed
    val seed: Long = when (seedInput) {
        is String -> Rng.hashSeed(seedInput)
        is Int -> seedInput.toLong() and 0xFFFFFFFFL
        is Long -> seedInput and 0xFFFFFFFFL
        else -> 0L // no platform clock in common code: explicit seeds only
    }

    val scenario = getScenario(opts.scenario)

    // National issue salience: scenario values where given, else base.
    val salience = mutableMapOf<String, Double>()
    for (id in IssueId.entries) {
        salience[id.serial] = scenario.issueSalience?.get(id.serial)
            ?: ISSUES.getValue(id.serial).baseSalience
    }
    val player = opts.playerCandidate
    val baseHandicap = HANDICAP.getValue(opts.difficulty)
    // Mirror Match: underdog boost regardless of difficulty.
    val handicap = if (opts.modifiers?.mirrorMatch == true) {
        baseHandicap.copy(
            cash = baseHandicap.cash + 40_000_000,
            actions = baseHandicap.actions + 2,
        )
    } else {
        baseHandicap
    }
    val dem = buildCandidate(CandidateId.DEM, scenario.dem)
    val rep = buildCandidate(CandidateId.REP, scenario.rep)
    val candidates = mutableMapOf(
        CandidateId.DEM.serial to dem,
        CandidateId.REP.serial to rep,
    )
    val opp = OPPONENT_OF.getValue(player)
    val playerRoster = if (player == CandidateId.DEM) scenario.dem.runningMates else scenario.rep.runningMates
    val oppRoster = if (opp == CandidateId.DEM) scenario.dem.runningMates else scenario.rep.runningMates
    val playerVp = resolveRunningMate(playerRoster, opts.runningMate)
    val oppVp = defaultRunningMate(oppRoster)
    applyRunningMate(candidates.getValue(player.serial), playerVp)
    applyRunningMate(candidates.getValue(opp.serial), oppVp)

    // Staff: instant effects fold in at creation; in-flight multipliers are
    // read from game.staff during play.
    val hires = opts.staff.filter { STAFF_BY_ID.containsKey(it) }.take(3)
    var staffActionBonus = 0
    for (id in hires) {
        val def = STAFF_BY_ID.getValue(id)
        staffActionBonus += def.effects.maxActions
        if (def.effects.traitBonuses.isNotEmpty()) {
            val pc = candidates.getValue(player.serial)
            for ((k, v) in def.effects.traitBonuses) {
                pc.traits[k] = max(0.0, min(100.0, pc.traits[k] + v))
            }
            // Permanent for the run: fold into the decay baseline.
            pc.baseTraits = pc.traits.copy()
        }
    }

    // What If: flip a contest's prior to a pure tossup. Pandemic: COVID salience.
    val priors = (scenario.statePriors ?: emptyMap()).toMutableMap()
    if (opts.modifiers?.whatIfState != null) priors[opts.modifiers.whatIfState] = 0.5
    if (opts.modifiers?.pandemic == true) {
        salience["covid_response"] = max(salience["covid_response"] ?: 0.0, 0.85)
    }

    val demVp = if (player == CandidateId.DEM) playerVp else oppVp
    val repVp = if (player == CandidateId.REP) playerVp else oppVp
    val demRes = startingResources(
        CandidateId.DEM, demVp, scenario.dem.traits.energy,
        if (player == CandidateId.DEM) handicap else PlayerHandicap(0, 0.0, 1.0, 0.0),
    )
    val repRes = startingResources(
        CandidateId.REP, repVp, scenario.rep.traits.energy,
        if (player == CandidateId.REP) handicap else PlayerHandicap(0, 0.0, 1.0, 0.0),
    )
    val resources = mutableMapOf(CandidateId.DEM.serial to demRes, CandidateId.REP.serial to repRes)
    val playerRes = resources.getValue(player.serial)
    // maxActions is val: rebuild the player's resources with the staff bonus.
    resources[player.serial] = playerRes.copy(
        maxActions = playerRes.maxActions + staffActionBonus,
        actions = playerRes.actions + staffActionBonus,
    )

    return GameState(
        seed = seed,
        rngState = seed,
        turn = 0,
        totalTurns = opts.totalTurns,
        granularity = opts.granularity,
        phase = GamePhase.INTEL,
        playerCandidate = player,
        scenarioId = scenario.id,
        eventMode = opts.eventMode,
        playerEdge = handicap.persuasion,
        locations = mutableMapOf(),
        candidates = candidates,
        issues = ISSUES.toMap(),
        salience = salience,
        states = withEnvironment(buildStates(priors, scenario.evOverrides), player, handicap.environment),
        resources = resources,
        pendingEvents = mutableListOf(),
        firedEventIds = mutableListOf(),
        queuedActions = emptyList(),
        causes = mutableListOf(),
        lastRecap = emptyList(),
        runningMates = mapOf(
            CandidateId.DEM.serial to (if (player == CandidateId.DEM) playerVp else oppVp).id,
            CandidateId.REP.serial to (if (player == CandidateId.REP) playerVp else oppVp).id,
        ),
        staff = mapOf(player.serial to hires),
        adSpend = mutableMapOf("dem" to 0.0, "rep" to 0.0),
        debateHistory = mutableListOf(),
        modifiers = opts.modifiers,
    )
}

// FNV-1a string seed (same algorithm as Rng.hashSeed; exported separately in
// TS for daily-challenge detection).
fun hashStr(s: String): Long = Rng.hashSeed(s)
