package com.lakesidegames.electioneer.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.NativeHelp
import com.lakesidegames.electioneer.engine.NativeSound
import com.lakesidegames.electioneer.BuildConfig
import java.io.File

class NativePreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("mov_native", Context.MODE_PRIVATE)
    var soundOn by mutableStateOf(prefs.getBoolean("sound_on", true)); private set
    var volume by mutableStateOf(prefs.getFloat("volume", 0.6f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0.6f }); private set
    var reducedMotion by mutableStateOf(prefs.getBoolean("reduce_motion", false)); private set
    var hotkeysOn by mutableStateOf(prefs.getBoolean("hotkeys_on", true)); private set
    var tutorialNonce by mutableIntStateOf(0); private set
    private var replayRequested = false
    fun takeTutorialReplay(): Boolean = replayRequested.also { replayRequested = false }
    private val players = mutableSetOf<MediaPlayer>()
    fun sound(value: Boolean) { soundOn = value; prefs.edit().putBoolean("sound_on", value).apply(); if (value) play("pollUp") }
    fun volume(value: Float) { volume = value.coerceIn(0f, 1f); prefs.edit().putFloat("volume", volume).apply() }
    fun motion(value: Boolean) { reducedMotion = value; prefs.edit().putBoolean("reduce_motion", value).apply() }
    fun hotkeys(value: Boolean) { hotkeysOn = value; prefs.edit().putBoolean("hotkeys_on", value).apply() }
    fun tourDone(country: String) = prefs.getBoolean("tour_$country", false)
    fun finishTour(country: String) { prefs.edit().putBoolean("tour_$country", true).apply() }
    fun replayTutorial() { replayRequested = true; prefs.edit().apply { listOf("US", "UK", "CA", "DE", "FR", "AU").forEach { remove("tour_$it") } }.apply(); tutorialNonce++ }
    fun play(cue: String) {
        if (!soundOn || volume <= 0f) return
        val file = File(context.cacheDir, "mov-cue-$cue.wav")
        runCatching {
            file.writeBytes(Base64.decode(NativeSound.wavBase64(cue, volume.toDouble()), Base64.DEFAULT))
            val player = MediaPlayer()
            players.add(player)
            fun release() { players.remove(player); player.release() }
            try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { release() }
                player.setOnErrorListener { _, _, _ -> release(); true }
                player.prepare(); player.start()
            } catch (error: Exception) { release() }
        }
    }
    fun close() { players.toList().forEach { it.release() }; players.clear() }
}

@Composable
fun NativeSettingsScreen(session: GameSession) {
    val settings = session.settings
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        NativeToggle("Sound effects", settings.soundOn, settings::sound)
        Text("Volume: ${(settings.volume * 100).toInt()}%")
        Slider(settings.volume, settings::volume, enabled = settings.soundOn, modifier = Modifier.semantics { contentDescription = "Sound volume" })
        Button(onClick = { settings.play("turnAdvance") }, enabled = settings.soundOn) { Text("Preview sound") }
        NativeToggle("Reduce motion", settings.reducedMotion, settings::motion)
        Text("Election night uses instant results when this or your device's animation setting is on.", style = MaterialTheme.typography.bodySmall)
        NativeToggle("Keyboard shortcuts", settings.hotkeysOn, settings::hotkeys)
        Text("Enter or Space ends the week, with confirmation if no moves are queued. 1 through 9 selects a move. Escape closes a recap or selection. ? opens Settings. Shortcuts pause while typing or resolving an event.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { settings.replayTutorial(); session.resumeGame() }, enabled = (session.game.value.state != null || session.campaign.value != null)) { Text("Replay tutorial") }
        OutlinedButton(onClick = { session.go(Screen.EDITOR) }) { Text("Scenario editor") }
        OutlinedButton(onClick = { session.go(Screen.GUIDE) }) { Text("How to play") }
        TextButton(onClick = { uri.openUri("https://lakesidegames.net/games/electioneer/?legal=privacy") }) { Text("Privacy policy") }
        TextButton(onClick = { uri.openUri("https://lakesidegames.net/games/electioneer/?legal=terms") }) { Text("Terms of service") }
        TextButton(onClick = { uri.openUri("mailto:support@lakesidegames.net") }) { Text("Contact support") }
        TextButton(onClick = { uri.openUri("https://lakesidegames.net/games/electioneer/") }) { Text("Web game") }
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun NativeToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun NativeGuideScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("HOW TO PLAY", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        NativeHelp.guide().forEach { lesson ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text(lesson.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(lesson.body)
            } }
        }
    }
}

@Composable
fun NativeCampaignCoach(session: GameSession, country: String, goal: String, turn: Int, selected: String?, queued: Int) {
    val settings = session.settings
    var step by remember(country) { mutableStateOf<Int?>(if (settings.takeTutorialReplay() || (turn == 0 && !settings.tourDone(country))) 0 else null) }
    var startTurn by remember(country) { mutableIntStateOf(turn) }
    LaunchedEffect(settings.tutorialNonce) { if (settings.takeTutorialReplay()) { step = 0; startTurn = turn } }
    LaunchedEffect(selected) { if (step == 1 && selected != null) step = 2 }
    LaunchedEffect(queued) { if (step == 3 && queued > 0) step = 4 }
    LaunchedEffect(turn) { if (step == 4 && turn > startTurn) step = 5 }
    fun finish() { step = null; settings.finishTour(country) }
    step?.let { index ->
        val lesson = NativeHelp.steps(country, goal)[index]
        Card(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text("TOUR · ${index + 1}/8", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary); TextButton(onClick = ::finish) { Text("Skip tour") } }
                Text(lesson.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(lesson.body)
                Row { if (index > 0) TextButton(onClick = { step = index - 1 }) { Text("Back") }; Spacer(Modifier.weight(1f)); Button(onClick = { if (index == 7) finish() else step = index + 1 }) { Text(if (index == 7) "Done" else "Next") } }
            }
        }
    }
}
