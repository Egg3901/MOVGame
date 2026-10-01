package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

// Custom content belongs to the campaign snapshot. Loading or editing another
// version of the same document cannot change an already running campaign.
data class NativeCustomValidation(val json: String?, val errors: List<String>)
data class NativeEditorField(val path: String, val section: String, val label: String,
    val value: String, val numeric: Boolean, val choices: List<String> = emptyList())
data class NativeCustomEntry(val id: String, val label: String, val country: String, val json: String)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private val customTraits = listOf("charisma", "energy", "debatePrep", "intelligence", "policyKnowledge", "debatingSkill", "fundraisingProwess")
private val mpTraits = listOf("charisma", "energy", "competence", "machine")
private val editorDefaults by lazy { EngineJson.parseToJsonElement(bundleText("editor-defaults")).jsonObject }

internal class CustomDocument(val raw: JsonObject) {
    val id get() = raw.text("id")!!
    val label get() = raw.text("label")!!
    val engine get() = raw.text("engine")!!
    val mp get() = raw["mp"]?.jsonObject
    val country get() = when (engine) { "us" -> "US"; "uk" -> "UK"; else -> mp!!.text("countryId")!! }
    val election get() = mp?.text("baseElection")
    val parties get() = mp?.get("parties")?.jsonArray?.map { it.jsonObject }.orEmpty()
    val json get() = raw.toString()
    fun countryBundle(): CountryBundle {
        val base = COUNTRIES.getValue(country)
        val overrides = parties.associateBy { it.text("partyId")!! }
        val leaders = base.leaders[election].orEmpty().toMutableMap()
        for ((id, p) in overrides) leaders[id] = CountryLeader(id, p.text("leaderName")!!,
            p.number("charisma")!!, p.number("energy")!!, p.number("competence")!!, p.number("machine")!!)
        return base.copy(id = id, label = label,
            system = base.system.copy(parties = base.system.parties.map { party -> overrides[party.id]?.let {
                party.copy(name = it.text("name")!!, shortName = it.text("shortName")!!, color = it.text("color")!!)
            } ?: party }), leaders = base.leaders + (election!! to leaders))
    }
    fun scenario(): Scenario {
        fun ticket(side: String): ScenarioTicket {
            val t = raw.getValue(side).jsonObject
            return ScenarioTicket(t.text("name")!!, t.text("shortName")!!,
                Party.entries.first { it.serial == t.text("party") }, t.text("color")!!,
                EngineJson.decodeFromJsonElement(t.getValue("traits")),
                EngineJson.decodeFromJsonElement(t.getValue("issuePositions")),
                EngineJson.decodeFromJsonElement(t.getValue("baseFavorability")),
                listOf(RunningMate("$side-runningmate", "Running Mate", if (side == "dem") CandidateId.DEM else CandidateId.REP,
                    "Runs alongside ${t.text("shortName")!!}.", historical = true)))
        }
        return Scenario(id = id, year = raw.number("year")!!.toInt(), label = label,
            tagline = raw.text("tagline")!!, dem = ticket("dem"), rep = ticket("rep"))
    }
    fun tilt(regions: List<StateContest>) {
        for (region in regions) {
            var touched = false
            for (bloc in region.blocs) for (p in parties) {
                val pid = p.text("partyId")!!
                val amount = p.number("baseSupport")!!
                val appeal = bloc.appeal ?: continue
                if (amount != 0.0 && pid in appeal) { appeal[pid] = appeal.getValue(pid) + amount * 1.5; touched = true }
            }
            if (touched) for (bloc in region.blocs) bloc.support = blocPartyShares(bloc).toMutableMap()
        }
    }
}

internal fun customDocument(json: String?): CustomDocument? = json?.let {
    NativeCustomScenario.validate(it, 0).json?.let { validated -> CustomDocument(EngineJson.parseToJsonElement(validated).jsonObject) }
}
internal fun countryForGame(game: CountryGameState): CountryBundle? =
    customDocument(game.customScenario)?.takeIf { it.engine == "country" && it.id == game.countryId }?.countryBundle()
        ?: if (game.custom == true) null else getCountry(game.countryId)

