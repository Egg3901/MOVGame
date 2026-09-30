package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString

@Serializable
data class NativeElection(
    val scenarioId: String, val country: String, val engine: String, val nativeId: String,
    val year: Int, val label: String, @SerialName("description") val blurb: String, val flag: String,
)

data class NativeCountry(val id: String, val name: String, val flag: String)
data class NativeParty(val id: String, val name: String, val leader: String, val color: String,
                       val charisma: Double, val energy: Double, val competence: Double, val machine: Double)
data class NativeIssue(val id: String, val name: String, val blurb: String, val salience: Double)
data class NativeStanding(val partyId: String, val name: String, val color: String, val units: Int, val voteShare: Double)
data class NativeRegion(val id: String, val name: String, val abbr: String, val totalUnits: Int,
                        val winner: String, val color: String, val playerUnits: Int, val playerShare: Double)
data class NativeChoice(val id: String, val text: String, val resultText: String)
data class NativePlan(val index: Int, val day: Int, val title: String, val detail: String, val cost: Double)
data class NativePoint(val x: Double, val y: Double)
data class NativePolygon(val points: List<NativePoint>)
data class NativeShape(val id: String, val polygons: List<NativePolygon>)
data class NativeMap(val width: Double, val height: Double, val shapes: List<NativeShape>)

@Serializable
private data class MapShape(val d: String)
@Serializable
private data class MapGeometry(val viewBox: String, val shapes: Map<String, MapShape>)

private val nativeMaps: Map<String, NativeMap> by lazy {
    EngineJson.decodeFromString<Map<String, MapGeometry>>(bundleText("maps")).mapValues { (_, map) ->
        val box = map.viewBox.split(' ').map(String::toDouble)
        require(box[0] == 0.0 && box[1] == 0.0)
        val shapes = map.shapes.map { (id, shape) ->
            // The web's generated country outlines contain polygonal M/L/Z
            // paths. Parse once into points for native Canvas/SwiftUI Path.
            require(shape.d.none { it.isLetter() && it !in "MLZmlz" })
            val polygons = shape.d.split(Regex("[Zz]")).filter { it.isNotBlank() }.map { polygon ->
                val coordinates = Regex("-?\\d+(?:\\.\\d+)?").findAll(polygon).map { it.value.toDouble() }.toList()
                require(coordinates.size % 2 == 0)
                NativePolygon(coordinates.chunked(2).map { NativePoint(it[0], it[1]) })
            }
            NativeShape(id, polygons)
        }
        NativeMap(box[2], box[3], shapes)
    }
}

private val nativeElections: List<NativeElection> by lazy {
    EngineJson.decodeFromString<List<NativeElection>>(bundleText("scenario-registry"))
}

@Serializable
private data class NativeCampaignSave(val version: Int = 1, val uk: UkGameState? = null,
                                      val country: CountryGameState? = null)

// One native interface over the existing UK and country engines. Validation,
// budget commitments, event gating and serialization are shared by both UIs.
class MobileCampaign private constructor(private var uk: UkGameState?, private var country: CountryGameState?) {
    companion object {
        fun countries(): List<NativeCountry> = listOf(NativeCountry("US", "United States", "🇺🇸"),
            NativeCountry("UK", "United Kingdom", "🇬🇧")) +
            listOf("CA", "DE", "FR", "AU").map { id ->
                val c = COUNTRIES.getValue(id)
                NativeCountry(id, c.label, c.flag)
            }

        fun elections(countryId: String): List<NativeElection> = nativeElections.filter { it.country == countryId }

        fun parties(countryId: String, electionId: String): List<NativeParty> {
            if (countryId == "UK") {
                require(electionId in UK_ELECTIONS)
                return playablePartiesIn(electionId).map { id ->
                    val p = PARTY_BY_ID.getValue(id)
                    val l = leaderFor(electionId, id, p.shortName)
                    NativeParty(id, p.shortName, l.name, p.color, l.charisma, l.energy, l.competence, l.machine)
                }
            }
            val c = COUNTRIES.getValue(countryId)
            require(electionId in c.elections)
            return playablePartiesIn(c, electionId).map { id ->
                val p = c.system.parties.first { it.id == id }
                val l = c.leaders[electionId]?.get(id)
                NativeParty(id, p.shortName, l?.name ?: "${p.shortName} leader", p.color,
                    l?.charisma ?: 55.0, l?.energy ?: 60.0, l?.competence ?: 60.0, l?.machine ?: 55.0)
            }
        }

        fun start(countryId: String, electionId: String, partyId: String, difficulty: String, seed: String): MobileCampaign {
            require(difficulty in listOf("easy", "normal", "hard") && seed.isNotBlank())
            require(parties(countryId, electionId).any { it.id == partyId })
            return if (countryId == "UK") MobileCampaign(createUkGame(NewUkGameOptions(
                seed = seed, election = electionId, playerParty = partyId, difficulty = difficulty)), null)
            else MobileCampaign(null, createCountryGame(COUNTRIES.getValue(countryId), NewCountryGameOptions(
                seed = seed, election = electionId, playerParty = partyId, difficulty = difficulty)))
        }

        fun restore(snapshot: String): MobileCampaign? = runCatching {
            val saved = EngineJson.decodeFromString<NativeCampaignSave>(snapshot)
            require(saved.version == 1 && ((saved.uk == null) != (saved.country == null)))
            val campaign = MobileCampaign(saved.uk, saved.country)
            require(elections(campaign.countryId()).any { it.nativeId == campaign.electionId() })
            require(parties(campaign.countryId(), campaign.electionId()).any { it.id == campaign.playerParty() })
            campaign
        }.getOrNull()
    }

