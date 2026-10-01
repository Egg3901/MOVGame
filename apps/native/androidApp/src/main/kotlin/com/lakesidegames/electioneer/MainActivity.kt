package com.lakesidegames.electioneer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.lakesidegames.electioneer.ui.CampaignSavesScreen
import com.lakesidegames.electioneer.ui.AnalysisScreen
import com.lakesidegames.electioneer.ui.AccountScreen
import com.lakesidegames.electioneer.ui.GameScreen
import com.lakesidegames.electioneer.ui.GameSession
import com.lakesidegames.electioneer.ui.HomeScreen
import com.lakesidegames.electioneer.ui.ResultsScreen
import com.lakesidegames.electioneer.ui.Screen
import com.lakesidegames.electioneer.ui.SetupScreen
import com.lakesidegames.electioneer.ui.StoreScreen
import com.lakesidegames.electioneer.ui.CampaignLibraryScreen
import com.lakesidegames.electioneer.ui.ElectionNightScreen
import com.lakesidegames.electioneer.ui.NativeSettingsScreen
import com.lakesidegames.electioneer.ui.NativeGuideScreen
import com.lakesidegames.electioneer.ui.NativeCampaignCoach
import com.lakesidegames.electioneer.ui.DailyBoardsScreen
import com.lakesidegames.electioneer.ui.WorldCampaignScreen

class MainActivity : ComponentActivity() {
    private lateinit var gameSession: GameSession
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::gameSession.isInitialized && gameSession.settings.hotkeysOn &&
            event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
            gameSession.screen.value in listOf(Screen.GAME, Screen.WORLD_GAME) &&
            !(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).isAcceptingText) {
            val key = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
                android.view.KeyEvent.KEYCODE_SPACE -> " "
                android.view.KeyEvent.KEYCODE_ESCAPE -> "escape"
                else -> event.unicodeChar.toChar().toString()
            }
            if (key in listOf("enter", " ", "escape", "?", "1", "2", "3", "4", "5", "6", "7", "8", "9")) {
                if (key == "?") gameSession.go(Screen.SETTINGS) else gameSession.sendShortcut(key)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stock ViewModelProvider: no viewmodel-compose artifact needed.
        val session = ViewModelProvider(this)[GameSession::class.java]
        gameSession = session
        session.attachStorage(applicationContext)
        session.attachBilling(applicationContext)
        setContent { MarginOfVictoryApp(session, this) }
    }
}

@Composable
fun MarginOfVictoryApp(session: GameSession, activity: Activity) {
    val screen by session.screen.collectAsState()
    BackHandler(enabled = screen != Screen.HOME && screen != Screen.REVEAL) {
        if (screen in listOf(Screen.SETTINGS, Screen.GUIDE, Screen.BOARDS, Screen.SAVES)) session.go(Screen.ACCOUNT)
        else if (screen in listOf(Screen.ANALYSIS, Screen.REPLAY)) session.resumeGame()
        else session.go(Screen.HOME)
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFF5B942), onPrimary = Color(0xFF17202B),
        background = Color(0xFF0A0F14), surface = Color(0xFF111B26),
        surfaceVariant = Color(0xFF16222E), onSurface = Color(0xFFEAF0F6),
        onSurfaceVariant = Color(0xFFA8B5C2), primaryContainer = Color(0xFF35402D),
    )) {
        Surface {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen == Screen.HOME || screen == Screen.SETUP || screen == Screen.LOADING ||
                                screen == Screen.GAME ||
                                screen == Screen.RESULTS || screen == Screen.LIBRARY || screen == Screen.WORLD_GAME || screen == Screen.ANALYSIS || screen == Screen.REPLAY || screen == Screen.REVEAL,
                            onClick = { session.playTab() },
                            icon = {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                            },
                            label = { Text("Play") },
                        )
                        NavigationBarItem(
                            selected = screen == Screen.STORE,
                            onClick = { session.go(Screen.STORE) },
                            icon = {
                                Icon(Icons.Filled.ShoppingCart, contentDescription = "Store")
                            },
                            label = { Text("Store") },
                        )
                        NavigationBarItem(
                            selected = screen in listOf(Screen.ACCOUNT, Screen.SAVES, Screen.BOARDS, Screen.SETTINGS, Screen.GUIDE),
                            onClick = { session.go(Screen.ACCOUNT) },
                            icon = {
                                Icon(Icons.Filled.AccountCircle, contentDescription = "Account")
                            },
                            label = { Text("Account") },
                        )
                    }
                },
            ) { inner ->
                val mod = Modifier.padding(inner)
                Box(mod) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(session)
                        Screen.SETUP -> SetupScreen(session)
                        Screen.LOADING -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                            Text("Preparing the campaign trail…")
                        }
                        Screen.GAME -> GameScreen(session)
                        Screen.RESULTS -> ResultsScreen(session)
                        Screen.REVEAL -> ElectionNightScreen(session)
                        Screen.STORE -> StoreScreen(session, activity)
                        Screen.ACCOUNT -> AccountScreen(session)
                        Screen.SETTINGS -> NativeSettingsScreen(session)
                        Screen.GUIDE -> NativeGuideScreen()
                        Screen.BOARDS -> DailyBoardsScreen(session)
                        Screen.SAVES -> CampaignSavesScreen(session)
                        Screen.ANALYSIS -> AnalysisScreen(session)
                        Screen.REPLAY -> AnalysisScreen(session, timeline = true)
                        Screen.LIBRARY -> CampaignLibraryScreen(session)
                        Screen.WORLD_GAME -> WorldCampaignScreen(session)
                    }
                }
            }
        }
    }
}
