package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.*
import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable data class NativeRevealParty(val id: String, val short: String, val color: String)
@Serializable data class NativeRevealUnit(val id: String, val name: String, val abbr: String, val winnerId: String,
    val winnerColor: String, val winnerShort: String, val units: Int, val margin: Double,
    val upset: Boolean = false, val allocation: Map<String, Int>? = null)
@Serializable data class NativeRevealData(val title: String, val totalUnits: Int, val threshold: Int,
    val parties: List<NativeRevealParty>, val units: List<NativeRevealUnit>, val playerPartyId: String,
    val unitLabel: String, val noMajorityLabel: String, val storageKey: String)
@Serializable data class NativeRevealEntry(val at: Double, val kind: String, val unit: NativeRevealUnit? = null,
    val wave: Int? = null, val isCliff: Boolean? = null, val projectedId: String? = null)
@Serializable data class NativeRevealSchedule(val entries: List<NativeRevealEntry>, val waveCount: Int, val projectedId: String?)
data class NativeRevealTally(val id: String, val name: String, val color: String, val units: Int)
data class NativeRevealBoard(val status: String, val rows: List<NativeRevealTally>, val calledUnits: Int,
    val remaining: Int, val projection: String?, val lastCall: String?, val called: List<NativeRevealUnit>, val finished: Boolean)
@Serializable private data class RevealWorldSave(val uk: UkGameState? = null, val country: CountryGameState? = null)

object NativeReveal {
    fun data(snapshot: String): NativeRevealData? {
        loadGame(snapshot)?.let { saved ->
            val game = saved.state
            val result = game.result ?: return null
            val units = result.stateResults.map { row ->
                val state = game.states.firstOrNull { it.id == row.stateId }
                val candidate = game.candidates.getValue(row.winner.serial)
                NativeRevealUnit(row.stateId, state?.name ?: row.stateId, state?.abbr ?: row.stateId,
                    row.winner.serial, candidate.color, candidate.shortName, row.electoralVotes, row.margin,
                    state?.let { (row.winner == CandidateId.DEM) != (it.prior2020DemShare > 0.5) } ?: false)
            }
            val scenarioId = game.scenarioId ?: "2020"
            val title = customDocument(game.customScenario)?.label ?: (SCENARIOS[scenarioId]?.year ?: scenarioId).toString()
            return NativeRevealData("Election Night · $title", units.sumOf { it.units }, 270,
                listOf("dem", "rep").map { id -> game.candidates.getValue(id).let { NativeRevealParty(id, it.shortName, it.color) } },
                units, game.playerCandidate.serial, "EV", "269-269: ELECTION GOES TO THE HOUSE", "reveal-${game.seed}-us-$scenarioId")
        }
        val world = runCatching { EngineJson.decodeFromString<RevealWorldSave>(snapshot) }.getOrNull() ?: return null
        val uk = world.uk
        val country = world.country
        if ((uk == null) == (country == null)) return null
        val bundle = country?.let { countryForGame(it) } ?: if (country != null) return null else null
        val seatResults = uk?.result?.seatResults ?: country?.result?.seatResults ?: return null
        val seats = uk?.result?.seats ?: country!!.result!!.seats
        val regions = uk?.regions ?: country!!.regions
        fun party(id: String): NativeRevealParty {
            val p = bundle?.system?.parties?.firstOrNull { it.id == id } ?: PARTY_BY_ID[id]
            return NativeRevealParty(id, p?.shortName ?: id.uppercase(), p?.color ?: "#888")
        }
        val units = seatResults.map { row ->
            val region = regions.firstOrNull { it.id == row.contestId }
            val p = party(row.winner)
            val shares = row.voteShare.values.sortedDescending()
            val baselineWinner = region?.baselineSeats?.maxByOrNull { it.value }?.key
            NativeRevealUnit(row.contestId, row.name, region?.abbr ?: row.contestId, row.winner, p.color, p.short,
                row.totalSeats, ((shares.getOrNull(0) ?: 0.0) - (shares.getOrNull(1) ?: 0.0)) * 100,
                baselineWinner != null && baselineWinner != row.winner, row.seatsByParty)
        }
        val order = bundle?.system?.parties?.map { it.id } ?: listOf("lab", "con", "ld", "ref", "grn", "snp", "pc", "dup", "sf", "uup", "sdlp", "apni", "oth")
        val winners = seats.keys.filter { (seats[it] ?: 0) > 0 }.sortedWith(
            compareByDescending<String> { seats[it] ?: 0 }.thenBy { order.indexOf(it).takeIf { index -> index >= 0 } ?: 99 })
        val parties = winners.take(4).map(::party).toMutableList()
        if (winners.size > parties.size) parties += NativeRevealParty("__others", "OTH", "#5b6b8c")
        val total = if (uk != null) units.sumOf { it.units } else majorityFor(country!!, bundle!!).total
        val threshold = if (uk != null) total / 2 + 1 else majorityFor(country!!, bundle!!).threshold
        val id = uk?.electionId ?: country!!.electionId
        val countryId = country?.countryId?.lowercase() ?: "uk"
        return NativeRevealData("Election Night · ${uk?.label ?: country!!.label}", total, threshold, parties, units,
            uk?.playerParty ?: country!!.playerParty, bundle?.unitNamePlural ?: "seats", "NO OVERALL MAJORITY: HUNG PARLIAMENT",
            "reveal-${uk?.seed ?: country!!.seed}-$countryId-$id")
    }