    fun countryId(): String = country?.countryId ?: "UK"
    fun map(): NativeMap = nativeMaps.getValue(countryId())
    fun electionId(): String = uk?.electionId ?: country!!.electionId
    fun label(): String = uk?.label ?: country!!.label
    fun playerParty(): String = uk?.playerParty ?: country!!.playerParty
    fun turn(): Int = uk?.turn ?: country!!.turn
    fun totalTurns(): Int = uk?.totalTurns ?: country!!.totalTurns
    fun isOver(): Boolean = (uk?.phase ?: country!!.phase) == "result"
    fun currency(): String = bundle()?.currency ?: "£"
    fun unitName(): String = bundle()?.unitNamePlural ?: "seats"
    fun funds(): Double = uk?.resources?.getValue(playerParty())?.funds ?: country!!.resources.getValue(playerParty()).funds
    fun momentum(): Double = uk?.resources?.getValue(playerParty())?.momentum ?: country!!.resources.getValue(playerParty()).momentum
    fun slotsLeft(): Int = ((uk?.resources?.getValue(playerParty())?.actions
        ?: country!!.resources.getValue(playerParty()).actions) - plan().size).coerceAtLeast(0)
    fun saveSnapshot(): String = EngineJson.encodeToString(NativeCampaignSave(uk = uk, country = country))
    fun plannedSpend(): Double = plan().sumOf { it.cost }
    fun availableFunds(): Double = (funds() - plannedSpend()).coerceAtLeast(0.0)
    fun majority(): Int = uk?.let { majorityForUk(it).threshold } ?: majorityFor(country!!, bundle()!!).threshold
    fun totalUnits(): Int = uk?.let { majorityForUk(it).total } ?: majorityFor(country!!, bundle()!!).total
    fun goalText(): String = if (countryId() == "FR")
        "Win ${majority()} of ${totalUnits()} presidency points. Six planning weeks compress the historical two-week runoff. First-round transfers are included in the opening support. National broadcasts and platform preparation reach the whole electorate."
        else if (countryId() == "DE") "Reach ${majority()} of ${totalUnits()} seats, alone or in a compatible coalition. The 5% national threshold can exclude parties; CSU is exempt."
        else "Reach ${majority()} of ${totalUnits()} seats. A hung parliament can produce a coalition, confidence and supply, or minority government."

    private fun bundle(): CountryBundle? = country?.let { COUNTRIES.getValue(it.countryId) }
    private fun partyDefs(): List<PartyDef> = bundle()?.system?.parties ?: UK_SYSTEM.parties
    private fun name(id: String): String = partyDefs().firstOrNull { it.id == id }?.shortName ?: id.uppercase()
    private fun color(id: String): String = partyDefs().firstOrNull { it.id == id }?.color ?: "#94a3b8"
    private fun activeParties(): List<String> = uk?.parties ?: country!!.parties
    fun rivals(): List<NativeStanding> = standings().filter { it.partyId != playerParty() }

    fun standings(): List<NativeStanding> {
        val u = uk?.let { it.result ?: projectUk(it) }
        val c = country?.let { it.result ?: projectCountry(it, bundle()!!) }
        val seats = u?.seats ?: c!!.seats
        val shares = u?.voteShare ?: c!!.voteShare
        return activeParties().map { NativeStanding(it, name(it), color(it), seats[it] ?: 0, shares[it] ?: 0.0) }
            .sortedByDescending { it.units }
    }

