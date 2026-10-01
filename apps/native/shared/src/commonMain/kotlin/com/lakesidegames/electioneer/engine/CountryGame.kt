package com.lakesidegames.electioneer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

// Country game facades over the core multiparty engine (Canada, Germany,
// France, Australia). Port of src/engine/countryGame.ts. Pure +
// deterministic: same seed ⇒ same game.

@Serializable
data class CountryResources(
    // Millions, in the country's currency.
    var funds: Double,
    override var actions: Int,
    override var maxActions: Int,
    override var momentum: Double,
) : MpResources

@Serializable(with = CountryActionTypeSerial::class)
enum class CountryActionType(val serial: String) {
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

@Serializable(with = CountryAdModeSerial::class)
enum class CountryAdMode(val serial: String) {
    POSITIVE("positive"),
    CONTRAST("contrast"),
    ISSUE("issue"),
}

@Serializable
data class CountryAction(
    val type: CountryActionType,
    val party: PartyId,
    val regionId: String? = null,
    val targetParty: PartyId? = null,
    val mode: CountryAdMode? = null,
    val issueId: String? = null,
    val spend: Double? = null,
    val day: Int? = null,
)

@Serializable
data class CountryResult(
    val seats: Map<PartyId, Int>,
    val voteShare: Map<PartyId, Double>,
    val seatResults: List<SeatResult>,
    val largestParty: PartyId,
    val hung: Boolean,
    val government: Government,
    val postMortem: List<CauseEntry>,
)

@Serializable
data class CountryPendingEvent(
    val eventId: String,
    val targetParty: PartyId,
)

@Serializable
data class CountryGameState(
    val countryId: String,
    val seed: Long,
    var rngState: Long,
    var turn: Int,
    val totalTurns: Int,
    var phase: String,
    val electionId: String,
    val label: String,
    val tagline: String,
    val playerParty: PartyId,
    val parties: List<PartyId>,
    val leaders: Map<PartyId, com.lakesidegames.electioneer.content.CountryLeader>,
    val salience: MutableMap<String, Double>,
    val campaignPower: Map<PartyId, Double>? = null,
    val regions: List<StateContest>,
    val resources: Map<PartyId, CountryResources>,
    val causes: MutableList<CauseEntry>,
    var queuedActions: List<CountryAction>,
    val abstaining: List<PartyId>,
    // Rolling campaign-news log (latest first) from fired events.
    var news: List<NewsItem>,
    // Election-deck event ids that already fired (never repeat in a game).
    var firedEvents: MutableList<String>? = null,
    // Winning post for THIS election (chamber sizes vary across cycles).
    val majority: MajorityRule? = null,
    // Underdog handicap ("normal" = identity).
    val difficulty: Difficulty? = null,
    var lastRecap: List<RecapItem>,
    var pendingEvent: CountryPendingEvent? = null,
    // Player-authored game: casual only, never posts a score.
    val custom: Boolean? = null,
    val customScenario: String? = null,
    var result: CountryResult? = null,
)

fun majorityFor(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
): MajorityRule {
    val m = g.majority
        ?: country.elections[g.electionId]?.majority?.let { MajorityRule(total = it.total, threshold = it.threshold) }
        ?: country.system.majority
    return m
}

private fun activeParties(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    election: com.lakesidegames.electioneer.content.CountryElectionData,
): List<PartyId> {
    val set = linkedSetOf<PartyId>()
    for (r in election.regions.values) {
        for (p in r.v.keys) set.add(p)
        for (p in r.s.keys) set.add(p)
    }
    val order = country.playable + country.system.parties.map { it.id }
    return set.sortedWith(compareBy { order.indexOf(it) })
}

fun playablePartiesIn(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    electionId: String,
): List<PartyId> {
    val election = country.elections[electionId] ?: return country.playable.toList()
    val active = activeParties(country, election).toSet()
    return country.playable.filter { active.contains(it) }
}

private fun leaderForCountry(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    electionId: String,
    partyId: PartyId,
): com.lakesidegames.electioneer.content.CountryLeader {
    val name = country.system.parties.find { it.id == partyId }?.shortName ?: partyId.uppercase()
    return country.leaders[electionId]?.get(partyId) ?: com.lakesidegames.electioneer.content.CountryLeader(
        partyId = partyId, name = "$name leader",
        charisma = 55.0, energy = 60.0, competence = 60.0, machine = 55.0,
    )
}

@Serializable
data class CountryHandicap(
    val funds: Double,
    val actions: Int,
    val persuasion: Double,
    val aiPersuasion: Double,
    val environment: Double,
)

val COUNTRY_HANDICAP: Map<String, CountryHandicap> = mapOf(
    "easy" to CountryHandicap(funds = 6.0, actions = 2, persuasion = 1.5, aiPersuasion = 0.5, environment = 0.9),
    "normal" to CountryHandicap(funds = 0.0, actions = 0, persuasion = 1.0, aiPersuasion = 1.0, environment = 0.0),
    "hard" to CountryHandicap(funds = 0.0, actions = 0, persuasion = 0.85, aiPersuasion = 1.1, environment = 0.0),
)

// One-time favorable-climate tilt, then refresh live support. No-op at 0.
private fun applyCountryEnvironment(regions: List<StateContest>, player: PartyId, env: Double) {
    if (env == 0.0) return
    for (region in regions) {
        for (bloc in region.blocs) {
            val appeal = bloc.appeal ?: continue
            appeal[player] = (appeal[player] ?: 0.0) + env
            bloc.support = blocPartyShares(bloc)
        }
    }
}

data class NewCountryGameOptions(
    val seed: Any? = null, // Long, Int, or String
    val election: String? = null,
    val playerParty: PartyId? = null,
    val totalTurns: Int = 6,
    val difficulty: Difficulty? = null,
)

fun createCountryGame(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    opts: NewCountryGameOptions = NewCountryGameOptions(),
): CountryGameState {
    // Default to the NEWEST election. (Never ids[0]: integer-like keys such as
    // "2021" enumerate ascending regardless of insertion order.)
    val ids = country.elections.keys.sortedByDescending { country.elections.getValue(it).year }
    val election = country.elections[opts.election] ?: country.elections.getValue(ids[0])
    val seed: Long = when (val s = opts.seed) {
        is String -> Rng.hashSeed(s)
        is Int -> s.toLong() and 0xFFFFFFFFL
        is Long -> s and 0xFFFFFFFFL
        else -> 0L // no platform clock in common code: explicit seeds only
    }
    val parties = activeParties(country, election)
    val playerParty = if (opts.playerParty != null && parties.contains(opts.playerParty)) {
        opts.playerParty
    } else {
        parties.find { country.playable.contains(it) } ?: parties[0]
    }

    val difficulty = opts.difficulty ?: "normal"
    val hc = COUNTRY_HANDICAP.getValue(difficulty)

    val leaders = linkedMapOf<PartyId, com.lakesidegames.electioneer.content.CountryLeader>()
    for (p in parties) leaders[p] = leaderForCountry(country, election.id, p)

    val resources = linkedMapOf<PartyId, CountryResources>()
    for (p in parties) {
        val machine = leaders.getValue(p).machine
        val bonusF = if (p == playerParty) hc.funds else 0.0
        val bonusA = if (p == playerParty) hc.actions else 0
        val slots = 5 + round(leaders.getValue(p).energy / 40).toInt() + bonusA
        resources[p] = CountryResources(
            funds = 4 + machine / 10 + bonusF,
            actions = slots,
            maxActions = slots,
            momentum = 0.0,
        )
    }

    val regions = buildCountryRegions(country, election)
    applyCountryEnvironment(regions, playerParty, hc.environment)

    return CountryGameState(
        countryId = country.id,
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
        campaignPower = election.campaignPower,
        regions = regions,
        resources = resources,
        causes = mutableListOf(),
        queuedActions = emptyList(),
        abstaining = country.abstaining.toList(),
        news = emptyList(),
        firedEvents = mutableListOf(),
        majority = election.majority?.let { MajorityRule(total = it.total, threshold = it.threshold) }
            ?: country.system.majority,
        difficulty = difficulty,
        lastRecap = emptyList(),
        pendingEvent = null,
    )
}

// ── Actions (same verbs + balance as the UK game) ──────────────────────────
private fun findRegion(g: CountryGameState, id: String?): StateContest? =
    g.regions.find { it.id == id }

private fun saturate(bloc: StateBloc, party: PartyId, raw: Double): Double {
    val acc = abs(bloc.campaignAppeal?.get(party) ?: 0.0)
    val room = max(0.15, 1 - acc / 0.8)
    return raw * room
}

private fun addAppeal(g: CountryGameState, region: StateContest, party: PartyId, cause: String, delta: Double) {
    // Difficulty scales campaigning: the player's own effort by `persuasion`,
    // every rival's by `aiPersuasion`. Both are 1.0 on normal (identity).
    val hc = COUNTRY_HANDICAP[g.difficulty ?: "normal"] ?: COUNTRY_HANDICAP.getValue("normal")
    val scaled = delta * (if (party == g.playerParty) hc.persuasion else hc.aiPersuasion) * (g.campaignPower?.get(party) ?: 1.0)
    for (bloc in region.blocs) {
        val camp = bloc.campaignAppeal ?: mutableMapOf<String, Double>().also { bloc.campaignAppeal = it }
        camp[party] = (camp[party] ?: 0.0) + saturate(bloc, party, scaled)
    }
    g.causes.add(CauseEntry(turn = g.turn, stateId = region.id, cause = cause, marginDelta = scaled))
}

private fun standsIn(g: CountryGameState, party: PartyId): List<StateContest> =
    g.regions.filter { it.baselineShare?.get(party) != null }

private fun topRivalIn(region: StateContest, party: PartyId): PartyId? {
    val shareByParty = tallyRegion(region).shareByParty
    return shareByParty.keys
        .filter { it != party && (shareByParty[it] ?: 0.0) > 0 }
        .sortedWith(compareByDescending { shareByParty[it] ?: 0.0 })
        .firstOrNull()
}

private fun applyCountryAction(g: CountryGameState, a: CountryAction, rng: Rng,
                               planMultiplier: Double = 1.0, planBonuses: List<PlanBonus> = emptyList()) {
    val res = g.resources[a.party] ?: return
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
        CountryActionType.CANVASS -> {
            if (region == null) return
            res.actions -= 1
            addAppeal(g, region, a.party, "${leader.name} doorstep campaign in ${region.abbr}", 0.05 * skill * (0.9 + rng.next() * 0.2))
        }
        CountryActionType.GROUND_GAME -> {
            if (region == null) return
            if (!spend(1.5)) return
            res.actions -= 1
            region.momentum = clamp(region.momentum + 2, -100.0, 100.0)
            addAppeal(g, region, a.party, "${leader.name} field operation in ${region.abbr}", 0.075 * (0.85 + leader.machine / 300))
        }
        CountryActionType.GOTV -> {
            if (region == null) return
            if (!spend(1.0)) return
            res.actions -= 1
            addAppeal(g, region, a.party, "${leader.name} GOTV drive in ${region.abbr}", 0.06 * (0.85 + leader.energy / 300) * planMultiplier)
        }
        CountryActionType.RALLY -> {
            if (region == null) return
            res.actions -= 1
            region.momentum = clamp(region.momentum + 6, -100.0, 100.0)
            res.momentum = clamp(res.momentum + 2, -100.0, 100.0)
            addAppeal(g, region, a.party, "${leader.name} rally in ${region.abbr}", 0.045 * skill)
        }
        CountryActionType.SURROGATE -> {
            if (region == null) return
            if (!spend(0.25)) return
            res.actions -= 1
            addAppeal(g, region, a.party, "Surrogate stumps for ${leader.name} in ${region.abbr}", 0.035 * (0.85 + leader.energy / 300))
        }
        CountryActionType.BROADCAST -> {
            val cost = max(0.5, a.spend ?: 1.5)
            if (!spend(cost)) return
            res.actions -= 1
            val mode = a.mode ?: CountryAdMode.POSITIVE
            val targets = if (region != null) listOf(region) else standsIn(g, a.party)
            val power = 0.02 * sqrt(cost / 1.5) * (0.85 + leader.machine / 300) * planMultiplier
            val per = if (region != null) power else power * 0.8
            if (mode == CountryAdMode.ISSUE && a.issueId != null) {
                g.salience[a.issueId] = clamp((g.salience[a.issueId] ?: 0.4) + 0.05 * planMultiplier, 0.0, 1.0)
                for (t in targets) addAppeal(g, t, a.party, "${leader.name} issue broadcast", per * 0.7)
            } else if (mode == CountryAdMode.CONTRAST) {
                for (t in targets) {
                    val foe = a.targetParty ?: topRivalIn(t, a.party)
                    if (foe != null) addAppeal(g, t, foe, "${leader.name} contrast broadcast", -per * 0.9)
                }
            } else {
                for (t in targets) addAppeal(g, t, a.party, "${leader.name} broadcast", per)
            }
            res.momentum = clamp(res.momentum + 1, -100.0, 100.0)
        }
        CountryActionType.OPPO_RESEARCH -> {
            if (!spend(2.0)) return
            res.actions -= 1
            val scope = if (region != null) listOf(region) else standsIn(g, a.party)
            for (t in scope) {
                val foe = a.targetParty ?: topRivalIn(t, a.party)
                // Self-targeted dirt (a gaffe) lands on the author.
                if (foe != null) addAppeal(g, t, if (a.party == foe) a.party else foe, "${leader.name}'s team releases dossier on ${foe.uppercase()}", -0.05 * compSkill)
            }
        }
        CountryActionType.DEBATE_PREP -> {
            res.actions -= 1
            for (t in standsIn(g, a.party)) addAppeal(g, t, a.party, "${leader.name} debate performance", 0.012 * compSkill)
            res.momentum = clamp(res.momentum + 4, -100.0, 100.0)
        }
        CountryActionType.POLICY_PREP -> {
            res.actions -= 1
            for (t in standsIn(g, a.party)) addAppeal(g, t, a.party, "${leader.name} platform launch", 0.014 * compSkill)
            res.momentum = clamp(res.momentum + 2, -100.0, 100.0)
        }
        CountryActionType.ISSUE_PIVOT -> {
            res.actions -= 1
            if (a.issueId != null) g.salience[a.issueId] = clamp((g.salience[a.issueId] ?: 0.4) + 0.08 * planMultiplier, 0.0, 1.0)
            for (t in standsIn(g, a.party)) addAppeal(g, t, a.party, "${leader.name} pivots the campaign", 0.008 * skill * planMultiplier)
        }
        CountryActionType.FUNDRAISE -> {
            res.actions -= 1
            val haul = (1.5 + leader.machine / 50) * (0.85 + rng.next() * 0.3)
            res.funds += haul
            g.causes.add(CauseEntry(turn = g.turn, cause = "${leader.name} fundraising (+${toFixed1(haul)}M)", marginDelta = 0.0))
        }
    }
    if (g.causes.size > causeCount) {
        for (bonus in planBonuses) g.causes.add(bonus.cause(g.turn, a.regionId))
    }
}

