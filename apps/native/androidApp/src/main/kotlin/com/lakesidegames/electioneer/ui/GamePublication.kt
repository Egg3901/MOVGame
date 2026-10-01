package com.lakesidegames.electioneer.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The engine edits the current state before publishing. A revision prevents
// StateFlow from suppressing an equal shallow copy and keeps Compose notified.
data class GameFrame<T>(val state: T, val revision: Long)

internal class GamePublication<T>(initial: T) {
    private val frames = MutableStateFlow(GameFrame(initial, 0))
    val snapshots: StateFlow<GameFrame<T>> = frames.asStateFlow()
    var value: T
        get() = frames.value.state
        set(next) { frames.value = GameFrame(next, frames.value.revision + 1) }
}
