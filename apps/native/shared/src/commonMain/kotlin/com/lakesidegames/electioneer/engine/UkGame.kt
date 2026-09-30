package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.PARTY_BY_ID
import com.lakesidegames.electioneer.content.UK_ABSTAINING
import com.lakesidegames.electioneer.content.UK_ELECTIONS
import com.lakesidegames.electioneer.content.UK_ELECTION_EVENTS
import com.lakesidegames.electioneer.content.UK_EVENTS
import com.lakesidegames.electioneer.content.UK_EVENT_CHANCE
import com.lakesidegames.electioneer.content.UK_PLAYABLE
import com.lakesidegames.electioneer.content.UkElectionData
import com.lakesidegames.electioneer.content.UkEvent
import com.lakesidegames.electioneer.content.UkLeader
import com.lakesidegames.electioneer.content.headlineFor
import com.lakesidegames.electioneer.content.leaderFor
import com.lakesidegames.electioneer.content.majorityForElection
import com.lakesidegames.electioneer.content.pickTarget
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

// The UK game: state, setup, actions, turn loop, and result. Port of
// src/engine/ukGame.ts. Pure + deterministic: same seed ⇒ same game.

typealias Difficulty = String

@Serializable
data class UkResources(
    // £ millions on hand.
    var funds: Double,
    override var actions: Int,
    override var maxActions: Int,
    override var momentum: Double,
) : MpResources

@Serializable(with = UkActionTypeSerial::class)
enum class UkActionType(val serial: String) {
    BROADCAST("broadcast"),
    RALLY("rally"),
    SURROGATE("surrogate"),
    FUNDRAISE("fundraise"),
    GROUND_GAME("ground_game"),
    GOTV("gotv"),
    CANVASS("canvass"),
    OPPO_RESEARCH("oppo_research"),
    DEBATE_PREP("debate_prep"),
    POLICY_PREP("policy_prep"),
    ISSUE_PIVOT("issue_pivot"),
}

@Serializable(with = UkAdModeSerial::class)
enum class UkAdMode(val serial: String) {
    POSITIVE("positive"),
    CONTRAST("contrast"),
    ISSUE("issue"),
}

@Serializable
data class UkAction(
    val type: UkActionType,
    val party: PartyId,
    // Region-targeted actions; omitted ⇒ national.
    val regionId: String? = null,
    // Contrast broadcast / oppo research target.
    val targetParty: PartyId? = null,
    val mode: UkAdMode? = null,
    // Issue broadcast / issue pivot.
    val issueId: String? = null,
    // £M for broadcast.
    val spend: Double? = null,
    // 1..7 planner slot, applied in stable day order.
    val day: Int? = null,
)

@Serializable
data class UkResult(
    val seats: Map<PartyId, Int>,
    val voteShare: Map<PartyId, Double>,
    val seatResults: List<SeatResult>,
    val largestParty: PartyId,
    val hung: Boolean,
    val government: Government,
    val postMortem: List<CauseEntry>,
)

@Serializable
data class UkPendingEvent(
    val eventId: String,
    // Resolved target party (always the player when pending).
    val targetParty: PartyId,
)

@Serializable
data class NewsItem(
    val turn: Int,
    val text: String,
)

@Serializable
data class UkGameState(
    val systemId: String = "UK",
    val seed: Long,
    var rngState: Long,
    var turn: Int,
    val totalTurns: Int,
    var phase: String,
    val electionId: String,
    val label: String,
    val tagline: String,
    val playerParty: PartyId,
    // Active parties this election.
    val parties: List<PartyId>,
    val leaders: Map<PartyId, UkLeader>,
    val salience: MutableMap<String, Double>,
    val regions: List<StateContest>,
    val resources: Map<PartyId, UkResources>,
    val causes: MutableList<CauseEntry>,
    var queuedActions: List<UkAction>,
    val abstaining: List<PartyId>,
    // Rolling campaign-news log (latest first) from fired events.
    var news: List<NewsItem>,
    // Election-deck event ids that already fired (never repeat in a game).
    var firedEvents: MutableList<String>? = null,
    // Underdog handicap ("normal" = identity).
    val difficulty: Difficulty? = null,
    // Chamber size for THIS election's boundary set.
    val majority: MajorityRule? = null,
    // Week-in-review lines after each turn.
    var lastRecap: List<RecapItem>,
    // Player-choice event waiting on a decision (blocks End Week).
    var pendingEvent: UkPendingEvent? = null,
    // Player-authored game: casual only, never posts a score.
    val custom: Boolean? = null,
    var result: UkResult? = null,
)

