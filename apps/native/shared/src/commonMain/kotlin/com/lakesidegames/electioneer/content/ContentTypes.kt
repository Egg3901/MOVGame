package com.lakesidegames.electioneer.content

import com.lakesidegames.electioneer.engine.CandidateTraits
import com.lakesidegames.electioneer.engine.Party
import com.lakesidegames.electioneer.engine.Region
import kotlinx.serialization.Serializable

// Setup content descriptors (mirrors content/states.ts + content/scenarios.ts).
@Serializable
data class StateSeed(
    val id: String,
    val name: String,
    val abbr: String,
    val ev: Int,
    val region: Region,
    val prior2020DemShare: Double,
    val mediaCost: Double,
    val battleground: Boolean,
    // Thousands of voters (0 for at-large aggregate units).
    val electorate: Double,
    // Multipliers on each bloc's national share, keyed by BlocId.serial.
    val profile: Map<String, Double>? = null,
    // For ME-AL / NE-AL: district contests whose combined vote decides it.
    val aggregateOf: List<String>? = null,
)

@Serializable
data class ScenarioTicket(
    val name: String,
    val shortName: String,
    val party: Party,
    val color: String,
    val traits: CandidateTraits,
    val issuePositions: Map<String, Double>,
    val baseFavorability: Map<String, Double>,
    val runningMates: List<com.lakesidegames.electioneer.engine.RunningMate>,
)

@Serializable
data class Scenario(
    val id: String,
    val year: Int,
    val systemId: String? = null,
    val label: String,
    val tagline: String,
    val dem: ScenarioTicket,
    val rep: ScenarioTicket,
    // Two-party Dem share per state id; falls back to the 2020 default.
    val statePriors: Map<String, Double>? = null,
    // Per-state EV overrides for that cycle's apportionment.
    val evOverrides: Map<String, Int>? = null,
    val evNote: String? = null,
    // National issue salience for this year, keyed by IssueId.serial.
    val issueSalience: Map<String, Double>? = null,
)
