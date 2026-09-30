package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.abs

// Matches the web's compact, engine-neutral replay contract. Never store whole
// campaign states here: the log is a read-only record, separate from undo.
@Serializable data class NativeReplayStanding(val id: String, val poll: Double, val units: Int)
@Serializable data class NativeReplayParty(val id: String, val name: String, val color: String)
@Serializable data class NativeReplaySnapshot(val turn: Int, val leaderId: String?, val standings: List<NativeReplayStanding>,
    val tossupUnits: Int, val playerCash: Double? = null, val playerMomentum: Double? = null,
    val contestShare: Map<String, Double>, val actions: List<String> = emptyList(), val events: List<String> = emptyList())
@Serializable data class NativeReplayLog(val version: Int = 1, val engine: String, val scenarioId: String,
    val playerId: String, val unitLabel: String, val unitTotal: Int, val majority: Int, val totalTurns: Int,
    val mode: String, val parties: List<NativeReplayParty>, val contestNames: Map<String, String>,
    val snapshots: List<NativeReplaySnapshot> = emptyList())
@Serializable data class NativeReportPoint(val turn: Int, val value: Double)
@Serializable data class NativeDecisiveContest(val id: String, val name: String, val finalPlayerShare: Double,
    val marginPts: Double, val won: Boolean)
@Serializable data class NativeLeadFlip(val turn: Int, val toPlayer: Boolean)
@Serializable data class NativeActionCount(val label: String, val count: Int)
@Serializable data class NativeReportEvent(val turn: Int, val text: String)
@Serializable data class NativeCampaignReport(val hasData: Boolean, val playerId: String, val playerName: String,
    val topRivalId: String?, val topRivalName: String?, val unitLabel: String, val majority: Int,
    val marginTrend: List<NativeReportPoint>, val unitTrend: List<NativeReportPoint>,
    val startMarginPts: Double, val finalMarginPts: Double, val swingPts: Double, val peakUnits: Int,
    val floorUnits: Int, val decisiveContests: List<NativeDecisiveContest>, val flips: List<NativeLeadFlip>,
    val cashSpent: Double?, val actionTally: List<NativeActionCount>, val events: List<NativeReportEvent>)

@Serializable private data class ReplayWorldSave(val uk: UkGameState? = null, val country: CountryGameState? = null)
private data class ReplaySource(val us: GameState? = null, val uk: UkGameState? = null, val country: CountryGameState? = null) {
    val turn: Int get() = us?.turn ?: uk?.turn ?: country!!.turn
}
private fun replaySource(snapshot: String): ReplaySource {
    loadGame(snapshot)?.let { return ReplaySource(us = it.state) }
    val world = EngineJson.decodeFromString<ReplayWorldSave>(snapshot)
    require((world.uk == null) != (world.country == null))
    return ReplaySource(uk = world.uk, country = world.country)
}