class NativeCustomScenario private constructor() {
    companion object {
        fun validate(json: String, now: Long): NativeCustomValidation {
            if (json.length > 2_000_000) return NativeCustomValidation(null, listOf("Scenario files must be smaller than 2 MB."))
            val input = runCatching { EngineJson.parseToJsonElement(json) }.getOrNull()
                ?: return NativeCustomValidation(null, listOf("That file is not valid JSON."))
            var raw = input as? JsonObject ?: return NativeCustomValidation(null, listOf("File is not a valid custom scenario."))
            if (raw.number("version") == 1.0 || "engine" !in raw) raw = JsonObject(raw + mapOf(
                "version" to JsonPrimitive(2), "engine" to (raw["engine"].takeUnless { it == JsonNull } ?: JsonPrimitive("us"))))
            if (raw.number("version") != 2.0) return NativeCustomValidation(null, listOf("Unsupported scenario version (${raw["version"]?.let { (it as? JsonPrimitive)?.content ?: it.toString() } ?: "undefined"}). This app reads version 2."))
            val engine = raw.text("engine")
            if (engine !in listOf("us", "uk", "country")) return NativeCustomValidation(null,
                listOf("Unknown engine (${(raw["engine"] as? JsonPrimitive)?.content ?: raw["engine"]?.toString() ?: "undefined"})."))
            val errors = mutableListOf<String>()
            if (raw.text("id")?.startsWith("custom-") != true) errors += "Scenario id must start with \"custom-\"."
            fun label(obj: JsonObject, key: String, required: String, tooLong: String, max: Int) {
                val value = obj.text(key)
                if (value == null || value.isBlank()) errors += required
                if (value != null && value.length > max) errors += tooLong
            }
            label(raw, "label", "Scenario needs a title.", "Scenario title is too long (60 characters max).", 60)
            if (raw.text("tagline") == null) errors += "Scenario tagline must be text."
            if ((raw.text("tagline")?.length ?: 0) > 200) errors += "Scenario tagline is too long (200 characters max)."
            if (raw.number("year")?.let { it in 1788.0..2100.0 } != true) errors += "Year must be between 1788 and 2100."
            val common = raw.filterKeys { it in listOf("id", "year") }.toMutableMap()
            common["version"] = JsonPrimitive(2); common["engine"] = JsonPrimitive(engine!!)
            common["label"] = JsonPrimitive(raw.text("label")?.trim() ?: "")
            common["tagline"] = JsonPrimitive(raw.text("tagline")?.trim() ?: "")
            for (key in listOf("createdAt", "updatedAt")) common[key] = raw[key].takeIf { raw.number(key) != null } ?: JsonPrimitive(now)
            val hex = Regex("#[0-9a-fA-F]{6}")
            fun number(obj: JsonObject, key: String, min: Double, max: Double, missing: String, range: String) {
                val value = obj.number(key)
                if (value == null) errors += missing else if (value !in min..max) errors += range
            }
            fun ticket(side: String): JsonObject? {
                val where = if (side == "dem") "your candidate" else "the opponent"
                val t = raw[side] as? JsonObject ?: run { errors += "$where: missing candidate data."; return null }
                label(t, "name", "$where: name is required.", "$where: name is too long (40 characters max).", 40)
                label(t, "shortName", "$where: short name is required.", "$where: short name is too long (16 characters max).", 16)
                if (t.text("party") !in listOf("Democratic", "Republican")) errors += "$where: party must be Democratic or Republican."
                if (t.text("color")?.let(hex::matches) != true) errors += "$where: color must be a hex value like #2563eb."
                fun values(key: String, ids: List<String>, min: Double, max: Double, missing: String, field: String): JsonObject {
                    val data = t[key] as? JsonObject ?: run { errors += "$where: $missing are missing."; return JsonObject(emptyMap()) }
                    for (id in ids) number(data, id, min, max, "$where: $field \"$id\" must be a number.",
                        "$where: $field \"$id\" must be between ${min.toInt()} and ${max.toInt()}.")
                    return JsonObject(data.filterKeys { it in ids })
                }
                val traits = values("traits", customTraits, 0.0, 100.0, "traits", "trait")
                val issues = values("issuePositions", IssueId.entries.map { it.serial }, -1.0, 1.0, "issue positions", "position on")
                val favor = mutableMapOf<String, JsonElement>()
                if ("baseFavorability" in t) {
                    val data = t["baseFavorability"] as? JsonObject
                    if (data == null) errors += "$where: favorability data is malformed."
                    else for ((id, value) in data) {
                        if (BlocId.entries.none { it.serial == id }) errors += "$where: unknown voter bloc \"$id\"."
                        else {
                            number(data, id, -1.0, 1.0, "$where: favorability for \"$id\" must be a number.", "$where: favorability for \"$id\" must be between -1 and 1.")
                            if (data.number(id) != 0.0) favor[id] = value
                        }
                    }
                }
                return JsonObject(t.filterKeys { it in listOf("party", "color") } + mapOf("name" to JsonPrimitive(t.text("name")?.trim() ?: ""),
                    "shortName" to JsonPrimitive(t.text("shortName")?.trim() ?: ""), "traits" to traits, "issuePositions" to issues, "baseFavorability" to JsonObject(favor)))
            }
            if (engine == "us") {
                if ("eventMode" in raw && raw.text("eventMode") !in listOf("historical", "plausible")) errors += "Event mode must be historical or plausible."
                common["dem"] = ticket("dem") ?: JsonNull; common["rep"] = ticket("rep") ?: JsonNull
                common["eventMode"] = raw["eventMode"] ?: JsonPrimitive("historical")
            } else {
                val mp = raw["mp"] as? JsonObject
                if (mp == null) errors += "Multiparty setup is missing."
                else {
                    val country = if (engine == "country") mp.text("countryId")?.let { COUNTRIES[it] } else null
                    if (engine == "country" && country == null) errors += "Pick a country to build on."
                    val election = mp.text("baseElection")
                    var allowed = emptyList<String>()
                    if (election == null) errors += "Pick a base election to build on."
                    else if (engine == "uk") {
                        if (election !in UK_ELECTIONS) errors += "Unknown UK election \"$election\"." else allowed = playablePartiesIn(election)
                    } else if (country != null) {
                        if (election !in country.elections) errors += "Unknown election \"$election\" for ${country.label}." else allowed = playablePartiesIn(country, election)
                    }
                    val list = mp["parties"] as? JsonArray
                    val normalized = mutableListOf<JsonElement>()
                    if (list == null) errors += "Party list is missing."
                    else if (list.size < 2) errors += "A race needs at least two parties."
                    else {
                        val seen = mutableSetOf<String>()
                        for ((index, value) in list.withIndex()) {
                            val where = "party ${index + 1}"
                            val p = value as? JsonObject
                            if (p == null) { errors += "$where: missing data."; continue }
                            val pid = p.text("partyId")
                            if (pid.isNullOrBlank()) errors += "$where: needs a party id."
                            else if (allowed.isNotEmpty() && pid !in allowed) errors += "$where: \"$pid\" does not contest this election."
                            else if (!seen.add(pid)) errors += "$where: \"$pid\" is listed twice."
                            label(p, "name", "$where: name is required.", "$where: name is too long (48 characters max).", 48)
                            label(p, "shortName", "$where: short name is required.", "$where: short name is too long (16 characters max).", 16)
                            if (p.text("color")?.let(hex::matches) != true) errors += "$where: color must be a hex value like #e4003b."
                            label(p, "leaderName", "$where: leader name is required.", "$where: leader name is too long (48 characters max).", 48)
                            for (key in mpTraits) number(p, key, 0.0, 100.0, "$where: trait \"$key\" must be a number.", "$where: trait \"$key\" must be between 0 and 100.")
                            number(p, "baseSupport", -1.0, 1.0, "$where: base support must be a number.", "$where: base support must be between -1 and 1.")
                            normalized += JsonObject(p.filterKeys { it in mpTraits + listOf("partyId", "color", "baseSupport") } +
                                listOf("name", "shortName", "leaderName").associateWith { JsonPrimitive(p.text(it)?.trim() ?: "") })
                        }
                    }
                    common["mp"] = JsonObject(mp.filterKeys { it == "baseElection" || (it == "countryId" && engine == "country") } + ("parties" to JsonArray(normalized)))
                }
                val defaults = editorDefaults.getValue("US:").jsonObject
                common["dem"] = defaults.getValue("dem"); common["rep"] = defaults.getValue("rep")
            }
            return if (errors.isNotEmpty()) NativeCustomValidation(null, errors) else NativeCustomValidation(JsonObject(common).toString(), emptyList())
        }
        fun create(country: String, id: String, election: String?, now: Long): String? {
            val target = if (country == "US") "" else election ?: nativeElections.filter { it.country == country }.maxByOrNull { it.year }?.nativeId ?: return null
            val template = editorDefaults["$country:$target"] as? JsonObject ?: return null
            return JsonObject(template + mapOf("id" to JsonPrimitive(id), "createdAt" to JsonPrimitive(now), "updatedAt" to JsonPrimitive(now))).toString()
        }
        fun changeElection(json: String, election: String): String? {
            val raw = runCatching { EngineJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val doc = customDocument(json) ?: return null
            val template = editorDefaults["${doc.country}:$election"] as? JsonObject ?: return null
            return JsonObject(raw + mapOf("year" to template.getValue("year"), "mp" to template.getValue("mp"))).toString()
        }
        fun fields(json: String): List<NativeEditorField> = runCatching {
            val raw = EngineJson.parseToJsonElement(json).jsonObject
            val result = mutableListOf<NativeEditorField>()
            fun field(obj: JsonObject, key: String, path: String, section: String, numeric: Boolean = false, choices: List<String> = emptyList(), label: String = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ')) {
                result += NativeEditorField("$path$key", section, label, (obj[key] as? JsonPrimitive)?.content ?: if (numeric) "0" else "", numeric, choices)
            }
            for (key in listOf("label", "tagline", "year")) field(raw, key, "", "Campaign", key == "year")
            if (raw.text("engine") == "us") {
                field(raw, "eventMode", "", "Campaign", choices = listOf("historical", "plausible"))
                for (side in listOf("dem", "rep")) {
                    val t = raw.getValue(side).jsonObject
                    val title = if (side == "dem") "Your candidate" else "The opponent"
                    for (key in listOf("name", "shortName", "color")) field(t, key, "$side/", title)
                    field(t, "party", "$side/", title, choices = listOf("Democratic", "Republican"))
                    for (key in customTraits) field(t.getValue("traits").jsonObject, key, "$side/traits/", "$title traits", true)
                    for (issue in IssueId.entries) field(t.getValue("issuePositions").jsonObject, issue.serial, "$side/issuePositions/", "$title issues (-1 to 1)", true)
                    for (bloc in BlocId.entries) field(t.getValue("baseFavorability").jsonObject, bloc.serial, "$side/baseFavorability/", "$title voters (-1 to 1)", true)
                }
            } else for ((index, element) in raw.getValue("mp").jsonObject.getValue("parties").jsonArray.withIndex()) {
                val p = element.jsonObject
                val title = "Party ${index + 1} · ${p.text("partyId")?.uppercase() ?: ""}"
                for (key in listOf("name", "shortName", "color", "leaderName") + mpTraits + "baseSupport")
                    field(p, key, "mp/parties/$index/", title, key in mpTraits || key == "baseSupport")
            }
            result
        }.getOrDefault(emptyList())
        fun edit(json: String, path: String, value: String): String? = runCatching {
            val field = fields(json).firstOrNull { it.path == path } ?: return null
            val replacement = if (field.numeric) value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonPrimitive(value) else JsonPrimitive(value)
            fun replace(node: JsonElement, keys: List<String>): JsonElement {
                if (keys.isEmpty()) return replacement
                val key = keys.first()
                return when (node) {
                    is JsonObject -> JsonObject(node + (key to replace(node[key] ?: JsonNull, keys.drop(1))))
                    is JsonArray -> JsonArray(node.mapIndexed { i, item -> if (i == key.toInt()) replace(item, keys.drop(1)) else item })
                    else -> error("Invalid editor field")
                }
            }
            replace(EngineJson.parseToJsonElement(json), path.split('/')).toString()
        }.getOrNull()
        fun entry(json: String): NativeCustomEntry? = customDocument(json)?.let { NativeCustomEntry(it.id, it.label, it.country, it.json) }
        fun duplicate(json: String, id: String, now: Long): String? = customDocument(json)?.let {
            JsonObject(it.raw + mapOf("id" to JsonPrimitive(id), "createdAt" to JsonPrimitive(now), "updatedAt" to JsonPrimitive(now),
                "label" to JsonPrimitive((it.label.take(53) + " (copy)").take(60)))).toString()
        }
        fun start(json: String): String? = runCatching {
            val doc = customDocument(json) ?: return null
            if (doc.engine == "us") {
                val game = beginGame(createGame(NewGameOptions(seed = doc.id, scenario = doc.id, scenarioOverride = doc.scenario(),
                    customScenario = doc.json, eventMode = EventMode.entries.first { it.serial == doc.raw.text("eventMode") })))
                saveGame(game, doc.id, "normal")
            } else {
                val player = doc.parties.first().text("partyId")!!
                if (doc.engine == "uk") {
                    val base = createUkGame(NewUkGameOptions(seed = doc.id, election = doc.election, playerParty = player, difficulty = "normal"))
                    val overrides = doc.parties.associateBy { it.text("partyId")!! }
                    val game = base.copy(label = doc.label, tagline = doc.raw.text("tagline")!!, custom = true, customScenario = doc.json,
                        leaders = base.leaders.mapValues { (id, ld) -> overrides[id]?.let { p -> ld.copy(name = p.text("leaderName")!!,
                            charisma = p.number("charisma")!!, energy = p.number("energy")!!, competence = p.number("competence")!!, machine = p.number("machine")!!) } ?: ld })
                    doc.tilt(game.regions)
                    buildJsonObject { put("version", 1); put("uk", EngineJson.encodeToJsonElement(game)) }.toString()
                } else {
                    val game = createCountryGame(doc.countryBundle(), NewCountryGameOptions(seed = doc.id, election = doc.election, playerParty = player, difficulty = "normal"))
                        .copy(label = doc.label, tagline = doc.raw.text("tagline")!!, custom = true, customScenario = doc.json)
                    doc.tilt(game.regions)
                    buildJsonObject { put("version", 1); put("country", EngineJson.encodeToJsonElement(game)) }.toString()
                }
            }
        }.getOrNull()
    }
}

class NativeCustomLibrary private constructor(private var documents: List<String>) {
    companion object {
        fun empty(): NativeCustomLibrary = NativeCustomLibrary(emptyList())
        fun restore(json: String): NativeCustomLibrary? = runCatching {
            val raw = EngineJson.parseToJsonElement(json).jsonObject
            require(raw["version"]?.jsonPrimitive?.intOrNull == 1)
            val docs = raw.getValue("documents").jsonArray.mapNotNull { NativeCustomScenario.validate(it.toString(), 0).json }
            require(docs.mapNotNull { NativeCustomScenario.entry(it)?.id }.distinct().size == docs.size)
            NativeCustomLibrary(docs)
        }.getOrNull()
    }
    fun entries(): List<NativeCustomEntry> = documents.mapNotNull { NativeCustomScenario.entry(it) }
    fun save(json: String, now: Long): NativeCustomValidation {
        val validation = NativeCustomScenario.validate(json, now)
        val doc = validation.json?.let(::customDocument) ?: return validation
        val updated = JsonObject(doc.raw + ("updatedAt" to JsonPrimitive(now))).toString()
        documents = documents.filter { NativeCustomScenario.entry(it)?.id != doc.id } + updated
        return NativeCustomValidation(updated, emptyList())
    }
    fun remove(id: String) { documents = documents.filter { NativeCustomScenario.entry(it)?.id != id } }
    fun json(): String = buildJsonObject { put("version", 1); put("documents", JsonArray(documents.map(EngineJson::parseToJsonElement))) }.toString()
}
