package com.lakesidegames.electioneer.engine

// Convenience: look a party up by id within a system.
// Port of partyById in src/engine/system.ts (the type-level descriptors
// already live in Types.kt).
fun partyById(system: PoliticalSystem, id: PartyId): PartyDef? =
    system.parties.find { it.id == id }