object NativeReplay {
    fun fileExport(snapshot: String, replay: String?): String? {
        val exported = NativeSaveTransfer.export(snapshot) ?: return null
        val root = EngineJson.parseToJsonElement(exported).jsonObject
        return JsonObject(root + ("_movReplay" to (replay?.let(EngineJson::parseToJsonElement) ?: JsonNull))).toString()
    }
    fun fileReplay(json: String): String? = runCatching {
        EngineJson.parseToJsonElement(json).jsonObject["_movReplay"]?.takeUnless { it == JsonNull }?.toString()
    }.getOrNull()
    fun document(log: NativeReplayLog, selectedTurn: String?): NativeAnalysisDocument {
        val snapshots = log.snapshots.sortedBy { it.turn }
        require(snapshots.isNotEmpty())
        val chosen = snapshots.firstOrNull { it.turn.toString() == selectedTurn } ?: snapshots.last()
        val report = report(log)
        fun name(id: String) = log.parties.firstOrNull { it.id == id }?.name ?: id
        fun week(turn: Int) = if (turn == 0) "Campaign start" else if (turn >= log.totalTurns) "Election eve" else "Week $turn"
        fun number(value: Double) = toFixed1(value)
        val cashScale = if (log.engine == "us") 1_000_000.0 else 1.0
        val sections = mutableListOf(
            NativeAnalysisSection("standings", "${week(chosen.turn)} standings", chosen.standings.map {
                NativeAnalysisRow(name(it.id), "${it.units} ${log.unitLabel} · ${number(it.poll * 100)}% vote share")
            } + listOfNotNull(chosen.playerCash?.let { NativeAnalysisRow("Your funds", "${number(it / cashScale)}M") },
                chosen.playerMomentum?.let { NativeAnalysisRow("Your momentum", number(it)) })),
            NativeAnalysisSection("week-actions", "This week", chosen.actions.map { NativeAnalysisRow("Campaign move", it) } +
                chosen.events.map { NativeAnalysisRow("Campaign event", it) }),
            NativeAnalysisSection("closest", "Closest contests", chosen.contestShare.entries.sortedBy { abs(it.value - 0.5) }.take(6).map {
                NativeAnalysisRow(log.contestNames[it.key] ?: it.key, "Your vote share ${number(it.value * 100)}%")
            }),
            NativeAnalysisSection("report", "Campaign report", listOf(
                NativeAnalysisRow("Leading rival", report.topRivalName ?: "None"),
                NativeAnalysisRow("Your national margin", "${number(report.finalMarginPts)} points"),
                NativeAnalysisRow("Swing since start", "${number(report.swingPts)} points"),
                NativeAnalysisRow("Peak / floor ${log.unitLabel}", "${report.peakUnits} / ${report.floorUnits}"),
                NativeAnalysisRow("Lead changes", report.flips.size.toString()),
            ) + listOfNotNull(report.cashSpent?.let { NativeAnalysisRow("Net cash used, including fundraising", "${number(it / cashScale)}M") })),
            NativeAnalysisSection("action-tally", "What you spent the campaign doing", report.actionTally.map { NativeAnalysisRow(it.label, "${it.count}×") }),
            NativeAnalysisSection("event-history", "Campaign events and debates", report.events.map { NativeAnalysisRow(week(it.turn), it.text) }),
        )
        if (report.decisiveContests.isNotEmpty()) sections += NativeAnalysisSection("decisive", "Decisive contests", report.decisiveContests.map {
            NativeAnalysisRow(it.name, "${if (it.won) "Won" else "Lost"} by ${number(abs(it.marginPts))} points")
        })
        fun partySeries(party: NativeReplayParty, value: (NativeReplayStanding) -> Double) = NativeTrendSeries(party.name, party.color,
            snapshots.mapNotNull { snap -> snap.standings.firstOrNull { it.id == party.id }?.let { NativeTrendPoint(snap.turn, value(it)) } })
        val charts = mutableListOf(
            NativeTrendChart("replay-units", "Projected ${log.unitLabel}", 0.0, log.unitTotal.toDouble(), log.majority.toDouble(), "${log.majority} to win",
                log.parties.map { partySeries(it) { row -> row.units.toDouble() } }),
            NativeTrendChart("replay-polls", "National vote shares (%)", 0.0, 100.0, 50.0, "50%",
                log.parties.map { partySeries(it) { row -> row.poll * 100 } }),
        )
        val own = log.parties.firstOrNull { it.id == log.playerId }
        val cash = snapshots.mapNotNull { snap -> snap.playerCash?.let { NativeTrendPoint(snap.turn, it / cashScale) } }
        if (cash.isNotEmpty()) charts += NativeTrendChart("replay-cash", "Your funds (millions)", 0.0, maxOf(1.0, cash.maxOf { it.value }), 0.0, "0",
            listOf(NativeTrendSeries(own?.name ?: log.playerId, own?.color ?: "#c99a38", cash)))
        val momentum = snapshots.mapNotNull { snap -> snap.playerMomentum?.let { NativeTrendPoint(snap.turn, it) } }
        if (momentum.isNotEmpty()) charts += NativeTrendChart("replay-momentum", "Your momentum", -100.0, 100.0, 0.0, "Neutral",
            listOf(NativeTrendSeries(own?.name ?: log.playerId, own?.color ?: "#c99a38", momentum)))
        return NativeAnalysisDocument(snapshots.map { NativeAnalysisRegion(it.turn.toString(), week(it.turn)) }, chosen.turn.toString(), sections, charts)
    }
    fun json(log: NativeReplayLog): String = EngineJson.encodeToString(log)
    fun append(log: NativeReplayLog, snapshot: NativeReplaySnapshot): NativeReplayLog =
        log.copy(snapshots = log.snapshots.filter { it.turn < snapshot.turn } + snapshot)
    fun truncate(log: NativeReplayLog, turn: Int): NativeReplayLog = log.copy(snapshots = log.snapshots.filter { it.turn <= turn })