// ── AI + events + turn loop (country-parameterized) ────────────────────────
private fun planCountryAiActions(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    party: PartyId,
    rng: Rng,
): List<CountryAction> {
    val res = g.resources.getValue(party)
    val difficulty = g.difficulty ?: "normal"
    return planMultipartyAi(
        MpView(
            regions = g.regions,
            turn = g.turn,
            totalTurns = g.totalTurns,
            funds = res.funds,
            actions = res.actions,
            majority = majorityFor(g, country),
            abstaining = g.abstaining,
            compatible = country.compatible,
        ),
        party,
        difficulty,
        rng,
    ).map { like ->
        CountryAction(
            type = CountryActionType.entries.first { it.serial == like.type },
            party = like.party,
            regionId = like.regionId,
            targetParty = like.targetParty,
            mode = like.mode?.let { m -> CountryAdMode.entries.first { it.serial == m } },
            issueId = null,
            spend = like.spend,
        )
    }
}

private fun pickEventTarget(
    role: String,
    player: PartyId,
    leader: PartyId,
    majors: List<PartyId>,
    pick: (List<PartyId>) -> PartyId,
): PartyId {
    return when (role) {
        "player" -> player
        "leader" -> leader
        "challenger" -> majors.filter { it != leader }.let { if (it.isNotEmpty()) pick(it) else player }
        else -> if (majors.isNotEmpty()) pick(majors) else player
    }
}