// Winning post for this election's boundary set (650/326 default).
fun majorityForUk(g: UkGameState): MajorityRule {
    val m = g.majority ?: majorityForElection(g.electionId).let {
        MajorityRule(total = it.total, threshold = it.threshold)
    }
    return m
}

// Active parties = the union of parties appearing in any region's result.
private fun activeParties(election: UkElectionData): List<PartyId> {
    val set = linkedSetOf<PartyId>()
    for (r in election.regions.values) {
        for (p in r.v.keys) set.add(p)
        for (p in r.s.keys) set.add(p)
    }
    // Stable, sensible order: playable majors first, then the rest.
    val order = UK_PLAYABLE + listOf("dup", "sf", "uup", "sdlp", "apni", "oth")
    return set.sortedWith(compareBy { order.indexOf(it) })
}

// The human-playable parties that actually contest a given election.
fun playablePartiesIn(electionId: String): List<PartyId> {
    val election = UK_ELECTIONS[electionId] ?: return UK_PLAYABLE.toList()
    val active = activeParties(election).toSet()
    return UK_PLAYABLE.filter { active.contains(it) }
}

@Serializable
data class UkHandicap(
    val funds: Double,
    val actions: Int,
    val persuasion: Double,
    val aiPersuasion: Double,
    val environment: Double,
)

val UK_HANDICAP: Map<String, UkHandicap> = mapOf(
    "easy" to UkHandicap(funds = 6.0, actions = 2, persuasion = 1.5, aiPersuasion = 0.5, environment = 0.9),
    "normal" to UkHandicap(funds = 0.0, actions = 0, persuasion = 1.0, aiPersuasion = 1.0, environment = 0.0),
    "hard" to UkHandicap(funds = 0.0, actions = 0, persuasion = 0.85, aiPersuasion = 1.1, environment = 0.0),
)

// One-time favorable-climate tilt toward the player, then refresh live support.
private fun applyUkEnvironment(regions: List<StateContest>, player: PartyId, env: Double) {
    if (env == 0.0) return
    for (region in regions) {
        for (bloc in region.blocs) {
            val appeal = bloc.appeal ?: continue
            appeal[player] = (appeal[player] ?: 0.0) + env
            bloc.support = blocPartyShares(bloc)
        }
    }
}

data class NewUkGameOptions(
    val seed: Any? = null, // Long, Int, or String
    val election: String? = null,
    val playerParty: PartyId? = null,
    val totalTurns: Int = 6,
    val difficulty: Difficulty? = null,
)

