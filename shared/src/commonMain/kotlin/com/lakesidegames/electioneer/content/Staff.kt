package com.lakesidegames.electioneer.content

import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.GameState
import kotlinx.serialization.Serializable

// Campaign staff: hire up to 3 from a pool of 8 at setup. Each gives one
// passive bonus; the in-flight multipliers are read by the engine via
// staffEffects(). Port of src/content/staff.ts.
@Serializable
data class StaffEffectsDef(
    val maxActions: Int = 0,
    val adMult: Double = 1.0,
    val fundraiseMult: Double = 1.0,
    val oppoShield: Double = 0.0,
    val debatePrepBonus: Double = 0.0,
    val traitBonuses: Map<String, Double> = emptyMap(),
)

@Serializable
data class StaffDef(
    val id: String,
    val name: String,
    val role: String,
    val blurb: String,
    val salaryPerWeek: Double,
    val loyalty: Double,
    val effects: StaffEffectsDef,
)

@Serializable
data class StaffEffects(
    val maxActions: Int = 0,
    val adMult: Double = 1.0,
    val fundraiseMult: Double = 1.0,
    val oppoShield: Double = 0.0,
    val debatePrepBonus: Double = 0.0,
    val salaryPerWeek: Double = 0.0,
)

// Aggregate passive effects of a ticket's current staff.
fun staffEffects(game: GameState, candidate: CandidateId): StaffEffects {
    var maxActions = 0
    var adMult = 1.0
    var fundraiseMult = 1.0
    var oppoShield = 0.0
    var debatePrepBonus = 0.0
    var salaryPerWeek = 0.0
    for (id in game.staff?.get(candidate.serial) ?: emptyList()) {
        val s = STAFF_BY_ID[id] ?: continue
        maxActions += s.effects.maxActions
        adMult *= s.effects.adMult
        fundraiseMult *= s.effects.fundraiseMult
        oppoShield += s.effects.oppoShield
        debatePrepBonus += s.effects.debatePrepBonus
        salaryPerWeek += s.salaryPerWeek
    }
    return StaffEffects(maxActions, adMult, fundraiseMult, oppoShield, debatePrepBonus, salaryPerWeek)
}
