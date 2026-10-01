package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.*

data class NativeAnalysisRow(val label: String, val value: String)
data class NativeAnalysisSection(val id: String, val title: String, val rows: List<NativeAnalysisRow>)
data class NativeAnalysisRegion(val id: String, val name: String)
data class NativeTrendPoint(val turn: Int, val value: Double)
data class NativeTrendSeries(val label: String, val color: String, val points: List<NativeTrendPoint>)
data class NativeTrendChart(val id: String, val title: String, val minimum: Double, val maximum: Double,
    val reference: Double, val referenceLabel: String, val series: List<NativeTrendSeries>)
data class NativeAnalysisDocument(val regions: List<NativeAnalysisRegion>, val selectedRegion: String,
    val sections: List<NativeAnalysisSection>, val charts: List<NativeTrendChart> = emptyList())

private fun percent(value: Double): String = "${toFixed1(value * 100)}%"
private fun points(value: Double): String = toFixed1(value)

// Read-only analysis from live engine state. Neither platform calculates polls
// or reveals a different view of the campaign model.
object NativeAnalysis {
    fun us(game: GameState, target: String?): NativeAnalysisDocument {
        val regions = game.states.filter { it.blocs.isNotEmpty() }
        val region = regions.firstOrNull { it.id == target } ?: regions.first()
        val candidates = game.candidates.values.toList()
        val sections = mutableListOf<NativeAnalysisSection>()
        for (candidate in candidates) {
            val traits = candidate.traits
            val resource = game.resources.getValue(candidate.id.serial)
            sections += NativeAnalysisSection("candidate-${candidate.id.serial}", "${candidate.name} · ${candidate.runningMate}", listOf(
                NativeAnalysisRow("Charisma", points(traits.charisma)), NativeAnalysisRow("Energy", points(traits.energy)),
                NativeAnalysisRow("Debate preparation", points(traits.debatePrep)), NativeAnalysisRow("Debating skill", points(traits.debatingSkill)),
                NativeAnalysisRow("Policy command", points(traits.policyKnowledge)), NativeAnalysisRow("Intelligence", points(traits.intelligence)),
                NativeAnalysisRow("Fundraising", points(traits.fundraisingProwess)), NativeAnalysisRow("Cash", "\$${points(resource.cash / 1_000_000)}M"),
                NativeAnalysisRow("Momentum", points(resource.nationalMomentum)), NativeAnalysisRow("Media narrative", points(resource.mediaNarrative)),
                NativeAnalysisRow("Staff", game.staff?.get(candidate.id.serial).orEmpty().joinToString { STAFF_BY_ID[it]?.name ?: it }.ifEmpty { "None" }),
            ))
        }
        sections += NativeAnalysisSection("issues", "Issues and candidate positions", game.issues.values.map { issue ->
            NativeAnalysisRow("${issue.name} · salience ${percent(game.salience[issue.id.serial] ?: issue.baseSalience)}",
                candidates.joinToString(" · ") { "${it.shortName} ${points(it.issuePositions[issue.id.serial] ?: 0.0)}" })
        } + NativeAnalysisRow("Position scale", "-1 left · 0 centre · +1 right"))
        sections += NativeAnalysisSection("polls", "${region.name} polls", pollState(game, region.id).map {
            NativeAnalysisRow("${it.pollster} · n=${it.sampleSize} · ±${points(it.marginOfError)} points",
                "${game.candidates.getValue("dem").shortName} ${percent(it.demShare)} · ${game.candidates.getValue("rep").shortName} ${percent(1 - it.demShare)}")
        } + NativeAnalysisRow("National polling average", "${game.candidates.getValue("dem").shortName} ${percent(nationalPoll(game))}"))
        sections += NativeAnalysisSection("blocs", "${region.name} voters", region.blocs.map { bloc ->
            NativeAnalysisRow(BLOCS[bloc.blocId]?.name ?: bloc.blocId.serial,
                "${bloc.size.toLong()} voters · turnout ${percent(bloc.turnoutPropensity * bloc.enthusiasm)} · ${game.candidates.getValue("dem").shortName} ${percent(liveBlocDemShare(region, bloc))}")
        })
        sections += NativeAnalysisSection("trends", "Campaign trends", game.timeline.orEmpty().map { point ->
            val playerDem = game.playerCandidate == CandidateId.DEM
            NativeAnalysisRow("Week ${point.turn}", "${if (playerDem) point.demEV else point.repEV} EV · poll ${percent(if (playerDem) point.demPoll else 1 - point.demPoll)} · \$${points((if (playerDem) point.demCash else point.repCash) / 1_000_000)}M · momentum ${points(if (playerDem) point.demMomentum else point.repMomentum)}")
        })
        val timeline = game.timeline.orEmpty()
        val playerDem = game.playerCandidate == CandidateId.DEM
        fun playerMargin(point: TurnPoint): Double = ((if (playerDem) point.demPoll else 1 - point.demPoll) * 2 - 1) * 100
        val charts = if (timeline.isEmpty()) emptyList() else {
            val margins = timeline.map(::playerMargin)
            var previousSign = 0
            var changes = 0
            for (point in timeline) {
                val sign = (point.demEV - point.repEV).compareTo(0)
                if (sign != 0 && previousSign != 0 && sign != previousSign) changes++
                if (sign != 0) previousSign = sign
            }
            val ev = timeline.map { if (playerDem) it.demEV else it.repEV }
            sections += NativeAnalysisSection("stats", "Race statistics", listOf(
                NativeAnalysisRow("Your polling margin", "${points(margins.last())} points"),
                NativeAnalysisRow("Swing since campaign start", "${points(margins.last() - margins.first())} points"),
                NativeAnalysisRow("EV lead changes", changes.toString()),
                NativeAnalysisRow("Your peak / floor EV", "${ev.max()} / ${ev.min()}"),
            ))
            fun series(candidate: Candidate, value: (TurnPoint) -> Double) = NativeTrendSeries(candidate.shortName, candidate.color,
                timeline.map { NativeTrendPoint(it.turn, value(it)) })
            val dem = game.candidates.getValue("dem")
            val rep = game.candidates.getValue("rep")
            listOf(
                NativeTrendChart("poll-margin", "Your national polling margin (points)", kotlin.math.min(-6.0, kotlin.math.floor(margins.min() - 2)),
                    kotlin.math.max(6.0, kotlin.math.ceil(margins.max() + 2)), 0.0, "Tie",
                    listOf(series(game.candidates.getValue(game.playerCandidate.serial), ::playerMargin))),
                NativeTrendChart("ev", "Electoral vote projection", 0.0, 538.0, 270.0, "270 to win",
                    listOf(series(dem) { it.demEV.toDouble() }, series(rep) { it.repEV.toDouble() })),
                NativeTrendChart("momentum", "National momentum", -100.0, 100.0, 0.0, "Neutral",
                    listOf(series(dem) { it.demMomentum }, series(rep) { it.repMomentum })),
            )
        }
        for ((index, debate) in game.debateHistory.orEmpty().withIndex()) sections += NativeAnalysisSection("debate-$index-${debate.eventId}", debate.title,
            candidates.map { NativeAnalysisRow(it.shortName, "${points(debate.scores[it.id.serial] ?: 0.0)} / 100 · ${debate.choiceText[it.id.serial].orEmpty()}") } +
                NativeAnalysisRow("Result", "${game.candidates[debate.winner]?.shortName ?: "Tie"} · momentum swing ${points(debate.momentumSwing)}${if (debate.meltdown) " · Meltdown" else ""}"))
        return NativeAnalysisDocument(regions.map { NativeAnalysisRegion(it.id, it.name) }, region.id, sections, charts)
    }