private fun countryEventById(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    g: CountryGameState,
    id: String,
): com.lakesidegames.electioneer.content.CountryEventDef? {
    val deck = country.elections[g.electionId]?.events ?: emptyList()
    return deck.find { it.id == id } ?: country.events.find { it.id == id }
}

private fun applyAppealNational(g: CountryGameState, target: PartyId, appeal: Double, cause: String) {
    for (region in g.regions) {
        if (region.baselineShare?.get(target) == null) continue
        for (bloc in region.blocs) {
            val camp = bloc.campaignAppeal ?: mutableMapOf<String, Double>().also { bloc.campaignAppeal = it }
            camp[target] = (camp[target] ?: 0.0) + appeal
        }
    }
    g.causes.add(CauseEntry(turn = g.turn, cause = cause, marginDelta = appeal))
}

private fun applyCountryEventEffects(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    ev: com.lakesidegames.electioneer.content.CountryEventDef,
    target: PartyId,
) {
    if (ev.appeal != null) applyAppealNational(g, target, ev.appeal, "${ev.id}: ${target.uppercase()}")
    if (ev.momentum != null && g.resources[target] != null) {
        val res = g.resources.getValue(target)
        res.momentum = clamp(res.momentum + ev.momentum, -100.0, 100.0)
    }
    val short = country.system.parties.find { it.id == target }?.shortName ?: target.uppercase()
    g.news = (listOf(NewsItem(turn = g.turn, text = ev.headline.replace("{party}", short))) + g.news).take(12)
}