    fun schedule(data: NativeRevealData): NativeRevealSchedule {
        val sorted = data.units.sortedByDescending { it.margin }
        val cliffN = minOf(8, sorted.size / 3)
        val safe = sorted.dropLast(cliffN)
        val cliff = sorted.takeLast(cliffN)
        val waves = maxOf(1, minOf(6, ceil(safe.size / 3.0).toInt()))
        val perWave = ceil(safe.size.toDouble() / waves).toInt()
        val entries = mutableListOf<NativeRevealEntry>()
        val tally = mutableMapOf<String, Int>()
        var projected: String? = null
        var time = 1300.0
        fun call(unit: NativeRevealUnit, wave: Int? = null, isCliff: Boolean? = null) {
            val at = time
            var projection: String? = null
            for ((party, seats) in unit.allocation ?: mapOf(unit.winnerId to unit.units)) {
                tally[party] = (tally[party] ?: 0) + seats
                if (projected == null && tally.getValue(party) >= data.threshold) {
                    projected = party; projection = party; time += 2200
                }
            }
            entries += NativeRevealEntry(at, "call", unit, wave, isCliff, projection)
        }
        for (wave in 0 until waves) {
            val slice = safe.drop(wave * perWave).take(perWave)
            if (slice.isEmpty()) continue
            entries += NativeRevealEntry(time, "wave", wave = wave + 1)
            time += 400
            for (unit in slice) { call(unit, wave + 1); time += 110 }
            time += 1800
        }
        if (cliff.isNotEmpty()) {
            entries += NativeRevealEntry(time, "cliff")
            time += 1700
            for ((i, unit) in cliff.withIndex()) {
                time += 1000 + (i.toDouble() / maxOf(1, cliff.size - 1)) * 1300
                call(unit, isCliff = true)
            }
        }
        entries += NativeRevealEntry(time + 1400, "end")
        return NativeRevealSchedule(entries, waves, projected)
    }
}

class NativeElectionNight private constructor(private val data: NativeRevealData, private val sequence: NativeRevealSchedule,
    private val geography: NativeMap) {
    companion object {
        fun create(snapshot: String): NativeElectionNight? {
            val data = NativeReveal.data(snapshot) ?: return null
            val world = MobileCampaign.restore(snapshot)
            val map = world?.map() ?: nativeMaps.getValue("US")
            return NativeElectionNight(data, NativeReveal.schedule(data), map)
        }
    }
    fun data(): NativeRevealData = data
    fun map(): NativeMap = geography
    fun count(): Int = sequence.entries.size
    fun delay(index: Int, speed: Int): Double {
        if (index !in sequence.entries.indices) return 0.0
        val previous = sequence.entries.getOrNull(index - 1)?.at ?: 0.0
        return maxOf(30.0, (sequence.entries[index].at - previous) / speed.coerceAtLeast(1))
    }
    fun board(index: Int): NativeRevealBoard {
        val executed = sequence.entries.take(index.coerceIn(0, count()))
        val calls = executed.mapNotNull { it.unit }
        val tally = mutableMapOf<String, Int>()
        for (unit in calls) for ((party, seats) in unit.allocation ?: mapOf(unit.winnerId to unit.units))
            tally[party] = (tally[party] ?: 0) + seats
        val called = calls.sumOf { it.units }
        val known = data.parties.filter { it.id != "__others" }.sumOf { tally[it.id] ?: 0 }
        val rows = data.parties.map { p -> NativeRevealTally(p.id, p.short, p.color,
            if (p.id == "__others") maxOf(0, called - known) else tally[p.id] ?: 0) }
        val finished = index >= count()
        val projection = executed.firstOrNull { it.projectedId != null }?.projectedId?.let { party ->
            "PROJECTED: ${data.parties.firstOrNull { it.id == party }?.short ?: party.uppercase()}"
        } ?: if (finished) data.noMajorityLabel else null
        val last = calls.lastOrNull()
        val wave = executed.lastOrNull { it.wave != null }?.wave ?: 0
        val status = if (finished) "ALL RESULTS IN" else if (executed.any { it.kind == "cliff" }) "TOO CLOSE TO CALL"
            else if (calls.isEmpty()) "POLLS ARE CLOSING" else "CALLS COMING IN · WAVE $wave OF ${sequence.waveCount}"
        return NativeRevealBoard(status, rows, called, maxOf(0, data.totalUnits - called), projection,
            last?.let { "${it.name} · ${it.winnerShort} leads" }, calls, finished)
    }
}
