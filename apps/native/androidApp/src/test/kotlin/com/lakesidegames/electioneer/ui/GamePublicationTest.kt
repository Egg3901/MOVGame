package com.lakesidegames.electioneer.ui

import com.lakesidegames.electioneer.engine.ActionType
import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.GamePhase
import com.lakesidegames.electioneer.engine.GameState
import com.lakesidegames.electioneer.engine.Resources
import com.lakesidegames.electioneer.engine.queuePlannedAction
import com.lakesidegames.electioneer.engine.removePlannedAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GamePublicationTest {
    @Test
    fun publishesEveryQueueEditAfterEngineMutatesCurrentState() = runBlocking {
        val publication = GamePublication(GameState(
            seed = 1, rngState = 1, turn = 0, totalTurns = 9, granularity = "week",
            phase = GamePhase.ALLOCATE, playerCandidate = CandidateId.DEM,
            candidates = emptyMap(), issues = emptyMap(), salience = mutableMapOf(),
            states = emptyList(), resources = mapOf("dem" to Resources(100_000_000.0, 3, 3, 1, 0.0, 0.0)),
            pendingEvents = mutableListOf(), firedEventIds = mutableListOf(), queuedActions = emptyList(),
            causes = mutableListOf(), lastRecap = emptyList(),
        ))
        val planned = mutableListOf<Int>()
        val slots = mutableListOf<Int>()
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            publication.snapshots.collect { frame ->
                planned += frame.state.queuedActions.size
                slots += frame.state.resources.getValue("dem").actions - frame.state.queuedActions.size
            }
        }
        try {
            fun publish(expected: Int) {
                val previous = publication.value
                publication.value = previous.copy()
                assertEquals(expected, planned.last(), "Queue edits must refresh the plan immediately")
                assertNotSame(previous, publication.value, "Compose needs a new game reference for its child screens")
            }
            assertTrue(queuePlannedAction(publication.value, ActionType.FUNDRAISE, null, 1))
            publish(1)
            assertEquals(listOf(0, 1), planned, "Adding a move must refresh the plan immediately")
            assertTrue(queuePlannedAction(publication.value, ActionType.FUNDRAISE, null, 1))
            publish(2)
            assertTrue(removePlannedAction(publication.value, 0))
            publish(1)
            publication.value.queuedActions = emptyList()
            publish(0)
            assertEquals(listOf(0, 1, 2, 1, 0), planned)
            assertEquals(listOf(3, 2, 1, 2, 3), slots)
        } finally {
            observer.cancelAndJoin()
        }
    }
}