    fun start(snapshot: String, mode: String): NativeReplayLog {
        require(mode == "casual" || mode == "daily")
        val source = replaySource(snapshot)
        source.us?.let { game ->
            var log = NativeReplayLog(engine = "us", scenarioId = game.scenarioId ?: "2020", playerId = game.playerCandidate.serial,
                unitLabel = "electoral votes", unitTotal = 538, majority = 270, totalTurns = game.totalTurns, mode = mode,
                parties = listOf("dem", "rep").map { id -> game.candidates.getValue(id).let { NativeReplayParty(id, it.shortName, it.color) } },
                contestNames = game.states.associate { it.id to it.name })
            if (game.timeline.isNullOrEmpty()) return append(log, usSnapshot(game))
            for (point in game.timeline.orEmpty()) log = append(log, usSnapshot(game, at = point))
            return log
        }
        val uk = source.uk
        val country = source.country
        val bundle = country?.let { getCountry(it.countryId)!! }
        val majority = uk?.let(::majorityForUk) ?: majorityFor(country!!, bundle!!)
        val ids = uk?.parties ?: country!!.parties
        val log = NativeReplayLog(engine = if (uk != null) "uk" else "country",
            scenarioId = uk?.electionId ?: "${country!!.countryId}-${country.electionId}",
            playerId = uk?.playerParty ?: country!!.playerParty, unitLabel = bundle?.unitNamePlural ?: "seats",
            unitTotal = majority.total, majority = majority.threshold, totalTurns = uk?.totalTurns ?: country!!.totalTurns, mode = mode,
            parties = ids.map { id ->
                val party = bundle?.system?.parties?.firstOrNull { it.id == id } ?: PARTY_BY_ID[id]
                NativeReplayParty(id, party?.name ?: id, party?.color ?: "#888")
            }, contestNames = (uk?.regions ?: country!!.regions).associate { it.id to it.name })
        return append(log, worldSnapshot(source))
    }

    // Resume only a matching log, trim undone future entries, and fill genuinely
    // missing current entries. Old world saves start at their actual loaded turn.
    fun restore(json: String?, snapshot: String): NativeReplayLog {
        val baseline = start(snapshot, "casual")
        val source = replaySource(snapshot)
        val saved = json?.takeIf { it.length <= 500_000 }?.let { runCatching { EngineJson.decodeFromString<NativeReplayLog>(it) }.getOrNull() }
        if (saved == null || saved.version != 1 || saved.engine != baseline.engine || saved.scenarioId != baseline.scenarioId ||
            saved.playerId != baseline.playerId || saved.mode !in listOf("casual", "daily") ||
            saved.snapshots.any { it.turn !in 0..baseline.totalTurns || it.standings.any { row -> !row.poll.isFinite() || row.poll !in 0.0..1.0 || row.units < 0 } }) return baseline
        val current = truncate(saved, source.turn)
        return if ((current.snapshots.lastOrNull()?.turn ?: -1) < source.turn)
            append(current, source.us?.let { usSnapshot(it) } ?: worldSnapshot(source)) else current
    }

    fun record(log: NativeReplayLog, previous: String, next: String): NativeReplayLog {
        val before = replaySource(previous)
        val after = replaySource(next)
        after.us?.let { game ->
            val prior = before.us ?: return start(next, log.mode)
            val actions = prior.queuedActions.filter { it.candidate == prior.playerCandidate }.map { action ->
                val where = action.stateId?.let { id -> prior.states.firstOrNull { it.id == id }?.abbr ?: id }
                val bloc = action.blocId?.serial?.let { REPLAY_BLOC_LABELS[it] ?: it }
                val suffix = if (where != null) " in $where" else if (bloc != null) " to $bloc" else ""
                "${actionVerb(action.type.serial, "us")}$suffix"
            }
            val events = game.firedEventIds.filter { it !in prior.firedEventIds }.map { EVENTS_BY_ID[it]?.title ?: it }.toMutableList()
            for (debate in game.debateHistory.orEmpty().drop(prior.debateHistory?.size ?: 0)) {
                val outcome = if (debate.winner == "tie") "a draw" else if (debate.winner == game.playerCandidate.serial) "a win" else "a loss"
                events += "Debate: ${debate.title} ($outcome)"
            }
            return append(log, usSnapshot(game, actions, events))
        }
        val regions = before.uk?.regions ?: before.country!!.regions
        val player = before.uk?.playerParty ?: before.country!!.playerParty
        fun describe(type: String, regionId: String?): String = actionVerb(type, log.engine) +
            (regionId?.let { id -> " in ${regions.firstOrNull { it.id == id }?.abbr ?: id}" } ?: "")
        val actions = before.uk?.queuedActions?.filter { it.party == player }?.map { describe(it.type.serial, it.regionId) }
            ?: before.country!!.queuedActions.filter { it.party == player }.map { describe(it.type.serial, it.regionId) }
        val oldNewsCount = before.uk?.news?.size ?: before.country!!.news.size
        val news = after.uk?.news?.map { it.text } ?: after.country!!.news.map { it.text }
        return append(log, worldSnapshot(after, actions, news.take((news.size - oldNewsCount).coerceAtLeast(0))))
    }

