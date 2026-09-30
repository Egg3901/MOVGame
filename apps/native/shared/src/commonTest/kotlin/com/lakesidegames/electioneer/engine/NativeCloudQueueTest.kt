package com.lakesidegames.electioneer.engine

import kotlin.test.*

class NativeCloudQueueTest {
    @Test fun editsCoalesceAndLateAcknowledgementKeepsNewerWork() {
        val queue = NativeCloudQueue.empty()
        queue.offer("autosave", "alice", 1)
        val inFlight = queue.next("alice", 0)!!
        queue.offer("autosave", "alice", 2)
        queue.acknowledge(inFlight)
        assertEquals(2L, queue.next("alice", 0)?.revision)
        queue.acknowledge(queue.next("alice", 0)!!)
        assertEquals(0, queue.pending("alice"))
        val restored = NativeCloudQueue.restore(queue.json())
        restored.offer("autosave", "alice", 2)
        assertEquals(0, restored.pending("alice"))
    }
    @Test fun offlineRetriesAreDurableBoundedAndAccountScoped() {
        val queue = NativeCloudQueue.empty()
        queue.offer("autosave", "alice", 1)
        queue.offer("save-2", "bob", 2)
        queue.fail(queue.next("alice", 0)!!, NativeCloudOutcome("retry"), 100)
        val restored = NativeCloudQueue.restore(queue.json())
        assertNull(restored.next("alice", 30_099))
        assertNotNull(restored.next("alice", 30_100))
        assertEquals("save-2", restored.next("bob", 0)?.id)
        repeat(20) { restored.fail(restored.next("alice", Long.MAX_VALUE / 2)!!, NativeCloudOutcome("retry"), 100) }
        assertEquals(300_000L, restored.delayMillis("alice", 100))
    }
    @Test fun conflictStaysBlockedThroughEditsUntilExplicitResolution() {
        val queue = NativeCloudQueue.empty()
        queue.offer("autosave", "alice", 1)
        queue.fail(queue.next("alice", 0)!!, NativeCloudOutcome("conflict"), 10)
        queue.offer("autosave", "alice", 2)
        assertTrue(queue.conflicted("alice"))
        assertNull(queue.next("alice", 1_000_000))
        assertEquals(-1L, queue.delayMillis("alice", 1_000_000))
        queue.retry("alice")
        assertEquals(2L, queue.next("alice", 0)?.revision)
        queue.remove("autosave")
        assertEquals(0, queue.pending("alice"))
    }
    @Test fun corruptAndFutureOutboxesDoNotCreateUploads() {
        assertEquals(0, NativeCloudQueue.restore("bad json").pending("alice"))
        assertEquals(0, NativeCloudQueue.restore("{\"version\":2,\"writes\":[]}").pending("alice"))
        val queue = NativeCloudQueue.empty()
        queue.offer("../bad", "alice", 1)
        queue.offer("valid", "", 1)
        assertNull(queue.next("alice", 0))
    }
}
