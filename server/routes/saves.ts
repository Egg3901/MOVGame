// CLOUD SAVES routes. Cross-device save sync for signed-in players. Dexie
// remains the client's source of truth; these endpoints mirror the game
// snapshot (and its optional replay log) per user so a save made on one device
// shows up on another. Every route requires auth and is scoped to req.auth.
//
// Quota: a player keeps at most MAX_SAVES cloud saves, and each record's stored
// blobs (snapshot + replay) must stay under MAX_RECORD_BYTES. Oversize or
// over-quota writes are rejected with a readable message so the client can warn
// and fall back to local-only silently.

import { Router } from "express";
import { getDb } from "../db.js";
import { requireAuth, type AuthedRequest } from "../auth.js";

export const savesRouter = Router();

export const MAX_SAVES = 20;
export const MAX_RECORD_BYTES = 2 * 1024 * 1024; // Full campaigns include accumulated cause history.

function byteLen(s: string): number {
  return Buffer.byteLength(s, "utf8");
}

interface SaveRow {
  save_id: string;
  name: string;
  turn: number;
  player_candidate: string | null;
  state: string;
  replay: string | null;
  updated_at: number;
}

function parse<T>(json: string | null): T | null {
  if (json == null) return null;
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

// GET /api/saves — the caller's save list (metadata only, newest first).
savesRouter.get("/", requireAuth, (req: AuthedRequest, res) => {
  const rows = getDb().prepare(
    "SELECT save_id, name, turn, player_candidate, updated_at, (replay IS NOT NULL) AS has_replay FROM cloud_saves WHERE user_id = ? ORDER BY updated_at DESC",
  ).all(req.auth!.userId) as (Omit<SaveRow, "state" | "replay"> & { has_replay: number })[];

  res.json({
    versionPreconditions: true,
    saves: rows.map((r) => ({
      id: r.save_id,
      name: r.name,
      turn: r.turn,
      playerCandidate: parse(r.player_candidate),
      updatedAt: r.updated_at,
      hasReplay: r.has_replay === 1,
    })),
  });
});

// GET /api/saves/:id — one full save record (snapshot + replay log).
savesRouter.get("/:id", requireAuth, (req: AuthedRequest, res) => {
  const row = getDb().prepare(
    "SELECT save_id, name, turn, player_candidate, state, replay, updated_at FROM cloud_saves WHERE user_id = ? AND save_id = ?",
  ).get(req.auth!.userId, req.params.id) as SaveRow | undefined;
  if (!row) return res.status(404).json({ error: "Save not found" });

  res.json({
    id: row.save_id,
    name: row.name,
    turn: row.turn,
    playerCandidate: parse(row.player_candidate),
    state: parse(row.state),
    replay: parse(row.replay),
    updatedAt: row.updated_at,
  });
});

// PUT /api/saves/:id — create or replace a save record. Enforces per-user
// count and per-record size quotas.
savesRouter.put("/:id", requireAuth, (req: AuthedRequest, res) => {
  const userId = req.auth!.userId;
  const saveId = req.params.id;
  const body = (req.body ?? {}) as {
    name?: unknown;
    turn?: unknown;
    playerCandidate?: unknown;
    state?: unknown;
    replay?: unknown;
    updatedAt?: unknown;
    expectedUpdatedAt?: unknown;
  };

  if (body.state === undefined || body.state === null) {
    return res.status(400).json({ error: "state is required" });
  }
  const name = typeof body.name === "string" && body.name.length > 0 ? body.name : "Save";
  const turn = Number.isFinite(Number(body.turn)) ? Math.trunc(Number(body.turn)) : 0;
  let updatedAt = Number.isFinite(Number(body.updatedAt)) ? Math.trunc(Number(body.updatedAt)) : Date.now();
  const stateJson = JSON.stringify(body.state);
  const playerJson = body.playerCandidate === undefined ? null : JSON.stringify(body.playerCandidate);
  // The snapshot write never carries the replay log (it has its own endpoint),
  // so an existing replay is preserved across a plain save.
  const replayJson = "replay" in body && body.replay != null ? JSON.stringify(body.replay) : null;

  const db = getDb();
  const existing = db.prepare(
    "SELECT replay, updated_at FROM cloud_saves WHERE user_id = ? AND save_id = ?",
  ).get(userId, saveId) as { replay: string | null; updated_at: number } | undefined;

  // Native clients retain the version they loaded. Reject stale uploads so a
  // second device cannot silently replace a newer campaign. Existing clients
  // that omit the precondition can still write, but every mutation advances
  // the version even if another device's clock is behind.
  if ("expectedUpdatedAt" in body) {
    const expected = body.expectedUpdatedAt;
    if (expected !== null && (typeof expected !== "number" || !Number.isSafeInteger(expected) || expected < 0)) {
      return res.status(400).json({ error: "expectedUpdatedAt must be a timestamp or null" });
    }
    if ((existing?.updated_at ?? null) !== expected) {
      return res.status(409).json({ error: "This cloud save changed on another device. Download it or upload your campaign as a new save.",
        conflict: true, updatedAt: existing?.updated_at ?? null });
    }
  }

  updatedAt = Math.max(updatedAt, (existing?.updated_at ?? -1) + 1);

  const nextReplay = "replay" in body ? replayJson : existing?.replay ?? null;
  const size = byteLen(stateJson) + (nextReplay ? byteLen(nextReplay) : 0);
  if (size > MAX_RECORD_BYTES) {
    return res.status(413).json({
      error: `Save is too large to sync (${Math.round(size / 1024)}KB, limit ${Math.round(MAX_RECORD_BYTES / 1024)}KB). It stays saved on this device.`,
    });
  }

  if (!existing) {
    const count = (db.prepare(
      "SELECT COUNT(*) AS c FROM cloud_saves WHERE user_id = ?",
    ).get(userId) as { c: number }).c;
    if (count >= MAX_SAVES) {
      return res.status(409).json({
        error: `Cloud save limit reached (${MAX_SAVES}). Delete an online save to sync a new one. This save is still kept on this device.`,
      });
    }
  }

  db.prepare(`
    INSERT INTO cloud_saves (user_id, save_id, name, turn, player_candidate, state, replay, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(user_id, save_id) DO UPDATE SET
      name = excluded.name, turn = excluded.turn,
      player_candidate = excluded.player_candidate, state = excluded.state,
      replay = excluded.replay, updated_at = excluded.updated_at
  `).run(userId, saveId, name, turn, playerJson, stateJson, nextReplay, updatedAt);

  res.json({ ok: true, id: saveId, updatedAt });
});

// PUT /api/saves/:id/replay — attach or replace the replay/timeline log for an
// existing save. Kept separate so the (large) snapshot need not be resent when
// only the replay changes. The save must already exist.
savesRouter.put("/:id/replay", requireAuth, (req: AuthedRequest, res) => {
  const userId = req.auth!.userId;
  const saveId = req.params.id;
  const body = (req.body ?? {}) as { log?: unknown; updatedAt?: unknown };
  if (body.log === undefined || body.log === null) {
    return res.status(400).json({ error: "log is required" });
  }

  const db = getDb();
  const row = db.prepare(
    "SELECT state, updated_at FROM cloud_saves WHERE user_id = ? AND save_id = ?",
  ).get(userId, saveId) as { state: string; updated_at: number } | undefined;
  if (!row) return res.status(404).json({ error: "Save not found" });

  const replayJson = JSON.stringify(body.log);
  const size = byteLen(row.state) + byteLen(replayJson);
  if (size > MAX_RECORD_BYTES) {
    return res.status(413).json({
      error: `Replay is too large to sync (${Math.round(size / 1024)}KB, limit ${Math.round(MAX_RECORD_BYTES / 1024)}KB). It stays saved on this device.`,
    });
  }

  const requestedAt = Number.isFinite(Number(body.updatedAt)) ? Math.trunc(Number(body.updatedAt)) : Date.now();
  const updatedAt = Math.max(requestedAt, row.updated_at + 1);
  db.prepare(
    "UPDATE cloud_saves SET replay = ?, updated_at = ? WHERE user_id = ? AND save_id = ?",
  ).run(replayJson, updatedAt, userId, saveId);
  res.json({ ok: true });
});

// GET /api/saves/:id/replay — the replay/timeline log for a save, if any.
savesRouter.get("/:id/replay", requireAuth, (req: AuthedRequest, res) => {
  const row = getDb().prepare(
    "SELECT replay, updated_at FROM cloud_saves WHERE user_id = ? AND save_id = ?",
  ).get(req.auth!.userId, req.params.id) as { replay: string | null; updated_at: number } | undefined;
  if (!row || row.replay == null) return res.status(404).json({ error: "No replay for this save" });
  res.json({ id: req.params.id, updatedAt: row.updated_at, log: parse(row.replay) });
});

// DELETE /api/saves/:id — remove a save record (idempotent).
savesRouter.delete("/:id", requireAuth, (req: AuthedRequest, res) => {
  getDb().prepare(
    "DELETE FROM cloud_saves WHERE user_id = ? AND save_id = ?",
  ).run(req.auth!.userId, req.params.id);
  res.json({ ok: true });
});
