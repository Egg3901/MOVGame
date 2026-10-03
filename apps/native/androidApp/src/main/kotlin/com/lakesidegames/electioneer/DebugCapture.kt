package com.lakesidegames.electioneer

import com.lakesidegames.electioneer.engine.*
import com.lakesidegames.electioneer.ui.GameSession
import com.lakesidegames.electioneer.ui.Screen
import org.json.JSONObject

// Only debug APKs accept capture routes. Runtime checks use real Compose
// screens and persisted campaigns; release builds ignore these intent extras.
internal fun captureDebugFlow(session: GameSession, flow: String) {
    if (!BuildConfig.DEBUG) return
    val routes = mapOf("home" to Screen.HOME, "account" to Screen.ACCOUNT, "library" to Screen.LIBRARY,
        "setup" to Screen.SETUP, "store" to Screen.STORE,
        "settings" to Screen.SETTINGS, "guide" to Screen.GUIDE, "editor" to Screen.EDITOR)
    if (flow in routes) { session.go(routes.getValue(flow)); return }
    if (flow.startsWith("ask")) {
        val country = flow.substringAfter('-', "US").takeIf { it in listOf("US", "UK", "CA", "DE", "FR", "AU") } ?: "US"
        captureDebugFlow(session, "game-$country")
        return
    }
    if (flow == "resume") { session.resumeGame(); return }
    val country = flow.substringAfter('-', "US")
    require(country in listOf("US", "UK", "CA", "DE", "FR", "AU"))
    session.settings.finishTour(country)
    val custom = flow.startsWith("custom-")
    var snapshot = if (custom) NativeCustomScenario.start(NativeCustomScenario.create(country, "custom-smoke-$country", null, 100)!!)!!
        else if (country == "US") {
            val mate = MobileGame.mates("2024", "dem").first { it.historical }.id
            MobileGame.startConfiguredGame("2024", "dem", mate, emptyList(), "normal", "historical", 9,
                "native-ui-$country", "", false, false).saveSnapshot()
        } else {
            val election = MobileCampaign.elections(country).maxBy { it.year }.nativeId
            MobileCampaign.start(country, election, MobileCampaign.parties(country, election).first().id,
                "normal", "native-ui-$country").saveSnapshot()
        }
    var replay = NativeReplay.start(snapshot, "casual")
    if (flow.startsWith("results-") || flow == "replay") {
        val us = MobileGame.restore(snapshot)
        val world = if (us == null) MobileCampaign.restore(snapshot)!! else null
        var count = 0
        while (us?.isOver() == false || world?.isOver() == false) {
            check(++count <= 40) { "Capture campaign did not finish" }
            val previous = snapshot
            if (us != null) us.endTurn()
            else if (world!!.hasPendingEvent()) world.answerEvent(world.eventChoices().first().id)
            else world.endWeek()
            snapshot = us?.saveSnapshot() ?: world!!.saveSnapshot()
            replay = NativeReplay.record(replay, previous, snapshot)
        }
    }
    check(session.importCampaign(snapshot, NativeReplay.json(replay)))
    if (flow == "custom-US") {
        val fragment = requireNotNull(android.net.Uri.parse(session.askUrl()).fragment).substringAfter("mov=")
        val json = android.util.Base64.decode(fragment, android.util.Base64.URL_SAFE).toString(Charsets.UTF_8)
        val label = JSONObject(json).getString("scenario")
        check(label == MobileGame.restore(snapshot)!!.campaignLabel()) { "Ask lost the custom campaign label" }
        android.util.Log.i("MOVCapture", "MOV_ASK_CUSTOM_LABEL_READY")
    }
    when (flow) {
        "analysis" -> session.go(Screen.ANALYSIS)
        "replay" -> session.go(Screen.REPLAY)
        "saves" -> { check(session.saveNamed("Smoke campaign")); session.go(Screen.SAVES) }
    }
    android.util.Log.i("MOVCapture", "FLOW_READY $flow")
}
