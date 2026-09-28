package com.lakesidegames.electioneer.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Phase 1, issue #20: contract JSON round-trips with TS-string keys, and
// TS-produced JSON decodes. TS_GAME_JSON / TS_EVENT_JSON / TS_GOV_JSON are
// verbatim outputs of the TS sources (scripts in /tmp).
class SerializationTest {

    @Test
    fun tsGameJsonDecodes() {
        val g = EngineJson.decodeFromString<GameState>(TS_GAME_JSON)
        assertEquals(1019336181L, g.seed)
        assertEquals(GamePhase.INTEL, g.phase)
        assertEquals(CandidateId.DEM, g.playerCandidate)
        assertEquals(1, g.states.size)
        assertEquals("PA", g.states[0].id)
        assertEquals(8, g.states[0].blocs.size)
        assertEquals("Joe Biden", g.candidates.getValue("dem").name)
        assertEquals(245000000.0, g.resources.getValue("dem").cash)
        assertEquals(1.15, g.playerEdge)
        // Re-encoding a decoded game is stable.
        val g2 = EngineJson.decodeFromString<GameState>(EngineJson.encodeToString(GameState.serializer(), g))
        assertEquals(g, g2)
    }

    @Test
    fun tsEventJsonDecodes() {
        val e = EngineJson.decodeFromString<GameEvent>(TS_EVENT_JSON)
        assertEquals("debate_1", e.id)
        assertEquals(EventTrigger.Scheduled(turn = 4), e.trigger)
        assertEquals(3, e.choices.size)
        assertEquals(70.0, e.choices.first { it.id == "policy_wonk" }.requires?.min)
    }

    @Test
    fun tsGovernmentJsonDecodes() {
        val gov = EngineJson.decodeFromString<Government>(TS_GOV_JSON)
        assertEquals(Government.Majority(party = "lab", seats = 407), gov)
    }

    @Test
    fun fullGameRoundTrips() {
        val g = createGame(NewGameOptions(seed = "calibration"))
        val back = EngineJson.decodeFromString<GameState>(EngineJson.encodeToString(GameState.serializer(), g))
        assertEquals(g, back)
        assertEquals(
            computeResult(g).electoralVotes,
            computeResult(back).electoralVotes,
        )
    }

    @Test
    fun serialStringsAreTsStrings() {
        val json = EngineJson.encodeToString(GameState.serializer(), createGame(NewGameOptions(seed = "s")))
        assertTrue(json.contains("\"phase\":\"intel\""), "phase serial")
        assertTrue(json.contains("\"playerCandidate\":\"dem\""), "candidate serial")
        assertTrue(json.contains("\"noncollege_white\""), "bloc serial")
    }
}

