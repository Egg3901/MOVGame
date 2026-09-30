package com.lakesidegames.electioneer.engine

// Like the web's twelve-week ring, undo is session-local. Serialized snapshots
// isolate nested mutable engine state from later actions and event answers.
class NativeUndoHistory {
    private val snapshots = mutableListOf<String>()
    fun available(): Boolean = snapshots.isNotEmpty()
    fun record(snapshot: String) {
        snapshots.add(snapshot)
        if (snapshots.size > 12) snapshots.removeAt(0)
    }
    fun take(): String? = if (snapshots.isEmpty()) null else snapshots.removeAt(snapshots.lastIndex)
    fun clear() { snapshots.clear() }
}