private fun resolveCountryEventTarget(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    ev: com.lakesidegames.electioneer.content.CountryEventDef,
    rng: Rng,
): PartyId? {
    val proj = computeSeatsResult(g.regions, majorityFor(g, country), g.abstaining)
    val majors = g.parties.filter { country.playable.contains(it) }
    return if (ev.party != null && g.parties.contains(ev.party)) {
        ev.party
    } else {
        pickEventTarget(ev.role, g.playerParty, proj.largestParty, majors) { xs -> rng.pick(xs) }
    }
}

private fun applyCountryEvent(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    ev: com.lakesidegames.electioneer.content.CountryEventDef,
    rng: Rng,
) {
    val target = resolveCountryEventTarget(g, country, ev, rng) ?: return

    if (ev.choices != null && ev.choices.isNotEmpty() && target == g.playerParty && g.pendingEvent == null) {
        g.pendingEvent = CountryPendingEvent(eventId = ev.id, targetParty = target)
        val short = country.system.parties.find { it.id == target }?.shortName ?: target.uppercase()
        g.news = (listOf(NewsItem(turn = g.turn, text = ev.headline.replace("{party}", short))) + g.news).take(12)
        return
    }
    applyCountryEventEffects(g, country, ev, target)
}

// Resolve a pending player-choice event. Pure: returns a new game state.
fun resolveCountryPlayerEvent(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    choiceId: String,
): CountryGameState {
    val next = g.deepCopyCountry()
    val pending = next.pendingEvent ?: return next
    val ev = countryEventById(country, next, pending.eventId)
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
        val maj = majorityFor(next, country)
        val proj = computeSeatsResult(next.regions, maj, next.abstaining)
        val rival = proj.seats.keys
            .filter { it != target }
            .sortedWith(compareByDescending { proj.seats[it] ?: 0 })
            .firstOrNull()
        if (rival != null) applyAppealNational(next, rival, choice.rivalAppeal, "${ev.id}: hit on ${rival.uppercase()}")
    }
    val short = country.system.parties.find { it.id == target }?.shortName ?: target
    next.lastRecap = (
        listOf(
            RecapItem(
                label = ev.headline.replace("{party}", short),
                detail = choice.resultText,
                marginDelta = choice.appeal,
            ),
        ) + next.lastRecap
        ).take(12)
    if (next.turn >= next.totalTurns) {
        next.phase = "result"
        next.result = computeCountryResult(next, country)
    }
    return next
}