    fun previewPlayerUnits(): Int {
        if (isOver()) return standings().first { it.partyId == playerParty() }.units
        return uk?.let { projectUkPreview(it).seats[playerParty()] ?: 0 }
            ?: projectCountryPreview(country!!, bundle()!!).seats[playerParty()] ?: 0
    }

    fun resultSummary(): NativeResultSummary? {
        if (!isOver()) return null
        val rows = standings()
        val facts = multipartyScoreFacts(rows.associate { it.partyId to it.units }, rows.associate { it.partyId to it.voteShare },
            playerParty(), majority(), totalUnits(), uk?.difficulty ?: country?.difficulty ?: "normal")
        return NativeResultSummary(computeScoreFromFacts(facts), facts.difficulty, facts.unitMargin.toInt(), facts.popularMargin, true)
    }

    fun historicalRegions(): List<NativeHistoricalRegion> {
        val regions = regions()
        return (uk?.regions ?: country!!.regions).map { state ->
            val row = regions.first { it.id == state.id }
            NativeHistoricalRegion(state.id, state.name, row.playerUnits,
                state.baselineSeats?.get(playerParty()) ?: 0,
                (row.playerShare - (state.baselineShare?.get(playerParty()) ?: 0.0)) * 100)
        }.sortedByDescending { kotlin.math.abs(it.shareSwing) }
    }

    fun regions(): List<NativeRegion> {
        val rows = uk?.let { (it.result ?: projectUk(it)).seatResults }
            ?: country!!.let { (it.result ?: projectCountry(it, bundle()!!)).seatResults }
        val contests = uk?.regions ?: country!!.regions
        return rows.map { r -> NativeRegion(r.contestId, r.name,
            contests.first { it.id == r.contestId }.abbr, r.totalSeats, name(r.winner), color(r.winner),
            r.seatsByParty[playerParty()] ?: 0, r.voteShare[playerParty()] ?: 0.0) }
    }

    fun regionStandings(regionId: String): List<NativeStanding> {
        val r = (uk?.let { (it.result ?: projectUk(it)).seatResults }
            ?: country!!.let { (it.result ?: projectCountry(it, bundle()!!)).seatResults }).first { it.contestId == regionId }
        return activeParties().map { NativeStanding(it, name(it), color(it), r.seatsByParty[it] ?: 0, r.voteShare[it] ?: 0.0) }
            .sortedByDescending { it.voteShare }
    }

    fun issues(): List<NativeIssue> {
        val salience = uk?.salience ?: country!!.salience
        return bundle()?.issues?.map { NativeIssue(it.id, it.name, it.blurb, salience[it.id] ?: 0.0) }
            ?: UK_ISSUES.map { NativeIssue(it.id, it.name, it.blurb, salience[it.id] ?: 0.0) }
    }

    fun actionTypes(): List<String> = UkActionType.entries.map { it.serial }
    private fun cost(type: String, spend: Double?): Double = when (type) {
        "broadcast" -> if (spend != null && !spend.isFinite()) Double.POSITIVE_INFINITY else (spend ?: 1.5).coerceAtLeast(0.5)
        "ground_game" -> 1.5
        "gotv" -> 1.0
        "surrogate" -> 0.25
        "oppo_research" -> 2.0
        else -> 0.0
    }

    fun plan(): List<NativePlan> {
        fun row(i: Int, type: String, region: String?, day: Int?, mode: String?, spend: Double?, issue: String?, rival: String?): NativePlan {
            val regionName = (uk?.regions ?: country!!.regions).firstOrNull { it.id == region }?.name ?: "National"
            val detail = listOfNotNull(regionName, mode, issue?.let { id -> issues().firstOrNull { it.id == id }?.name }, rival?.let(::name)).joinToString(" · ")
            return NativePlan(i, day ?: 1, type.replace('_', ' ').replaceFirstChar { it.uppercase() }, detail, cost(type, spend))
        }
        return uk?.queuedActions?.mapIndexed { i, a -> row(i, a.type.serial, a.regionId, a.day, a.mode?.serial, a.spend, a.issueId, a.targetParty) }
            ?: country!!.queuedActions.mapIndexed { i, a -> row(i, a.type.serial, a.regionId, a.day, a.mode?.serial, a.spend, a.issueId, a.targetParty) }
    }

