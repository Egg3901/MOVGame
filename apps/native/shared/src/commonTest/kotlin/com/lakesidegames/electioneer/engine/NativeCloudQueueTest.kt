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
    @Test fun quotaFailuresStopWritesWithoutClaimingAnotherDeviceChangedTheSave() {
        val queue = NativeCloudQueue.empty()
        queue.offer("new-save", "alice", 1)
        queue.fail(queue.next("alice", 0)!!, NativeCloudOutcome("rejected"), 10)
        assertFalse(queue.conflicted("alice"))
        assertNull(queue.next("alice", 1_000_000))
        queue.retry("alice")
        assertNotNull(queue.next("alice", 0))
    }
    @Test fun anotherAccountsNewCampaignCannotReplaceAPendingAutosavePayload() {
        val library = NativeSaveLibrary.empty()
        val queue = NativeCloudQueue.empty()
        val first = MobileGame.startGame("dem", "normal", 1).saveSnapshot()
        val second = MobileGame.startGame("rep", "hard", 2).saveSnapshot()
        val alice = NativeAutosave.slot("alice", "unused")
        val bob = NativeAutosave.slot("bob", "unused")
        assertTrue(library.save(alice, "Autosave", first, 1))
        library.markSynced(alice, "alice", 10)
        queue.offer(alice, "alice", 1)
        assertTrue(library.save(bob, "Autosave", second, 2))
        queue.offer(bob, "bob", 2)
        val restored = NativeSaveLibrary.restore(library.json())!!
        val pending = NativeCloudQueue.restore(queue.json())
        assertEquals(alice, pending.next("alice", 0)?.id)
        assertEquals(first, restored.get(alice)?.snapshot)
        assertEquals(bob, pending.next("bob", 0)?.id)
        assertEquals(second, restored.get(bob)?.snapshot)
        assertNotEquals(restored.uploadJson(alice, "alice", 10), restored.uploadJson(bob, "bob", 10))
    }
    @Test fun anOwnedSaveCannotBeUploadedUnderAnotherAccount() {
        val library = NativeSaveLibrary.empty()
        val snapshot = MobileGame.startGame("dem", "normal", 1).saveSnapshot()
        assertTrue(library.save("owned-save", "Campaign", snapshot, 1))
        library.markSynced("owned-save", "alice", 10)
        assertNotNull(library.uploadJson("owned-save", "alice", 11))
        assertNull(library.uploadJson("owned-save", "bob", 11))
        assertEquals("alice", library.get("owned-save")?.cloudOwner)
        assertEquals(snapshot, library.get("owned-save")?.snapshot)
    }
    @Test fun guestsStartFreshSlotsAndSignedInSlotsAreStableForTheSameOwner() {
        assertNotEquals(NativeAutosave.slot(null, "guest-one"), NativeAutosave.slot(null, "guest-two"))
        assertEquals(NativeAutosave.slot("alice", "one"), NativeAutosave.slot("alice", "two"))
        assertNotEquals(NativeAutosave.slot("alice", "one"), NativeAutosave.slot("bob", "one"))
        assertFailsWith<IllegalArgumentException> { NativeAutosave.slot("../bad", "one") }
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
