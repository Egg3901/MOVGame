package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.COUNTRIES
import com.lakesidegames.electioneer.content.UK_ELECTIONS
import com.lakesidegames.electioneer.content.UK_REGIONS
import com.lakesidegames.electioneer.content.UK_TOTAL_SEATS
import com.lakesidegames.electioneer.content.getCountry
import com.lakesidegames.electioneer.content.majorityForElection
import com.lakesidegames.electioneer.content.poolFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Phase 1, issue #19: US/UK/country facades. UK 2024 neutral reproduces the
// landslide; every country election reproduces its real result within
// largest-remainder wobble. Exact seat maps cross-checked against TS runs.
class AdaptersTest {

    @Test
    fun ukPoolsTotal650() {
        assertEquals(650, UK_TOTAL_SEATS)
        assertEquals(650, majorityForElection("2024").total)
        for (region in UK_REGIONS) {
            val seats = UK_ELECTIONS.getValue("2024").regions.getValue(region.id).s
            val sum = seats.values.sum()
            assertEquals(poolFor("2024", region.id), sum, region.name)
            assertEquals(region.seats, sum, region.name)
        }
    }

    @Test
    fun solvedAppealsReproduceRegionalShares() {
        val g = createUkGame(NewUkGameOptions(election = "2024", seed = 1))
        for (region in g.regions) {
            val shares = tallyRegion(region).shareByParty
            val target = region.baselineShare!!
            for (p in target.keys) {
                assertTrue(
                    kotlin.math.abs(shares.getValue(p) - target.getValue(p)) <= 0.005,
                    "${region.name} $p",
                )
            }
        }
    }

    @Test
    fun uk2024NeutralReproducesLandslide() {
        var g = createUkGame(NewUkGameOptions(election = "2024", seed = 1, playerParty = "lab"))
        while (g.phase != "result") {
            g.queuedActions = emptyList()
            g = ukAdvanceTurn(g, UkAdvanceOptions(disableAi = true))
        }
        val r = g.result!!
        assertEquals(
            mapOf(
                "lab" to 407, "con" to 125, "ref" to 5, "ld" to 73, "grn" to 4,
                "oth" to 7, "snp" to 9, "pc" to 4, "sf" to 7, "dup" to 5,
                "apni" to 1, "uup" to 1, "sdlp" to 2,
            ),
            r.seats,
        )
        assertEquals("lab", r.largestParty)
        assertTrue(r.government is Government.Majority)
    }

    @Test
    fun countryNeutralReproducesHistory() {
        for ((cid, country) in COUNTRIES) {
            for (election in country.elections.values) {
                var g = createCountryGame(country, NewCountryGameOptions(election = election.id, seed = 42))
                for (t in 0 until g.totalTurns) {
                    g = countryAdvanceTurn(g, country, CountryAdvanceOptions(disableAi = true))
                }
                val result = g.result ?: computeCountryResult(g, country)
                val expected = mutableMapOf<String, Int>()
                for (res in election.regions.values) {
                    for ((p, s) in res.s) expected[p] = (expected[p] ?: 0) + s
                }
                for ((p, s) in expected) {
                    assertTrue(
                        kotlin.math.abs((result.seats[p] ?: 0) - s) <= 2,
                        "$cid ${election.id} $p: got ${result.seats[p]}, real $s",
                    )
                }
            }
        }
    }

    @Test
    fun countrySeatMapsMatchTsExactly() {
        fun neutral(cid: String, electionId: String): Map<String, Int> {
            val country = getCountry(cid)!!
            var g = createCountryGame(country, NewCountryGameOptions(election = electionId, seed = 42))
            for (t in 0 until g.totalTurns) {
                g = countryAdvanceTurn(g, country, CountryAdvanceOptions(disableAi = true))
            }
            return (g.result ?: computeCountryResult(g, country)).seats
        }
        assertEquals(
            mapOf("lpc" to 169, "cpc" to 145, "ndp" to 6, "gpc" to 1, "oth" to 0, "bq" to 22),
            neutral("CA", "2025"),
        )
        assertEquals(
            mapOf("cdu" to 209, "spd" to 121, "afd" to 152, "grn" to 83, "lnk" to 64, "oth" to 1),
            neutral("DE", "2025"),
        )
        assertEquals(mapOf("ens" to 53, "rn" to 47), neutral("FR", "2027"))
        assertEquals(
            mapOf("alp" to 94, "lnp" to 43, "ind" to 12, "grn" to 1, "onp" to 0, "oth" to 0),
            neutral("AU", "2025"),
        )
    }

    @Test
    fun knownWinners() {
        fun largest(cid: String): String {
            val country = getCountry(cid)!!
            var g = createCountryGame(country, NewCountryGameOptions(seed = 7))
            for (t in 0 until g.totalTurns) {
                g = countryAdvanceTurn(g, country, CountryAdvanceOptions(disableAi = true))
            }
            return (g.result ?: computeCountryResult(g, country)).largestParty
        }
        assertEquals("lpc", largest("CA"))
        assertEquals("cdu", largest("DE"))
        assertEquals("ens", largest("FR"))
    }

    @Test
    fun germanFirewallHolds() {
        val country = getCountry("DE")!!
        for (seed in 1..5) {
            var g = createCountryGame(
                country,
                NewCountryGameOptions(seed = seed.toLong(), playerParty = "afd"),
            )
            for (t in 0 until g.totalTurns + 2) {
                if (g.phase == "result") break
                g = countryAdvanceTurn(g, country, CountryAdvanceOptions(autoResolvePlayerEvents = true))
            }
            val gov = g.result!!.government
            if (gov is Government.Coalition) assertTrue(!gov.parties.contains("afd"))
            if (gov is Government.ConfidenceSupply) assertTrue(gov.partner != "afd")
        }
    }

    @Test
    fun playedGameCompletesAndScores() {
        val country = getCountry("CA")!!
        assertTrue(playablePartiesIn(country, "2025").contains("cpc"))
        var g = createCountryGame(country, NewCountryGameOptions(seed = 99, playerParty = "cpc"))
        for (t in 0 until g.totalTurns + 2) {
            if (g.phase == "result") break
            g.queuedActions = listOf(
                CountryAction(type = CountryActionType.RALLY, party = "cpc", regionId = "ON"),
                CountryAction(type = CountryActionType.CANVASS, party = "cpc", regionId = "QC"),
                CountryAction(type = CountryActionType.FUNDRAISE, party = "cpc"),
            )
            g = countryAdvanceTurn(g, country, CountryAdvanceOptions(autoResolvePlayerEvents = true))
        }
        assertEquals("result", g.phase)
        assertEquals("lpc", g.result!!.largestParty)
        assertEquals(
            mapOf("lpc" to 176, "cpc" to 122, "ndp" to 6, "gpc" to 1, "oth" to 0, "bq" to 38),
            g.result!!.seats,
        )
        val facts = multipartyScoreFacts(
            g.result!!.seats, g.result!!.voteShare, "cpc", 172, 343, "normal",
        )
        val score = computeScoreFromFacts(facts)
        assertTrue(score in 0..1000)
    }
}