    fun queue(type: String, regionId: String?, day: Int, mode: String?, spend: Double?, issueId: String?, targetParty: String?): Boolean {
        if (isOver() || hasPendingEvent() || slotsLeft() <= 0 || type !in actionTypes() || day !in 1..7) return false
        if (plan().count { it.day == day } >= 3 || cost(type, spend) > availableFunds() + 1e-9) return false
        if (regionId != null && (uk?.regions ?: country!!.regions).none { it.id == regionId && it.baselineShare?.containsKey(playerParty()) == true }) return false
        if (type in listOf("rally", "surrogate", "ground_game", "gotv", "canvass") && regionId == null) return false
        if (mode != null && mode !in listOf("positive", "contrast", "issue")) return false
        if (issueId != null && issues().none { it.id == issueId }) return false
        if ((type == "issue_pivot" || (type == "broadcast" && mode == "issue")) && issueId == null) return false
        if (targetParty != null && (targetParty == playerParty() || targetParty !in activeParties())) return false
        uk?.let { it.queuedActions = it.queuedActions + UkAction(UkActionType.entries.first { a -> a.serial == type },
            playerParty(), regionId, targetParty, mode?.let { m -> UkAdMode.entries.first { a -> a.serial == m } }, issueId, spend, day) }
        country?.let { it.queuedActions = it.queuedActions + CountryAction(CountryActionType.entries.first { a -> a.serial == type },
            playerParty(), regionId, targetParty, mode?.let { m -> CountryAdMode.entries.first { a -> a.serial == m } }, issueId, spend, day) }
        return true
    }

    fun removeAction(index: Int) {
        if (isOver()) return
        uk?.let { it.queuedActions = it.queuedActions.filterIndexed { i, _ -> i != index } }
        country?.let { it.queuedActions = it.queuedActions.filterIndexed { i, _ -> i != index } }
    }

    fun endWeek(): Boolean {
        if (isOver() || hasPendingEvent()) return false
        uk = uk?.let { ukAdvanceTurn(it, UkAdvanceOptions(autoResolvePlayerEvents = false)) }
        country = country?.let { countryAdvanceTurn(it, bundle()!!, CountryAdvanceOptions(autoResolvePlayerEvents = false)) }
        return true
    }

    fun recap(): List<RecapItem> = uk?.lastRecap ?: country!!.lastRecap
    fun news(): List<NewsItem> = uk?.news ?: country!!.news
    fun hasPendingEvent(): Boolean = uk?.pendingEvent != null || country?.pendingEvent != null
    private fun ukEvent(): UkEvent? = uk?.pendingEvent?.let { pending ->
        (UK_ELECTION_EVENTS[uk!!.electionId].orEmpty() + UK_EVENTS).firstOrNull { it.id == pending.eventId }
    }
    private fun countryEvent(): CountryEventDef? = country?.pendingEvent?.let { pending ->
        (bundle()!!.elections[country!!.electionId]?.events.orEmpty() + bundle()!!.events).firstOrNull { it.id == pending.eventId }
    }
    fun eventTitle(): String = (ukEvent()?.headline ?: countryEvent()?.headline ?: "Campaign decision").replace("{party}", name(playerParty()))
    fun eventPrompt(): String = ukEvent()?.prompt ?: "Choose your response. This decision must be resolved before the next week."
    fun eventChoices(): List<NativeChoice> = ukEvent()?.choices?.map { NativeChoice(it.id, it.text, it.resultText) }
        ?: countryEvent()?.choices?.map { NativeChoice(it.id, it.text, it.resultText) }.orEmpty()
    fun answerEvent(choiceId: String): Boolean {
        if (eventChoices().none { it.id == choiceId }) return false
        uk = uk?.let { resolveUkPlayerEvent(it, choiceId) }
        country = country?.let { resolveCountryPlayerEvent(it, bundle()!!, choiceId) }
        return true
    }

    fun outcome(): String {
        val government = uk?.let { (it.result ?: projectUk(it)).government }
            ?: country!!.let { (it.result ?: projectCountry(it, bundle()!!)).government }
        bundle()?.governmentText?.invoke(government, ::name)?.let { return it }
        return when (government) {
            is Government.Majority -> "${name(government.party)} forms a majority government with ${government.seats} seats."
            is Government.Coalition -> "${government.parties.joinToString(" and ", transform = ::name)} form a coalition with ${government.seats} seats."
            is Government.ConfidenceSupply -> "${name(government.lead)} governs with confidence and supply from ${name(government.partner)}."
            is Government.Minority -> "${name(government.party)} forms a minority government with ${government.seats} seats."
            is Government.Hung -> "Hung parliament. ${name(government.largest)} is the largest party."
        }
    }

    fun resultCauses(): List<String> = (uk?.result?.postMortem ?: country?.result?.postMortem).orEmpty().map { it.cause }
}
