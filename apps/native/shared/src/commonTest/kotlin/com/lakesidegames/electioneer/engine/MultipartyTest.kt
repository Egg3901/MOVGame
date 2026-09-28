package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Phase 1, issue #17: multiparty vote model, seats curve, government calls,
// polls, and AI. Vectors from running the TS sources on identical fixtures
// (scripts in /tmp). Doubles from exp/normal paths assert within tolerance.
class MultipartyTest {

    private fun close(expected: Double, actual: Double, label: String) {
        assertTrue(kotlin.math.abs(expected - actual) <= 1e-9, "$label expected $expected but was $actual")
    }

    private fun bloc(appeal: Map<String, Double>, camp: Map<String, Double> = emptyMap(), size: Double = 1000.0) =
        StateBloc(
            blocId = BlocId.NONCOLLEGE_WHITE, size = size, turnoutPropensity = 0.6,
            baselineMargin = 0.0, support = emptyMap(), campaignMargin = 0.0, enthusiasm = 1.0,
            appeal = appeal.toMutableMap(), campaignAppeal = camp.toMutableMap(),
        )

    private fun region(
        id: String, seats: Int, base: Map<String, Int>, share: Map<String, Double>, blocs: List<StateBloc>,
    ) = StateContest(
        id = id, name = id, abbr = id, electoralVotes = 0, region = Region.SWING,
        prior2020DemShare = 0.5, mediaMarketCost = 1.0, battleground = false,
        blocs = blocs, groundGame = mutableMapOf(), momentum = 0.0,
        seats = seats, baselineSeats = base, baselineShare = share, seatElasticity = 2.6,
    )

    private val lon = region(
        "LON", 73,
        mapOf("lab" to 40, "con" to 25, "ld" to 8),
        mapOf("lab" to 0.45, "con" to 0.35, "ld" to 0.2),
        listOf(
            bloc(mapOf("lab" to 0.5, "con" to 0.1, "ld" to -0.2)),
            bloc(mapOf("lab" to 0.0, "con" to 0.4, "ld" to 0.0), size = 800.0),
        ),
    )
    private val mid = region(
        "MID", 50,
        mapOf("lab" to 20, "con" to 25, "ld" to 5),
        mapOf("lab" to 0.38, "con" to 0.42, "ld" to 0.2),
        listOf(bloc(mapOf("lab" to 0.2, "con" to 0.3, "ld" to -0.1), size = 1200.0)),
    )
    private val maj = MajorityRule(total = 650, threshold = 326)

    @Test
    fun softmaxMatchesTs() {
        val s = softmax(mapOf("lab" to 0.5, "con" to 0.2, "ld" to -0.1))
        close(0.4367518169107908, s.getValue("lab"), "lab")
        close(0.32355370388335947, s.getValue("con"), "con")
        close(0.2396944792058498, s.getValue("ld"), "ld")
        assertTrue(softmax(emptyMap()).isEmpty())
    }

    @Test
    fun blocSharesAndTallyMatchTs() {
        val shares = blocPartyShares(bloc(mapOf("lab" to 0.5, "con" to 0.2), mapOf("lab" to 0.1)))
        close(0.598687660112452, shares.getValue("lab"), "lab")
        close(0.401312339887548, shares.getValue("con"), "con")
        val t = tallyRegion(lon)
        close(414.3565195526769, t.votesByParty.getValue("lab"), "votes")
        close(390.678751934002, t.votesByParty.getValue("con"), "votesC")
        assertEquals(1080.0, t.totalVotes)
        close(0.3836634440302564, t.shareByParty.getValue("lab"), "share")
    }

    @Test
    fun largestRemainderMatchesTs() {
        assertEquals(mapOf("a" to 11, "b" to 5, "c" to 2), largestRemainder(mapOf("a" to 10.4, "b" to 5.4, "c" to 2.2), 18))
        // Ties keep content order.
        assertEquals(mapOf("a" to 6, "b" to 5), largestRemainder(mapOf("a" to 5.5, "b" to 5.5), 11))
        assertEquals(mapOf("a" to 0, "b" to 0), largestRemainder(mapOf("a" to 0.0, "b" to 0.0), 5))
    }

    @Test
    fun seatsCurveMatchesTs() {
        val neutral = tallyRegion(lon).shareByParty
        assertEquals(mapOf("lab" to 28, "con" to 27, "ld" to 18), allocateRegionSeats(lon, neutral))
        val sw = neutral.mapValues { (p, s) -> s + if (p == "lab") 0.05 else if (p == "con") -0.05 else 0.0 }
        assertEquals(mapOf("lab" to 37, "con" to 18, "ld" to 18), allocateRegionSeats(lon, sw))
        // Exact neutral reproduces the real baseline seats.
        assertEquals(
            mapOf("lab" to 40, "con" to 25, "ld" to 8),
            allocateRegionSeats(lon, mapOf("lab" to 0.45, "con" to 0.35, "ld" to 0.2)),
        )
    }