private fun fireCountryEvent(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    rng: Rng,
) {
    val firedList = g.firedEvents ?: mutableListOf<String>().also { g.firedEvents = it }
    val fired = firedList.toSet()
    val deck = country.elections[g.electionId]?.events ?: emptyList()

    for (ev in deck) {
        if (ev.turn == null || ev.turn != g.turn || fired.contains(ev.id)) continue
        applyCountryEvent(g, country, ev, rng)
        firedList.add(ev.id)
    }

    if (g.pendingEvent != null) return

    val pool = deck.filter { it.turn == null && !firedList.contains(it.id) } + country.events
    if (pool.isEmpty() || !rng.chance(country.eventChance)) return
    val ev = rng.weightedPick(pool, pool.map { it.weight })
    applyCountryEvent(g, country, ev, rng)
    if (deck.any { it.id == ev.id }) firedList.add(ev.id)
}

private fun buildCountryRecap(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    turn: Int,
    seatsBefore: Int,
): List<RecapItem> {
    val seatsAfter = computeCountryResult(g, country).seats[g.playerParty] ?: 0
    val short = country.system.parties.find { it.id == g.playerParty }?.shortName ?: g.playerParty
    return buildCauseRecap(
        g.causes,
        turn,
        RecapItem(
            label = "Projected ${country.unitNamePlural}",
            detail = "$short $seatsAfter (was $seatsBefore)",
            marginDelta = (seatsAfter - seatsBefore).toDouble(),
        ),
    )
}

