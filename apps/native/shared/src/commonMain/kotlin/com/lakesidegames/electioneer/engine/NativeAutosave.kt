package com.lakesidegames.electioneer.engine

// A pending cloud write references a library slot. Keep that slot attached to
// its campaign owner when another account starts a new campaign on this device.
class NativeAutosave private constructor() {
    companion object {
        fun slot(owner: String?, nonce: String): String {
            val id = "autosave-${owner ?: nonce}"
            require(NativeSaveLibrary.validId(id))
            return id
        }
    }
}
