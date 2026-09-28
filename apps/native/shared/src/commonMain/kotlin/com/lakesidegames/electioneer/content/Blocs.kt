package com.lakesidegames.electioneer.content

import com.lakesidegames.electioneer.engine.BlocId
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

// Bloc archetypes: national share, turnout, 2020 lean, and the issue profile
// (how much each bloc cares + its ideal left(-1)/right(+1) position).
// Port of src/content/blocs.ts. Phase 2 will load content from JSON bundles
// with web.pin as source of truth; these tables seed that migration.
@Serializable
data class BlocArchetype(
    val id: BlocId,
    val name: String,
    val nationalShare: Double,
    val turnoutPropensity: Double,
    val nationalDemShare: Double,
    val issueWeights: Map<String, Double>,
    val idealPositions: Map<String, Double>,
    val traitSensitivity: Double,
)