    fun world(uk: UkGameState?, country: CountryGameState?, target: String?): NativeAnalysisDocument {
        val contests = uk?.regions ?: country!!.regions
        val region = contests.firstOrNull { it.id == target } ?: contests.first()
        val countryBundle = country?.let { countryForGame(it)!! }
        val parties = uk?.parties ?: country!!.parties
        fun name(id: String): String = countryBundle?.system?.parties?.firstOrNull { it.id == id }?.shortName
            ?: PARTY_BY_ID[id]?.shortName ?: id
        val sections = mutableListOf<NativeAnalysisSection>()
        for (party in parties) {
            val leader = uk?.leaders?.get(party)
            val countryLeader = country?.leaders?.get(party)
            val resource = uk?.resources?.get(party)
            val countryResource = country?.resources?.get(party)
            sections += NativeAnalysisSection("candidate-$party", "${name(party)} · ${leader?.name ?: countryLeader?.name ?: party}", listOf(
                NativeAnalysisRow("Charisma", points(leader?.charisma ?: countryLeader!!.charisma)),
                NativeAnalysisRow("Energy", points(leader?.energy ?: countryLeader!!.energy)),
                NativeAnalysisRow("Competence", points(leader?.competence ?: countryLeader!!.competence)),
                NativeAnalysisRow("Campaign machine", points(leader?.machine ?: countryLeader!!.machine)),
                NativeAnalysisRow("Funds", "${countryBundle?.currency ?: "£"}${points(resource?.funds ?: countryResource!!.funds)}M"),
                NativeAnalysisRow("Momentum", points(resource?.momentum ?: countryResource!!.momentum)),
            ))
        }
        val issues = countryBundle?.issues?.map { Triple(it.id, it.name, it.blurb) }
            ?: UK_ISSUES.map { Triple(it.id, it.name, it.blurb) }
        val salience = uk?.salience ?: country!!.salience
        sections += NativeAnalysisSection("issues", "Issues", issues.map { (id, title, blurb) ->
            NativeAnalysisRow("$title · ${percent(salience[id] ?: 0.0)}", blurb)
        })
        sections += NativeAnalysisSection("polls", "${region.name} polls", pollRegion(uk?.seed ?: country!!.seed, uk?.turn ?: country!!.turn, region).map {
            NativeAnalysisRow("${it.pollster} · n=${it.sampleSize} · ±${points(it.marginOfError)} points",
                it.shareByParty.entries.sortedByDescending { row -> row.value }.joinToString(" · ") { row -> "${name(row.key)} ${percent(row.value)}" })
        } + NativeAnalysisRow("National polling average", nationalMpPoll(uk?.seed ?: country!!.seed, uk?.turn ?: country!!.turn, contests)
            .entries.sortedByDescending { it.value }.joinToString(" · ") { "${name(it.key)} ${percent(it.value)}" }))
        sections += NativeAnalysisSection("blocs", "${region.name} voters", region.blocs.map { bloc ->
            NativeAnalysisRow(countryBundle?.blocs?.firstOrNull { it.id == bloc.blocId.serial }?.name ?: UK_BLOCS_BY_ID[bloc.blocId.serial]?.name ?: bloc.blocId.serial.replace('_', ' '),
                "${bloc.size.toLong()} voters · turnout ${percent(bloc.turnoutPropensity * bloc.enthusiasm)} · " +
                    blocPartyShares(bloc).entries.sortedByDescending { it.value }.joinToString(" · ") { "${name(it.key)} ${percent(it.value)}" })
        })
        return NativeAnalysisDocument(contests.map { NativeAnalysisRegion(it.id, it.name) }, region.id, sections)
    }
}

// Interactive debates must resolve both tickets, exactly as the web store does.
fun answerPlayerEvent(game: GameState, eventId: String, choiceId: String): String? {
    val event = EVENTS_BY_ID[eventId] ?: return null
    if (game.pendingEvents.none { it.eventId == eventId && it.forCandidate == game.playerCandidate }) return null
    if (event.isDebate) {
        val debate = resolveDebate(game, event, mapOf(game.playerCandidate to choiceId))
        return "${debate.resultText[game.playerCandidate.serial].orEmpty()}\n\n" +
            game.candidates.values.joinToString(" · ") { "${it.shortName}: ${points(debate.scores[it.id.serial] ?: 0.0)}/100" }
    }
    return resolveEvent(game, eventId, choiceId, game.playerCandidate)
}