fun createUkGame(opts: NewUkGameOptions = NewUkGameOptions()): UkGameState {
    val election = UK_ELECTIONS[opts.election ?: "2024"] ?: UK_ELECTIONS.getValue("2024")
    val seed: Long = when (val s = opts.seed) {
        is String -> Rng.hashSeed(s)
        is Int -> s.toLong() and 0xFFFFFFFFL
        is Long -> s and 0xFFFFFFFFL
        else -> 0L // no platform clock in common code: explicit seeds only
    }
    val parties = activeParties(election)
    // Guard: a party not contesting this election can't be led.
    val playerParty = if (opts.playerParty != null && parties.contains(opts.playerParty)) {
        opts.playerParty
    } else {
        parties.find { UK_PLAYABLE.contains(it) } ?: parties[0]
    }

    val difficulty = opts.difficulty ?: "normal"
    val hc = UK_HANDICAP.getValue(difficulty)

    val leaders = linkedMapOf<PartyId, UkLeader>()
    for (p in parties) leaders[p] = leaderFor(election.id, p, p.uppercase())

    val resources = linkedMapOf<PartyId, UkResources>()
    for (p in parties) {
        val machine = leaders.getValue(p).machine
        val bonusF = if (p == playerParty) hc.funds else 0.0
        val bonusA = if (p == playerParty) hc.actions else 0
        val slots = 5 + round(leaders.getValue(p).energy / 40).toInt() + bonusA
        resources[p] = UkResources(
            funds = 4 + machine / 10 + bonusF,
            actions = slots,
            maxActions = slots,
            momentum = 0.0,
        )
    }

    val regions = buildUkRegions(election)
    applyUkEnvironment(regions, playerParty, hc.environment)

    return UkGameState(
        seed = seed,
        rngState = seed,
        turn = 0,
        totalTurns = opts.totalTurns,
        phase = "campaign",
        electionId = election.id,
        label = election.label,
        tagline = election.tagline,
        playerParty = playerParty,
        parties = parties,
        leaders = leaders,
        salience = election.salience.toMutableMap(),
        regions = regions,
        resources = resources,
        causes = mutableListOf(),
        queuedActions = emptyList(),
        abstaining = UK_ABSTAINING.toList(),
        news = emptyList(),
        firedEvents = mutableListOf(),
        difficulty = difficulty,
        majority = election.majority?.let { MajorityRule(total = it.total, threshold = it.threshold) }
            ?: majorityForElection(election.id).let { MajorityRule(total = it.total, threshold = it.threshold) },
        lastRecap = emptyList(),
        pendingEvent = null,
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────
private fun findRegion(g: UkGameState, id: String?): StateContest? =
    g.regions.find { it.id == id }

// Diminishing returns on how far a party's appeal has already been pushed in
// a region (mirrors the U.S. saturation).
private fun saturate(bloc: StateBloc, party: PartyId, raw: Double): Double {
    val acc = abs(bloc.campaignAppeal?.get(party) ?: 0.0)
    val room = max(0.15, 1 - acc / 0.8)
    return raw * room
}

private fun addAppeal(g: UkGameState, region: StateContest, party: PartyId, cause: String, delta: Double) {
    // Difficulty scales campaigning: player effort by `persuasion`, rivals by
    // `aiPersuasion`. Both 1.0 on normal (identity).
    val hc = UK_HANDICAP[g.difficulty ?: "normal"] ?: UK_HANDICAP.getValue("normal")
    val scaled = delta * (if (party == g.playerParty) hc.persuasion else hc.aiPersuasion)
    for (bloc in region.blocs) {
        val camp = bloc.campaignAppeal ?: mutableMapOf<String, Double>().also { bloc.campaignAppeal = it }
        camp[party] = (camp[party] ?: 0.0) + saturate(bloc, party, scaled)
    }
    g.causes.add(CauseEntry(turn = g.turn, stateId = region.id, cause = cause, marginDelta = scaled))
}

// Regions where a party actually stands: absent from the baseline = not on
// the ballot there.
private fun standsIn(g: UkGameState, party: PartyId): List<StateContest> =
    g.regions.filter { it.baselineShare?.get(party) != null }

// The strongest rival to `party` in a region.
private fun topRivalIn(region: StateContest, party: PartyId): PartyId? {
    val shareByParty = tallyRegion(region).shareByParty
    return shareByParty.keys
        .filter { it != party && (shareByParty[it] ?: 0.0) > 0 }
        .sortedWith(compareByDescending { shareByParty[it] ?: 0.0 })
        .firstOrNull()
}

private fun applyUkAction(g: UkGameState, a: UkAction, rng: Rng,
                               planMultiplier: Double = 1.0, planBonuses: List<PlanBonus> = emptyList()) {
    val res = g.resources.getValue(a.party)
    if (res.actions < 1) return
    val leader = g.leaders.getValue(a.party)
    val skill = 0.85 + leader.charisma / 400
    val compSkill = 0.85 + leader.competence / 400
    val region = findRegion(g, a.regionId)
    val causeCount = g.causes.size
    fun spend(need: Double): Boolean {
        if (res.funds < need) return false
        res.funds -= need
        return true
    }

    when (a.type) {
        UkActionType.CANVASS -> {
            if (region == null) return
            res.actions -= 1
            addAppeal(g, region, a.party, "${leader.name} doorstep campaign in ${region.abbr}", 0.05 * skill * (0.9 + rng.next() * 0.2))
        }
        UkActionType.GROUND_GAME -> {
            if (region == null) return
            if (!spend(1.5)) return
            res.actions -= 1
            region.momentum = clamp(region.momentum + 2, -100.0, 100.0)
            addAppeal(g, region, a.party, "${leader.name} field operation in ${region.abbr}", 0.075 * (0.85 + leader.machine / 300))
        }
        UkActionType.GOTV -> {
            if (region == null) return
            if (!spend(1.0)) return
            res.actions -= 1
            addAppeal(g, region, a.party, "${leader.name} GOTV drive in ${region.abbr}", 0.06 * (0.85 + leader.energy / 300) * planMultiplier)
        }
        UkActionType.RALLY -> {
            if (region == null) return
            res.actions -= 1
            region.momentum = clamp(region.momentum + 6, -100.0, 100.0)
            res.momentum = clamp(res.momentum + 2, -100.0, 100.0)
            addAppeal(g, region, a.party, "${leader.name} rally in ${region.abbr}", 0.045 * skill)
        }
        UkActionType.SURROGATE -> {
            if (region == null) return
            if (!spend(0.25)) return
            res.actions -= 1
            addAppeal(g, region, a.party, "Surrogate stumps for ${leader.name} in ${region.abbr}", 0.035 * (0.85 + leader.energy / 300))
        }
        UkActionType.BROADCAST -> {
            val cost = max(0.5, a.spend ?: 1.5)
            if (!spend(cost)) return
            res.actions -= 1
            val mode = a.mode ?: UkAdMode.POSITIVE
            val targets = if (region != null) listOf(region) else standsIn(g, a.party)
            val power = 0.02 * sqrt(cost / 1.5) * (0.85 + leader.machine / 300) * planMultiplier
            val per = if (region != null) power else power * 0.8
            if (mode == UkAdMode.ISSUE && a.issueId != null) {
                g.salience[a.issueId] = clamp((g.salience[a.issueId] ?: 0.4) + 0.05 * planMultiplier, 0.0, 1.0)
                for (t in targets) addAppeal(g, t, a.party, "${leader.name} issue broadcast", per * 0.7)
            } else if (mode == UkAdMode.CONTRAST) {
                val scope = if (region != null) listOf(region) else targets
                for (t in scope) {
                    val foe = a.targetParty ?: topRivalIn(t, a.party)
                    if (foe != null) addAppeal(g, t, foe, "${leader.name} contrast broadcast", -per * 0.9)
                }
            } else {
                for (t in targets) addAppeal(g, t, a.party, "${leader.name} broadcast", per)
            }
            res.momentum = clamp(res.momentum + 1, -100.0, 100.0)
        }
        UkActionType.OPPO_RESEARCH -> {
            if (!spend(2.0)) return
            res.actions -= 1
            val scope = if (region != null) listOf(region) else standsIn(g, a.party)
            for (t in scope) {
                val foe = a.targetParty ?: topRivalIn(t, a.party)
                if (foe != null) addAppeal(g, t, foe, "${leader.name}'s team releases dossier on ${foe.uppercase()}", -0.05 * compSkill)
            }
        }
        UkActionType.DEBATE_PREP -> {
            res.actions -= 1
            for (t in standsIn(g, a.party)) addAppeal(g, t, a.party, "${leader.name} debate performance", 0.012 * compSkill)
            res.momentum = clamp(res.momentum + 4, -100.0, 100.0)
        }
        UkActionType.POLICY_PREP -> {
            res.actions -= 1
            for (t in standsIn(g, a.party)) addAppeal(g, t, a.party, "${leader.name} manifesto launch", 0.014 * compSkill)
            res.momentum = clamp(res.momentum + 2, -100.0, 100.0)
        }
        UkActionType.ISSUE_PIVOT -> {
            res.actions -= 1
            if (a.issueId != null) g.salience[a.issueId] = clamp((g.salience[a.issueId] ?: 0.4) + 0.08 * planMultiplier, 0.0, 1.0)
            for (t in standsIn(g, a.party)) addAppeal(g, t, a.party, "${leader.name} pivots the campaign", 0.008 * skill * planMultiplier)
        }
        UkActionType.FUNDRAISE -> {
            res.actions -= 1
            val haul = (1.5 + leader.machine / 50) * (0.85 + rng.next() * 0.3)
            res.funds += haul
            g.causes.add(CauseEntry(turn = g.turn, cause = "${leader.name} fundraising (+£${toFixed1(haul)}M)", marginDelta = 0.0))
        }
    }
    if (g.causes.size > causeCount) {
        for (bonus in planBonuses) g.causes.add(bonus.cause(g.turn, a.regionId))
    }
}

// ── Multiparty AI (difficulty-tiered; shared with the balance harness) ─────
private fun planUkAiActions(g: UkGameState, party: PartyId, rng: Rng): List<UkAction> {
    val res = g.resources.getValue(party)
    val difficulty = g.difficulty ?: "normal"
    return planMultipartyAi(
        MpView(
            regions = g.regions,
            turn = g.turn,
            totalTurns = g.totalTurns,
            funds = res.funds,
            actions = res.actions,
            majority = majorityForUk(g),
            abstaining = g.abstaining,
            compatible = ::ukCompatible,
        ),
        party,
        difficulty,
        rng,
    ).map { like ->
        UkAction(
            type = UkActionType.entries.first { it.serial == like.type },
            party = like.party,
            regionId = like.regionId,
            targetParty = like.targetParty,
            mode = like.mode?.let { m -> UkAdMode.entries.first { it.serial == m } },
            issueId = null,
            spend = like.spend,
        )
    }
}

private fun eventById(id: String, electionId: String): UkEvent? {
    val deck = UK_ELECTION_EVENTS[electionId] ?: emptyList()
    return deck.find { it.id == id } ?: UK_EVENTS.find { it.id == id }
}

private fun topRivalParty(g: UkGameState, party: PartyId): PartyId? {
    val proj = computeSeatsResult(g.regions, majorityForUk(g), g.abstaining)
    return proj.seats.keys
        .filter { it != party }
        .sortedWith(compareByDescending { proj.seats[it] ?: 0 })
        .firstOrNull()
}

private fun applyAppealNational(g: UkGameState, target: PartyId, appeal: Double, cause: String) {
    for (region in g.regions) {
        if (region.baselineShare?.get(target) == null) continue
        for (bloc in region.blocs) {
            val camp = bloc.campaignAppeal ?: mutableMapOf<String, Double>().also { bloc.campaignAppeal = it }
            camp[target] = (camp[target] ?: 0.0) + appeal
        }
    }
    g.causes.add(CauseEntry(turn = g.turn, cause = cause, marginDelta = appeal))
}

// Apply one event's default (non-choice) effects. Fixed party target when pinned.
private fun applyUkEventEffects(g: UkGameState, ev: UkEvent, target: PartyId) {
    if (ev.appeal != null) applyAppealNational(g, target, ev.appeal, "${ev.id}: ${target.uppercase()}")
    if (ev.momentum != null && g.resources[target] != null) {
        val res = g.resources.getValue(target)
        res.momentum = clamp(res.momentum + ev.momentum, -100.0, 100.0)
    }
    val short = PARTY_BY_ID[target]?.shortName ?: target.uppercase()
    g.news = (listOf(NewsItem(turn = g.turn, text = headlineFor(ev, short))) + g.news).take(12)
}

private fun resolveUkEventTarget(g: UkGameState, ev: UkEvent, rng: Rng): PartyId? {
    val proj = computeSeatsResult(g.regions, majorityForUk(g), g.abstaining)
    return if (ev.party != null && g.parties.contains(ev.party)) {
        ev.party
    } else {
        pickTarget(ev.role, g.playerParty, proj.largestParty, g.parties) { xs -> rng.pick(xs) }
    }
}

// Apply or queue one event. Choice events targeting the player become pending.
private fun applyUkEvent(g: UkGameState, ev: UkEvent, rng: Rng) {
    val target = resolveUkEventTarget(g, ev, rng) ?: return

    if (ev.choices != null && ev.choices.isNotEmpty() && target == g.playerParty && g.pendingEvent == null) {
        g.pendingEvent = UkPendingEvent(eventId = ev.id, targetParty = target)
        val short = PARTY_BY_ID[target]?.shortName ?: target.uppercase()
        g.news = (listOf(NewsItem(turn = g.turn, text = headlineFor(ev, short))) + g.news).take(12)
        return
    }
    applyUkEventEffects(g, ev, target)
}

// Resolve a pending player-choice event. Pure: returns a new game state.
fun resolveUkPlayerEvent(g: UkGameState, choiceId: String): UkGameState {
    val next = g.deepCopyUk()
    val pending = next.pendingEvent ?: return next
    val ev = eventById(pending.eventId, next.electionId)
    next.pendingEvent = null
    if (ev?.choices == null) return next
    val choice = ev.choices.find { it.id == choiceId } ?: ev.choices[0]
    val target = pending.targetParty
    if (choice.appeal != null) applyAppealNational(next, target, choice.appeal, "${ev.id}: ${choice.text}")
    if (choice.momentum != null && next.resources[target] != null) {
        val res = next.resources.getValue(target)
        res.momentum = clamp(res.momentum + choice.momentum, -100.0, 100.0)
    }
    if (choice.rivalAppeal != null) {
        val rival = topRivalParty(next, target)
        if (rival != null) applyAppealNational(next, rival, choice.rivalAppeal, "${ev.id}: hit on ${rival.uppercase()}")
    }
    next.lastRecap = (
        listOf(
            RecapItem(
                label = ev.headline.replace("{party}", PARTY_BY_ID[target]?.shortName ?: target),
                detail = choice.resultText,
                marginDelta = choice.appeal,
            ),
        ) + (next.lastRecap)
        ).take(12)
    // A decision that landed on the final week still ends the campaign.
    if (next.turn >= next.totalTurns) {
        next.phase = "result"
        next.result = computeUkResult(next)
    }
    return next
}

// Fire this week's events: scheduled story beats first, then at most one draw.
private fun fireUkEvent(g: UkGameState, rng: Rng) {
    val firedList = g.firedEvents ?: mutableListOf<String>().also { g.firedEvents = it }
    val fired = firedList.toSet()
    val deck = UK_ELECTION_EVENTS[g.electionId] ?: emptyList()

    for (ev in deck) {
        if (ev.turn == null || ev.turn != g.turn || fired.contains(ev.id)) continue
        applyUkEvent(g, ev, rng)
        firedList.add(ev.id)
    }

    // Skip the random draw if a player decision is already pending this week.
    if (g.pendingEvent != null) return

    val pool = deck.filter { it.turn == null && !firedList.contains(it.id) } + UK_EVENTS
    if (pool.isEmpty() || !rng.chance(UK_EVENT_CHANCE)) return
    val ev = rng.weightedPick(pool, pool.map { it.weight })
    applyUkEvent(g, ev, rng)
    if (deck.any { it.id == ev.id }) firedList.add(ev.id)
}

private fun buildUkRecap(g: UkGameState, turn: Int, seatsBefore: Int): List<RecapItem> {
    val seatsAfter = computeUkResult(g).seats[g.playerParty] ?: 0
    return buildCauseRecap(
        g.causes,
        turn,
        RecapItem(
            label = "Projected seats",
            detail = "${PARTY_BY_ID[g.playerParty]?.shortName ?: g.playerParty} $seatsAfter (was $seatsBefore)",
            marginDelta = (seatsAfter - seatsBefore).toDouble(),
        ),
    )
}

private fun UkGameState.deepCopyUk(): UkGameState = copy(
    leaders = leaders.toMap(),
    salience = salience.toMutableMap(),
    regions = regions.map { r ->
        r.copy(
            blocs = r.blocs.map { b ->
                b.copy(
                    support = b.support.toMap(),
                    appeal = b.appeal?.toMutableMap(),
                    campaignAppeal = b.campaignAppeal?.toMutableMap(),
                )
            },
            groundGame = r.groundGame.toMutableMap(),
            baselineSeats = r.baselineSeats?.toMap(),
            baselineShare = r.baselineShare?.toMap(),
        )
    },
    resources = resources.mapValues { (_, r) -> r.copy() }.toMutableMap(),
    causes = causes.map { it.copy() }.toMutableList(),
    queuedActions = queuedActions.map { it.copy() },
    abstaining = abstaining.toList(),
    news = news.map { it.copy() },
    firedEvents = firedEvents?.toMutableList(),
    lastRecap = lastRecap.map { it.copy() },
)

// ── Turn loop ──────────────────────────────────────────────────────────────
@Serializable
data class UkAdvanceOptions(
    // Suppress the AI parties (calibration anchor: neutral play reproduces history).
    val disableAi: Boolean = false,
    // Auto-pick the first choice on pending player events (bots / tests).
    val autoResolvePlayerEvents: Boolean = true,
)

fun ukAdvanceTurn(g: UkGameState, opts: UkAdvanceOptions = UkAdvanceOptions()): UkGameState {
    var next: UkGameState = g.deepCopyUk()
    val gate = resolvePendingChoiceGate(
        next,
        opts.autoResolvePlayerEvents,
        { it.pendingEvent != null },
        { game, choiceId -> resolveUkPlayerEvent(game, choiceId) },
        { game ->
            val ev = game.pendingEvent?.let { eventById(it.eventId, game.electionId) }
            ev?.choices?.firstOrNull()?.id
        },
    )
    if (gate.blocked) return g
    next = gate.game

    // Pending resolution on the final week may have already closed the campaign.
    if (next.phase == "result") return next
    if (next.turn >= next.totalTurns) {
        next.phase = "result"
        next.result = computeUkResult(next)
        return next
    }

    val rng = Rng.createRng(next.rngState)
    val seatsBefore = computeUkResult(next).seats[next.playerParty] ?: 0
    val turn = next.turn

    // 1. Player's queued actions.
    resolvePlan(next.queuedActions, { it.planMove() }, { next.causes.size }) { action, mult, bonuses ->
        applyUkAction(next, action, rng, mult, bonuses)
    }
    next.queuedActions = emptyList()

    // 2. AI for every other active major party.
    if (!opts.disableAi) {
        for (p in next.parties) {
            if (p == next.playerParty || !UK_PLAYABLE.contains(p)) continue
            for (a in planUkAiActions(next, p, rng)) applyUkAction(next, a, rng)
        }
    }

    // 3. A campaign event may fire. Suppressed under the calibration anchor.
    if (!opts.disableAi) fireUkEvent(next, rng)

    // 4. Momentum decays; refresh action pools and live support.
    decayMultipartyTurn(next.resources, next.regions, next.parties)

    next.lastRecap = buildUkRecap(next, turn, seatsBefore)
    next.rngState = rng.state()
    // Advance the turn counter and close the campaign when out of weeks.
    // A leftover pending decision keeps the phase at campaign for the UI.
    next.turn += 1
    if (next.pendingEvent != null) return next
    if (next.turn >= next.totalTurns) {
        next.phase = "result"
        next.result = computeUkResult(next)
    }
    return next
}

// Coalition compatibility: the two main UK rivals never govern together.
fun ukCompatible(lead: PartyId, partner: PartyId): Boolean {
    val rivals = setOf("con", "lab")
    if (rivals.contains(lead) && rivals.contains(partner)) return false
    if ((lead == "con" && partner == "snp") || (lead == "snp" && partner == "con")) return false
    return true
}

fun computeUkResult(g: UkGameState): UkResult {
    val r = computeSeatsResult(g.regions, majorityForUk(g), g.abstaining, ::ukCompatible)
    // The post-mortem shows only the player's own persuasive actions.
    val playerName = g.leaders[g.playerParty]?.name ?: ""
    val postMortem = g.causes
        .filter { c -> playerName.isNotEmpty() && c.marginDelta != 0.0 && c.cause.contains(playerName) }
        .sortedWith(compareByDescending { abs(it.marginDelta) })
        .take(8)
    return UkResult(
        seats = r.seats,
        voteShare = r.voteShare,
        seatResults = r.seatResults,
        largestParty = r.largestParty,
        hung = r.hung,
        government = r.government,
        postMortem = postMortem,
    )
}

// Live national projection during play (for the seat bar / map).
fun projectUk(g: UkGameState): UkResult = computeUkResult(g)

// "What-if" projection: apply the player's currently-queued actions to a clone
// (no AI, no events, no turn advance) and project. Pure: never mutates live.
fun projectUkPreview(g: UkGameState): UkResult {
    if (g.queuedActions.isEmpty()) return computeUkResult(g)
    val clone = g.deepCopyUk()
    val rng = Rng.createRng(clone.rngState)
    resolvePlan(clone.queuedActions, { it.planMove() }, { clone.causes.size }) { action, mult, bonuses ->
        applyUkAction(clone, action, rng, mult, bonuses)
    }
    for (region in clone.regions) {
        for (bloc in region.blocs) bloc.support = blocPartyShares(bloc)
    }
    return computeUkResult(clone)
}