private fun CountryGameState.deepCopyCountry(): CountryGameState = copy(
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

@Serializable
data class CountryAdvanceOptions(
    // Calibration anchor: neutral play reproduces history.
    val disableAi: Boolean = false,
    val autoResolvePlayerEvents: Boolean = true,
)

fun countryAdvanceTurn(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
    opts: CountryAdvanceOptions = CountryAdvanceOptions(),
): CountryGameState {
    var next: CountryGameState = g.deepCopyCountry()
    val gate = resolvePendingChoiceGate(
        next,
        opts.autoResolvePlayerEvents,
        { it.pendingEvent != null },
        { game, choiceId -> resolveCountryPlayerEvent(game, country, choiceId) },
        { game ->
            val ev = game.pendingEvent?.let { countryEventById(country, game, it.eventId) }
            ev?.choices?.firstOrNull()?.id
        },
    )
    if (gate.blocked) return g
    next = gate.game

    if (next.phase == "result") return next
    if (next.turn >= next.totalTurns) {
        next.phase = "result"
        next.result = computeCountryResult(next, country)
        return next
    }

    val rng = Rng.createRng(next.rngState)
    val seatsBefore = computeCountryResult(next, country).seats[next.playerParty] ?: 0
    val turn = next.turn

    resolvePlan(next.queuedActions, { it.planMove() }, { next.causes.size }) { action, mult, bonuses ->
        applyCountryAction(next, action, rng, mult, bonuses)
    }
    next.queuedActions = emptyList()

    if (!opts.disableAi) {
        for (p in next.parties) {
            if (p == next.playerParty || !country.playable.contains(p)) continue
            for (a in planCountryAiActions(next, country, p, rng)) applyCountryAction(next, a, rng)
        }
        fireCountryEvent(next, country, rng)
    }

    decayMultipartyTurn(next.resources, next.regions, next.parties)

    next.lastRecap = buildCountryRecap(next, country, turn, seatsBefore)
    next.rngState = rng.state()
    // Advance the turn counter and close the campaign when out of weeks.
    next.turn += 1
    if (next.pendingEvent != null) return next
    if (next.turn >= next.totalTurns) {
        next.phase = "result"
        next.result = computeCountryResult(next, country)
    }
    return next
}

fun computeCountryResult(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
): CountryResult {
    val r = computeSeatsResult(g.regions, majorityFor(g, country), g.abstaining, country.compatible)
    val playerName = g.leaders[g.playerParty]?.name ?: ""
    val postMortem = g.causes
        .filter { c -> playerName.isNotEmpty() && c.marginDelta != 0.0 && c.cause.contains(playerName) }
        .sortedWith(compareByDescending { abs(it.marginDelta) })
        .take(8)
    return CountryResult(
        seats = r.seats,
        voteShare = r.voteShare,
        seatResults = r.seatResults,
        largestParty = r.largestParty,
        hung = r.hung,
        government = r.government,
        postMortem = postMortem,
    )
}

fun projectCountry(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
): CountryResult = computeCountryResult(g, country)

fun projectCountryPreview(
    g: CountryGameState,
    country: com.lakesidegames.electioneer.content.CountryBundle,
): CountryResult {
    if (g.queuedActions.isEmpty()) return computeCountryResult(g, country)
    val clone = g.deepCopyCountry()
    val rng = Rng.createRng(clone.rngState)
    resolvePlan(clone.queuedActions, { it.planMove() }, { clone.causes.size }) { action, mult, bonuses ->
        applyCountryAction(clone, action, rng, mult, bonuses)
    }
    for (region in clone.regions) {
        for (bloc in region.blocs) bloc.support = blocPartyShares(bloc)
    }
    return computeCountryResult(clone, country)
}

// ── Region construction (the country-parameterized ukSetup) ────────────────
private const val EPS = 1e-9

private fun normalizeShare(v: Map<String, Double>): Map<String, Double> {
    var sum = 0.0
    for (x in v.values) sum += max(0.0, x)
    val out = linkedMapOf<String, Double>()
    for (p in v.keys) out[p] = if (sum > 0) max(0.0, v.getValue(p)) / sum else 0.0
    return out
}

// Solve per-bloc appeals so the region's turnout-weighted aggregate hits the
// real result — same IPF as the UK builder, over this country's blocs.
private data class CountryBlocIn(
    val id: String,
    val size: Double,
    val turnout: Double,
    val tilt: Map<String, Double>,
)

private fun solveRegionBlocs(
    blocs: List<CountryBlocIn>,
    target: Map<String, Double>,
): List<StateBloc> {
    val parties = target.keys.toList()
    val lnTarget = linkedMapOf<String, Double>()
    for (p in parties) lnTarget[p] = kotlin.math.ln(max(EPS, target.getValue(p)))
    val corr = linkedMapOf<String, Double>()
    for (p in parties) corr[p] = 0.0

    fun appealFor(tilt: Map<String, Double>): Map<String, Double> {
        val a = linkedMapOf<String, Double>()
        for (p in parties) a[p] = lnTarget.getValue(p) + (tilt[p] ?: 0.0) + corr.getValue(p)
        return a
    }

    repeat(16) {
        val agg = linkedMapOf<String, Double>()
        for (p in parties) agg[p] = 0.0
        var totW = 0.0
        for (b in blocs) {
            val sh = softmax(appealFor(b.tilt))
            val w = b.size * b.turnout
            for (p in parties) agg[p] = agg.getValue(p) + w * sh.getValue(p)
            totW += w
        }
        for (p in parties) {
            val a = if (totW > 0) agg.getValue(p) / totW else target.getValue(p)
            corr[p] = corr.getValue(p) + kotlin.math.ln(max(EPS, target.getValue(p)) / max(EPS, a))
        }
    }

    return blocs.map { b ->
        val appeal = appealFor(b.tilt)
        StateBloc(
            blocId = BlocId.fromSerial(b.id),
            size = b.size,
            turnoutPropensity = b.turnout,
            baselineMargin = 0.0,
            support = softmax(appeal),
            campaignMargin = 0.0,
            enthusiasm = 1.0,
            appeal = appeal.toMutableMap(),
            campaignAppeal = parties.associateWith { 0.0 }.toMutableMap(),
        )
    }
}

private fun buildRegionContest(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    meta: com.lakesidegames.electioneer.content.CountryRegionMeta,
    res: com.lakesidegames.electioneer.content.RegionResult,
): StateContest {
    val target = normalizeShare(res.v)
    // The election's own seat table defines the region pool, so one bundle can
    // host elections fought under different apportionments.
    val pool = res.s.values.sum().let { if (it == 0) meta.seats else it }
    val raw = country.blocs.map { b -> Pair(b, b.share * (meta.profile?.get(b.id) ?: 1.0)) }
    val totalShare = raw.sumOf { it.second }
    val blocsIn = raw.map { (def, share) ->
        CountryBlocIn(
            id = def.id,
            size = (meta.electorate * share) / totalShare,
            turnout = def.turnoutPropensity,
            tilt = def.tilt,
        )
    }

    return StateContest(
        id = meta.id,
        name = meta.name,
        abbr = meta.abbr,
        electoralVotes = 0,
        region = Region.SWING,
        prior2020DemShare = 0.5,
        mediaMarketCost = 1.0,
        battleground = false,
        blocs = solveRegionBlocs(blocsIn, target),
        groundGame = mutableMapOf("dem" to 0.0, "rep" to 0.0),
        momentum = 0.0,
        seats = pool,
        baselineSeats = res.s.toMap(),
        baselineShare = target,
        seatElasticity = meta.seatElasticity ?: country.defaultSeatElasticity,
    )
}

fun buildCountryRegions(
    country: com.lakesidegames.electioneer.content.CountryBundle,
    election: com.lakesidegames.electioneer.content.CountryElectionData,
): List<StateContest> {
    return country.regions
        .filter { meta -> election.regions[meta.id] != null }
        .map { meta -> buildRegionContest(country, meta, election.regions.getValue(meta.id)) }
}
