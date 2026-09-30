package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable private data class NextHint(val countryId: String, val electionId: String, val label: String, val blurb: String)
private val nextHints by lazy { EngineJson.decodeFromString<Map<String, NextHint>>(bundleText("next-campaigns")) }
@Serializable data class NativeNextCampaign(val countryId: String, val electionId: String, val label: String, val blurb: String,
    val partyId: String, val difficulty: String, val eventMode: String, val mateId: String)
@Serializable data class NativeResultJourney(val shareText: String, val daily: Boolean, val score: Int, val unitLine: String,
    val next: NativeNextCampaign?)

class NativeResultsJourney private constructor() {
    companion object {
        fun create(snapshot: String, dateUTC: String): NativeResultJourney? = runCatching {
            val document = NativeSaveTransfer.inspect(snapshot) ?: return null
            val saved = loadGame(document.snapshot)
            val world = if (saved == null) MobileCampaign.restore(document.snapshot) ?: return null else null
            val summary = saved?.let { NativeResults.us(it.state, it.difficulty) } ?: world?.resultSummary() ?: return null
            val country = world?.countryId() ?: "US"
            val election = world?.electionId() ?: saved!!.state.scenarioId ?: "2020"
            val meta = nativeElections.first { it.country == country && it.nativeId == election }
            val player = world?.playerParty() ?: saved!!.state.playerCandidate.serial
            val daily = world?.isDaily(dateUTC) ?: NativeDaily.matchesUs(dateUTC, saved!!.state)
            val units = world?.standings()?.first { it.partyId == player }?.units
                ?: saved!!.state.result!!.electoralVotes[player] ?: 0
            val unitLine = "$units ${world?.unitName() ?: "EVs"}"
            val won = if (world != null) units >= world.majority() else saved!!.state.result!!.winner == player
            val role = NativeDaily.roleName(player)
            val title = "Margin of Victory${if (daily) " Daily · $dateUTC" else ""}"
            val scoreLine = if (summary.score >= 0) " · Score ${comma(summary.score)}" else ""
            val share = "$title\n${meta.flag} ${meta.label} · as $role\n${if (won) "🏆" else "🗳️"} $unitLine$scoreLine\nlakesidegames.net/games/electioneer"
            val next = nextHints[meta.scenarioId]?.let { hint ->
                val party = if (country == "US") player else {
                    val playable = MobileCampaign.parties(country, hint.electionId)
                    playable.firstOrNull { it.id == player }?.id ?: playable.first().id
                }
                val mate = if (country == "US") MobileGame.mates(hint.electionId, party).first { it.historical }.id else ""
                NativeNextCampaign(country, hint.electionId, hint.label, hint.blurb, party,
                    summary.difficulty.takeIf { it != "unknown" } ?: "normal", saved?.state?.eventMode?.serial ?: "historical", mate)
            }
            NativeResultJourney(share, daily, summary.score, unitLine, next)
        }.getOrNull()

        fun percentile(score: Int, boardScores: List<Int>?, rank: Int?): Int? {
            if (boardScores.isNullOrEmpty()) return null
            val ratio = if (rank != null) rank.toDouble() / maxOf(boardScores.size, rank)
                else (boardScores.count { it > score } + 1).toDouble() / (boardScores.size + 1)
            return maxOf(1, floor(ratio * 100 + 0.5).toInt())
        }

        private fun comma(value: Int): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