    private fun usSnapshot(game: GameState, actions: List<String> = emptyList(), events: List<String> = emptyList(), at: TurnPoint? = null): NativeReplaySnapshot {
        val point = at ?: game.timeline?.lastOrNull()
        val final = if (game.phase == GamePhase.RESULT && (point == null || point.turn == game.turn)) game.result else null
        val projection = if (point == null && final == null) projectElection(game) else null
        val demEV = final?.electoralVotes?.get("dem") ?: point?.demEV ?: projection!!.ev.getValue("dem")
        val repEV = final?.electoralVotes?.get("rep") ?: point?.repEV ?: projection!!.ev.getValue("rep")
        val demPoll = final?.popularShare?.get("dem") ?: point?.demPoll ?: nationalPoll(game)
        val dem = game.playerCandidate == CandidateId.DEM
        val shares = final?.stateResults?.associate { it.stateId to it.demShare } ?: point?.demShareByState
            ?: projectElection(game).contests.associate { it.stateId to it.demShare }
        return NativeReplaySnapshot(turn = point?.turn ?: game.turn, leaderId = if (demEV == repEV) null else if (demEV > repEV) "dem" else "rep",
            standings = listOf(NativeReplayStanding("dem", demPoll, demEV), NativeReplayStanding("rep", 1 - demPoll, repEV)),
            tossupUnits = if (final != null) 0 else point?.tossupEV ?: projection!!.tossupEv,
            playerCash = point?.let { if (dem) it.demCash else it.repCash } ?: game.resources.getValue(game.playerCandidate.serial).cash,
            playerMomentum = point?.let { if (dem) it.demMomentum else it.repMomentum } ?: game.resources.getValue(game.playerCandidate.serial).nationalMomentum,
            contestShare = shares.mapValues { if (dem) it.value else 1 - it.value }, actions = actions, events = events)
    }

    private fun worldSnapshot(source: ReplaySource, actions: List<String> = emptyList(), events: List<String> = emptyList()): NativeReplaySnapshot {
        val uk = source.uk
        val country = source.country
        val ukResult = uk?.let(::projectUk)
        val countryResult = country?.let { projectCountry(it, getCountry(it.countryId)!!) }
        val player = uk?.playerParty ?: country!!.playerParty
        val standings = (uk?.parties ?: country!!.parties).map { id -> NativeReplayStanding(id,
            (ukResult?.voteShare ?: countryResult!!.voteShare)[id] ?: 0.0, (ukResult?.seats ?: countryResult!!.seats)[id] ?: 0) }
        return NativeReplaySnapshot(source.turn, standings.maxByOrNull { it.units }?.id, standings, 0,
            uk?.resources?.get(player)?.funds ?: country?.resources?.get(player)?.funds,
            uk?.resources?.get(player)?.momentum ?: country?.resources?.get(player)?.momentum,
            (ukResult?.seatResults ?: countryResult!!.seatResults).associate { it.contestId to (it.voteShare[player] ?: 0.0) }, actions, events)
    }

