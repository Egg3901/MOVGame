package com.lakesidegames.electioneer.engine
import com.lakesidegames.electioneer.content.getScenario
import com.lakesidegames.electioneer.content.STATE_SEEDS

data class NativeAward(val id: String, val name: String, val blurb: String, val icon: String)
data class NativeResultSummary(val score: Int, val difficulty: String, val unitMargin: Int,
                               val popularMargin: Double, val standardLength: Boolean)
data class NativeHistoricalRegion(val id: String, val name: String, val units: Int,
                                  val historicalUnits: Int, val shareSwing: Double)

object NativeResults {
    fun us(game: GameState, difficulty: String?): NativeResultSummary? {
        val result = game.result ?: return null
        val facts = usScoreFacts(result.electoralVotes, result.popularShare, game.playerCandidate, difficulty ?: "normal")
        val opponent = if (game.playerCandidate == CandidateId.DEM) "rep" else "dem"
        return NativeResultSummary(if (difficulty == null) -1 else computeScoreFromFacts(facts), difficulty ?: "unknown",
            (result.electoralVotes[game.playerCandidate.serial] ?: 0) - (result.electoralVotes[opponent] ?: 0),
            facts.popularMargin, game.totalTurns == 9)
    }

    fun achievements(game: GameState, difficulty: String?): List<NativeAward> {
        val result = game.result ?: return emptyList()
        if (difficulty == null) return emptyList()
        return checkAchievements(AchievementContext(result, game, game.playerCandidate, difficulty))
            .map { NativeAward(it.id, it.name, it.description, it.icon) }
    }

    fun historicalUs(game: GameState): List<NativeHistoricalRegion> {
        val result = game.result ?: return emptyList()
        val dem = game.playerCandidate == CandidateId.DEM
        val scenario = getScenario(game.scenarioId)
        return result.stateResults.map { row ->
            val state = game.states.first { it.id == row.stateId }
            val prior = scenario.statePriors?.get(row.stateId)
                ?: STATE_SEEDS.first { it.id == row.stateId }.prior2020DemShare
            val historicalWin = if (dem) prior > 0.5 else prior < 0.5
            val swing = (row.demShare - prior) * 100 * if (dem) 1 else -1
            NativeHistoricalRegion(row.stateId, state.name,
                if (row.winner == game.playerCandidate) row.electoralVotes else 0,
                if (historicalWin) row.electoralVotes else 0, swing)
        }.sortedByDescending { kotlin.math.abs(it.shareSwing) }
    }
}