private const val TS_GAME_JSON = """{"seed":1019336181,"rngState":1019336181,"turn":0,"totalTurns":9,"granularity":"week","phase":"intel","playerCandidate":"dem","scenarioId":"2020","eventMode":"historical","playerEdge":1.15,"locations":null,"candidates":{"dem":{"id":"dem","name":"Joe Biden","shortName":"Biden","party":"Democratic","color":"#2563eb","runningMate":"Kamala Harris","traits":{"charisma":68,"energy":55,"debatePrep":70,"intelligence":68,"policyKnowledge":92,"debatingSkill":69,"fundraisingProwess":78},"baseTraits":{"charisma":62,"energy":55,"debatePrep":70,"intelligence":68,"policyKnowledge":92,"debatingSkill":64,"fundraisingProwess":78},"issuePositions":{"economy":-0.25,"covid_response":-0.55,"healthcare":-0.45,"immigration":-0.35,"race_policing":-0.4,"climate":-0.5,"taxes":-0.3,"law_and_order":-0.1,"abortion":-0.5,"trade":-0.05},"baseFavorability":{"black":0.43,"college_white":0.12,"suburban_women":0.24,"seniors":0.05,"asian_other":0.05}},"rep":{"id":"rep","name":"Donald Trump","shortName":"Trump","party":"Republican","color":"#dc2626","runningMate":"Mike Pence","traits":{"charisma":74,"energy":70,"debatePrep":66,"intelligence":38,"policyKnowledge":62,"debatingSkill":66,"fundraisingProwess":70},"baseTraits":{"charisma":74,"energy":70,"debatePrep":60,"intelligence":38,"policyKnowledge":58,"debatingSkill":66,"fundraisingProwess":70},"issuePositions":{"economy":0.5,"covid_response":0.5,"healthcare":0.4,"immigration":0.75,"race_policing":0.55,"climate":0.6,"taxes":0.55,"law_and_order":0.7,"abortion":0.5,"trade":0.45},"baseFavorability":{"noncollege_white":0.37,"seniors":0.14}}},"issues":{"economy":{"id":"economy","name":"The Economy & Jobs","baseSalience":0.85,"blurb":"Recovery from the pandemic recession, jobs, and the stock market."},"covid_response":{"id":"covid_response","name":"COVID-19 Response","baseSalience":0.9,"blurb":"Masks, lockdowns, vaccines, and the federal pandemic response."},"healthcare":{"id":"healthcare","name":"Healthcare","baseSalience":0.62,"blurb":"The ACA, pre-existing conditions, and the cost of care."},"immigration":{"id":"immigration","name":"Immigration","baseSalience":0.48,"blurb":"The border, DACA, and the family-separation legacy."},"race_policing":{"id":"race_policing","name":"Race & Policing","baseSalience":0.7,"blurb":"Policing reform and racial justice after the summer of protest."},"climate":{"id":"climate","name":"Climate & Energy","baseSalience":0.4,"blurb":"Wildfires, the Green New Deal, and fracking."},"taxes":{"id":"taxes","name":"Taxes & Spending","baseSalience":0.45,"blurb":"Who pays, the 2017 cuts, and stimulus checks."},"law_and_order":{"id":"law_and_order","name":"Law & Order","baseSalience":0.55,"blurb":"Crime, unrest in the cities, and 'defund the police'."},"abortion":{"id":"abortion","name":"Abortion & the Courts","baseSalience":0.5,"blurb":"The Supreme Court vacancy and the future of Roe."},"trade":{"id":"trade","name":"Trade & Manufacturing","baseSalience":0.3,"blurb":"China, tariffs, and the industrial Midwest."}},"salience":{"economy":0.85,"covid_response":0.9,"healthcare":0.62,"immigration":0.48,"race_policing":0.7,"climate":0.4,"taxes":0.45,"law_and_order":0.55,"abortion":0.5,"trade":0.3},"states":[{"id":"PA","name":"Pennsylvania","abbr":"PA","electoralVotes":20,"region":"Swing","prior2020DemShare":0.506,"mediaMarketCost":1.1,"battleground":true,"blocs":[{"blocId":"noncollege_white","size":2273.3393994540493,"turnoutPropensity":0.62,"baselineMargin":-0.6834197646195391,"support":{"dem":0.33549847445221204,"rep":0.664501525547788},"campaignMargin":0,"enthusiasm":1},{"blocId":"college_white","size":1136.6696997270246,"turnoutPropensity":0.72,"baselineMargin":-0.02801291204244072,"support":{"dem":0.4929972299197703,"rep":0.5070027700802298},"campaignMargin":0,"enthusiasm":1},{"blocId":"suburban_women","size":568.3348498635123,"turnoutPropensity":0.7,"baselineMargin":0.09261507574617413,"support":{"dem":0.5231372328983582,"rep":0.47686276710164177},"campaignMargin":0,"enthusiasm":1},{"blocId":"black","size":909.3357597816196,"turnoutPropensity":0.6,"baselineMargin":2.089168957620242,"support":{"dem":0.889845993166049,"rep":0.11015400683395105},"campaignMargin":0,"enthusiasm":1},{"blocId":"hispanic","size":694.631483166515,"turnoutPropensity":0.52,"baselineMargin":0.3814926056027285,"support":{"dem":0.5942330507551487,"rep":0.40576694924485135},"campaignMargin":0,"enthusiasm":1},{"blocId":"asian_other","size":315.7415832575068,"turnoutPropensity":0.58,"baselineMargin":0.5109835886902463,"support":{"dem":0.6250370222980174,"rep":0.3749629777019826},"campaignMargin":0,"enthusiasm":1},{"blocId":"seniors","size":726.2056414922656,"turnoutPropensity":0.75,"baselineMargin":-0.18809832738951376,"support":{"dem":0.45311357733794705,"rep":0.5468864226620529},"campaignMargin":0,"enthusiasm":1},{"blocId":"youth","size":315.7415832575068,"turnoutPropensity":0.46,"baselineMargin":0.29740948839218695,"support":{"dem":0.5738091229254703,"rep":0.42619087707452974},"campaignMargin":0,"enthusiasm":1}],"groundGame":{"dem":0,"rep":0},"momentum":0}],"resources":{"dem":{"cash":245000000,"actions":11,"maxActions":11,"staffCapacity":6,"nationalMomentum":0,"mediaNarrative":0},"rep":{"cash":180000000,"actions":11,"maxActions":11,"staffCapacity":6,"nationalMomentum":0,"mediaNarrative":0}},"pendingEvents":[],"firedEventIds":[],"queuedActions":[],"causes":[],"lastRecap":[],"runningMates":{"dem":"harris","rep":"pence"},"staff":{"dem":[]},"adSpend":null,"debateHistory":null,"timeline":null,"result":null,"fundsRaised":null}"""

private const val TS_EVENT_JSON = """{"id":"debate_1","title":"First Presidential Debate (Cleveland)","prompt":"Ninety minutes of chaos and interruptions. The moderator presses you on the pandemic and the economy. How do you carry yourself on the biggest stage of the race?","subject":"both","trigger":{"kind":"scheduled","turn":4},"isDebate":true,"oncePerGame":true,"choices":[{"id":"calm_presidential","text":"Stay calm and presidential; talk to the camera, not the opponent.","effects":{"blocDeltas":[{"blocId":"suburban_women","margin":0.06,"enthusiasm":0.01},{"blocId":"seniors","margin":0.05},{"blocId":"college_white","margin":0.04},{"blocId":"noncollege_white","margin":-0.01}],"momentum":8,"narrative":10},"resultText":"You refuse to take the bait. The split-screen does the work; pundits call it steady and reassuring."},{"id":"aggressive","text":"Punch back hard; dominate every exchange and never yield the floor.","effects":{"blocDeltas":[{"blocId":"noncollege_white","margin":0.05,"enthusiasm":0.02},{"blocId":"youth","margin":0.02},{"blocId":"suburban_women","margin":-0.05},{"blocId":"seniors","margin":-0.03}],"momentum":4,"narrative":-6},"resultText":"You fire up the base, but the constant crosstalk reads as a mess to viewers at home. Suburban voters wince."},{"id":"policy_wonk","text":"Go deep on policy detail and dare them to keep up.","requires":{"trait":"policyKnowledge","min":70},"effects":{"blocDeltas":[{"blocId":"college_white","margin":0.06},{"blocId":"seniors","margin":0.03},{"blocId":"youth","margin":-0.02}],"salienceDeltas":{"healthcare":0.05,"economy":0.04},"momentum":5,"narrative":6},"resultText":"You bury the stage in specifics. Wonks swoon; some viewers tune out, but you own the substance."}]}"""

private const val TS_GOV_JSON = """{"kind":"majority","party":"lab","seats":407}"""