    @Test
    fun seatsResultMatchesTs() {
        val r = computeSeatsResult(listOf(lon, mid), maj)
        assertEquals(mapOf("lab" to 44, "con" to 48, "ld" to 31), r.seats)
        close(0.37074674053384304, r.voteShare.getValue("lab"), "natLab")
        assertEquals(2, r.seatResults.size)
        assertEquals("lab", r.seatResults[0].winner)
        assertEquals("con", r.seatResults[1].winner)
        assertEquals("con", r.largestParty)
        assertTrue(r.hung)
        assertEquals(Government.Hung(largest = "con"), r.government)
    }

    @Test
    fun governmentCallsMatchTs() {
        assertEquals(
            Government.Majority(party = "a", seats = 330),
            formGovernment(mapOf("a" to 330, "b" to 250), maj, emptyList(), "a"),
        )
        assertEquals(
            Government.Coalition(parties = listOf("a", "c"), seats = 550),
            formGovernment(mapOf("a" to 300, "b" to 100, "c" to 250), maj, emptyList(), "a"),
        )
        assertEquals(
            Government.Coalition(parties = listOf("c", "a"), seats = 630),
            formGovernment(mapOf("a" to 310, "b" to 20, "c" to 320), maj, emptyList(), "c"),
        )
        assertEquals(
            Government.Coalition(parties = listOf("a", "c"), seats = 550),
            formGovernment(mapOf("a" to 290, "b" to 100, "c" to 260), maj, emptyList(), "a"),
        )
        assertEquals(
            Government.Coalition(parties = listOf("c", "a"), seats = 450),
            formGovernment(mapOf("a" to 200, "b" to 200, "c" to 250), maj, emptyList(), "c"),
        )
        assertEquals(
            Government.Coalition(parties = listOf("a", "b"), seats = 620),
            formGovernment(
                mapOf("a" to 320, "b" to 300, "sf" to 7),
                MajorityRule(total = 650, threshold = 326), listOf("sf"), "a",
            ),
        )
    }

    @Test
    fun multipartyPollsMatchTs() {
        val polls = pollRegion(7, 2, lon)
        assertEquals(4, polls.size)
        assertEquals("YouGov", polls[0].pollster)
        close(0.39507220326192016, polls[0].shareByParty.getValue("lab"), "youGovLab")
        assertEquals(1800, polls[0].sampleSize)
        assertEquals(2.3, polls[0].marginOfError)
        assertEquals(2.5, polls[1].marginOfError)
        assertEquals(2.8, polls[2].marginOfError)
        assertEquals(2.6, polls[3].marginOfError)
        close(0.3827079625141003, polls[1].shareByParty.getValue("lab"), "ipsosLab")
        val nat = nationalMpPoll(7, 2, listOf(lon, mid))
        close(0.37674973357190406, nat.getValue("lab"), "natLab")
        close(0.37515819055201316, nat.getValue("con"), "natCon")
        close(0.2480920758760828, nat.getValue("ld"), "natLd")
    }

    @Test
    fun multipartyAiMatchesTs() {
        val view = MpView(regions = listOf(lon, mid), turn = 6, totalTurns = 9, funds = 10.0, actions = 5)
        val targets = mpTargets(view, "lab", 4)
        assertEquals(listOf("LON", "MID"), targets.map { it.region.id })
        close(0.021923858906180482, targets[0].margin, "lonMargin")
        assertEquals("con", targets[0].rivalParty)
        close(-0.03695408272765516, targets[1].margin, "midMargin")

        val focused = mpFocusedActions(view, "lab", MP_DIFFICULTY.getValue("normal"), Rng.createRng("mp1"))
        assertEquals(
            listOf(
                MpActionLike("rally", "lab", "LON"),
                MpActionLike("broadcast", "lab", "LON", spend = 1.5, mode = "positive", targetParty = "con"),
                MpActionLike("ground_game", "lab", "MID"),
                MpActionLike("canvass", "lab", "LON"),
                MpActionLike("canvass", "lab", "MID"),
            ),
            focused,
        )
        val plan = planMultipartyAi(view, "con", "hard", Rng.createRng("mp2"))
        assertEquals(
            listOf(
                MpActionLike("rally", "con", "LON"),
                MpActionLike("broadcast", "con", "LON", spend = 1.5, mode = "contrast", targetParty = "lab"),
                MpActionLike("ground_game", "con", "MID"),
                MpActionLike("canvass", "con", "LON"),
                MpActionLike("canvass", "con", "MID"),
            ),
            plan,
        )
    }
}
