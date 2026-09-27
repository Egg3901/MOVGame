package com.lakesidegames.electioneer.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

// Contract JSON: every enum crosses the wire as its TS serial string
// ("dem", "economy", ...), never the Kotlin member name. Phase 2 content
// bundles decode with these same codecs.
val EngineJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // TS sum types tag with "kind" ("majority", "scheduled", ...).
    classDiscriminator = "kind"
}

abstract class SerialCodec<T>(
    serialName: String,
    private val toSerial: (T) -> String,
    private val fromSerial: (String) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(toSerial(value))
    }

    override fun deserialize(decoder: Decoder): T = fromSerial(decoder.decodeString())
}

object IssueIdSerial : SerialCodec<IssueId>("IssueId", { it.serial }, { s -> IssueId.entries.first { it.serial == s } })
object BlocIdSerial : SerialCodec<BlocId>("BlocId", { it.serial }, { s -> BlocId.entries.first { it.serial == s } })
object ActionTypeSerial : SerialCodec<ActionType>("ActionType", { it.serial }, { s -> ActionType.entries.first { it.serial == s } })
object AdModeSerial : SerialCodec<AdMode>("AdMode", { it.serial }, { s -> AdMode.entries.first { it.serial == s } })
object GamePhaseSerial : SerialCodec<GamePhase>("GamePhase", { it.serial }, { s -> GamePhase.entries.first { it.serial == s } })
object CandidateIdSerial : SerialCodec<CandidateId>("CandidateId", { it.serial }, { s -> CandidateId.entries.first { it.serial == s } })
object PartySerial : SerialCodec<Party>("Party", { it.serial }, { s -> Party.entries.first { it.serial == s } })
object RegionSerial : SerialCodec<Region>("Region", { it.serial }, { s -> Region.entries.first { it.serial == s } })
object PartyScopeSerial : SerialCodec<PartyScope>("PartyScope", { it.serial }, { s -> PartyScope.entries.first { it.serial == s } })
object AllocationStrategyIdSerial : SerialCodec<AllocationStrategyId>("AllocationStrategyId", { it.serial }, { s -> AllocationStrategyId.entries.first { it.serial == s } })
object EventModeSerial : SerialCodec<EventMode>("EventMode", { it.serial }, { s -> EventMode.entries.first { it.serial == s } })
object UkActionTypeSerial : SerialCodec<UkActionType>("UkActionType", { it.serial }, { s -> UkActionType.entries.first { it.serial == s } })
object UkAdModeSerial : SerialCodec<UkAdMode>("UkAdMode", { it.serial }, { s -> UkAdMode.entries.first { it.serial == s } })
object CountryActionTypeSerial : SerialCodec<CountryActionType>("CountryActionType", { it.serial }, { s -> CountryActionType.entries.first { it.serial == s } })
object CountryAdModeSerial : SerialCodec<CountryAdMode>("CountryAdMode", { it.serial }, { s -> CountryAdMode.entries.first { it.serial == s } })