    fun report(log: NativeReplayLog): NativeCampaignReport {
        val snapshots = log.snapshots.sortedBy { it.turn }
        fun name(id: String) = log.parties.firstOrNull { it.id == id }?.name ?: id
        val peak = linkedMapOf<String, Int>()
        for (snapshot in snapshots) for (standing in snapshot.standings) if (standing.id != log.playerId)
            peak[standing.id] = maxOf(peak[standing.id] ?: Int.MIN_VALUE, standing.units)
        val rival = peak.maxByOrNull { it.value }?.key
        fun own(snapshot: NativeReplaySnapshot) = snapshot.standings.firstOrNull { it.id == log.playerId }
        fun opponent(snapshot: NativeReplaySnapshot) = snapshot.standings.firstOrNull { it.id == rival }
        val margins = snapshots.mapNotNull { snap -> val me = own(snap); val them = opponent(snap)
            if (me != null && them != null) NativeReportPoint(snap.turn, (me.poll - them.poll) * 100) else null }
        val units = snapshots.mapNotNull { snap -> own(snap)?.let { NativeReportPoint(snap.turn, it.units.toDouble()) } }
        var sign = 0
        val flips = mutableListOf<NativeLeadFlip>()
        for (snapshot in snapshots) {
            val me = own(snapshot) ?: continue
            val them = opponent(snapshot) ?: continue
            val next = (me.units - them.units).compareTo(0)
            if (next != 0 && sign != 0 && next != sign) flips += NativeLeadFlip(snapshot.turn, next > 0)
            if (next != 0) sign = next
        }
        val decisive = if (log.engine == "us") snapshots.lastOrNull()?.contestShare.orEmpty().map { (id, share) ->
            NativeDecisiveContest(id, log.contestNames[id] ?: id, share, (share - 0.5) * 200, share >= 0.5)
        }.sortedBy { abs(it.marginPts) }.take(8) else emptyList()
        val cash = snapshots.mapNotNull { it.playerCash }
        val actions = linkedMapOf<String, Int>()
        for (snapshot in snapshots) for (action in snapshot.actions) actions[action] = (actions[action] ?: 0) + 1
        val start = margins.firstOrNull()?.value ?: 0.0
        val finish = margins.lastOrNull()?.value ?: 0.0
        return NativeCampaignReport(snapshots.isNotEmpty(), log.playerId, name(log.playerId), rival, rival?.let(::name), log.unitLabel, log.majority,
            margins, units, start, finish, finish - start, units.maxOfOrNull { it.value.toInt() } ?: 0, units.minOfOrNull { it.value.toInt() } ?: 0,
            decisive, flips, if (cash.isEmpty()) null else cash.first() - cash.last(), actions.map { NativeActionCount(it.key, it.value) }.sortedByDescending { it.count },
            snapshots.flatMap { snap -> snap.events.map { NativeReportEvent(snap.turn, it) } })
    }
}

class NativeReplayTracker {
    private var log: NativeReplayLog? = null
    fun start(snapshot: String, mode: String) { log = NativeReplay.start(snapshot, mode) }
    fun restore(json: String?, snapshot: String) { log = NativeReplay.restore(json, snapshot) }
    fun json(): String? = log?.let(NativeReplay::json)
    fun record(previous: String, next: String) {
        if (replaySource(previous).turn == replaySource(next).turn) return
        log = NativeReplay.record(log ?: NativeReplay.start(previous, "casual"), previous, next)
    }
    fun rewind(snapshot: String) { log = NativeReplay.restore(json(), snapshot) }
    fun canView(finished: Boolean): Boolean = log?.let { it.snapshots.isNotEmpty() && (it.mode != "daily" || finished) } ?: false
    fun document(selectedTurn: String?): NativeAnalysisDocument? = log?.takeIf { it.snapshots.isNotEmpty() }?.let { NativeReplay.document(it, selectedTurn) }
}

private val REPLAY_BLOC_LABELS = mapOf("noncollege_white" to "Non-college white", "college_white" to "College white",
    "suburban_women" to "Suburban women", "black" to "Black", "hispanic" to "Hispanic", "asian_other" to "AAPI / other", "seniors" to "Seniors", "youth" to "Youth")
private fun actionVerb(type: String, engine: String): String = when (type) {
    "advertise" -> "Ran ads"; "broadcast" -> "Ran a broadcast"; "rally" -> "Held a rally"; "surrogate" -> "Sent a surrogate"
    "fundraise" -> "Fundraised"; "ground_game" -> "Built ground game"; "gotv" -> "Ran GOTV"; "canvass" -> "Canvassed"
    "oppo_research" -> "Dug up oppo"; "debate_prep" -> "Prepped for debate"
    "policy_prep" -> if (engine == "us") "Prepped on policy" else if (engine == "uk") "Worked the manifesto" else "Worked the platform"
    "issue_pivot" -> "Pivoted on an issue"; else -> type
}
