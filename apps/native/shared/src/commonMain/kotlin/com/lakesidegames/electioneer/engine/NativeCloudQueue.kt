package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class NativeCloudWrite(val id: String, val owner: String, val revision: Long,
    val failures: Int = 0, val retryAt: Long = 0, val blocked: Boolean = false, val blockedReason: String = "")
@Serializable private data class NativeCloudCheckpoint(val id: String, val owner: String, val revision: Long)
@Serializable private data class NativeCloudOutbox(val version: Int = 1, val writes: List<NativeCloudWrite> = emptyList(), val checkpoints: List<NativeCloudCheckpoint> = emptyList())
data class NativeCloudOutcome(val status: String, val version: Long = -1, val message: String = "")

// Durable coalescing outbox. Network adapters never replace local campaign data.
class NativeCloudQueue private constructor(private var writes: List<NativeCloudWrite>, private var checkpoints: List<NativeCloudCheckpoint> = emptyList()) {
    companion object {
        fun empty() = NativeCloudQueue(emptyList())
        fun restore(json: String?): NativeCloudQueue = runCatching {
            val box = EngineJson.decodeFromString<NativeCloudOutbox>(json ?: return empty())
            require(box.version == 1)
            require(box.writes.all { NativeSaveLibrary.validId(it.id) && it.owner.isNotBlank() && it.revision >= 0 && it.failures >= 0 })
            require(box.writes.map { it.owner to it.id }.distinct().size == box.writes.size)
            require(box.checkpoints.all { NativeSaveLibrary.validId(it.id) && it.owner.isNotBlank() && it.revision >= 0 })
            NativeCloudQueue(box.writes, box.checkpoints)
        }.getOrElse { empty() }
    }
    fun json(): String = EngineJson.encodeToString(NativeCloudOutbox(writes = writes, checkpoints = checkpoints))
    fun offer(id: String, owner: String, revision: Long) {
        if (!NativeSaveLibrary.validId(id) || owner.isBlank() || revision < 0) return
        if (checkpoints.any { it.id == id && it.owner == owner && it.revision >= revision }) return
        val previous = writes.firstOrNull { it.id == id && it.owner == owner }
        if (previous != null && previous.revision >= revision) return
        val next = NativeCloudWrite(id, owner, revision, blocked = previous?.blocked == true, blockedReason = previous?.blockedReason ?: "")
        writes = writes.filterNot { it.id == id && it.owner == owner } + next
    }
    fun next(owner: String, now: Long): NativeCloudWrite? = writes.firstOrNull { it.owner == owner && !it.blocked && it.retryAt <= now }
    fun delayMillis(owner: String, now: Long): Long = writes.filter { it.owner == owner && !it.blocked }
        .minOfOrNull { (it.retryAt - now).coerceAtLeast(0) } ?: -1
    fun pending(owner: String): Int = writes.count { it.owner == owner }
    fun conflicted(owner: String): Boolean = writes.any { it.owner == owner && it.blocked && it.blockedReason == "conflict" }
    fun acknowledge(write: NativeCloudWrite) = acknowledgeRevision(write.id, write.owner, write.revision)
    fun acknowledgeRevision(id: String, owner: String, revision: Long) {
        writes = writes.filterNot { it.owner == owner && it.id == id && it.revision <= revision }
        val previous = checkpoints.firstOrNull { it.id == id && it.owner == owner }
        checkpoints = checkpoints.filterNot { it.id == id && it.owner == owner } + NativeCloudCheckpoint(id, owner, maxOf(revision, previous?.revision ?: -1))
    }
    fun fail(write: NativeCloudWrite, outcome: NativeCloudOutcome, now: Long) {
        writes = writes.map {
            if (it.id != write.id || it.owner != write.owner) it else {
                val failures = (it.failures + 1).coerceAtMost(10)
                val blocked = outcome.status in listOf("conflict", "unsupported", "rejected")
                it.copy(failures = failures, retryAt = now + minOf(300_000L, 15_000L * (1L shl minOf(failures, 5))), blocked = blocked, blockedReason = if (blocked) outcome.status else "")
            }
        }
    }
    fun retry(owner: String) { writes = writes.map { if (it.owner == owner) it.copy(failures = 0, retryAt = 0, blocked = false, blockedReason = "") else it } }
    fun remove(id: String) { writes = writes.filterNot { it.id == id }; checkpoints = checkpoints.filterNot { it.id == id } }
}
