// Lakeside ID: map a verified A House Divided identity (obtained via the
// central lakeside-auth broker) onto a local Margin of Victory user. Also mints and
// redeems the 60 second single-use SSO handoff codes that other
// lakesidegames.net consumers (the account portal) use.
//
// AHD game-JWT verification used to live here (copied from LSGD-ops-dash).
// That now lives in lakeside-auth; this module only consumes the normalized
// identity the broker returns after a browser bounce through /auth/ahd.

import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import bcrypt from "bcryptjs";
import { getDb, type UserRow } from "./db.js";
import { LakesideAuthClient, type LakesideAuthIdentity } from "./lakesideAuthClient.js";

export interface LakesideIdentity {
  ahdUserId: string;
  email: string;
  username: string;
}

export const DEFAULT_BASE_URL = "https://sim.ahousedividedgame.com";

// BASE_URL is also a Vite-reserved env name ("/" under vitest), so only honor
// values that are actual absolute URLs.
export function configuredBaseUrl(): string {
  const raw = process.env.BASE_URL;
  return (raw && /^https?:\/\//.test(raw) ? raw : DEFAULT_BASE_URL).replace(/\/+$/, "");
}

/** Server-to-server client for lakeside-auth, or null when env is incomplete. */
export function getLakesideAuthClient(): LakesideAuthClient | null {
  const baseUrl = process.env.LAKESIDE_AUTH_BASE_URL;
  const internalToken = process.env.LAKESIDE_AUTH_INTERNAL_TOKEN;
  if (!baseUrl || !internalToken) return null;
  return new LakesideAuthClient({ baseUrl, internalToken });
}

/**
 * Optional AHD-sibling origin for the browser redirect (cookie is scoped to
 * .ahousedividedgame.com). Falls back to LAKESIDE_AUTH_BASE_URL when unset.
 */
export function lakesideAuthAhdOrigin(): string | undefined {
  const origin = process.env.LAKESIDE_AUTH_AHD_ORIGIN;
  return origin?.replace(/\/+$/, "") || undefined;
}

/** Map a lakeside-auth AHD identity onto the local linking shape, or null. */
export function identityFromAuth(identity: LakesideAuthIdentity): LakesideIdentity | null {
  if (identity.provider !== "ahd") return null;
  if (!identity.id || !identity.email || !identity.username) return null;
  return { ahdUserId: identity.id, email: identity.email, username: identity.username };
}

// ── Local account linking ────────────────────────────────────────────────────
// Resolve by the stable provider ID. A matching email cannot prove account
// ownership or authorize replacing an existing link or password.

const USERNAME_RE = /^[a-zA-Z0-9_]{3,20}$/;

function availableUsername(db: ReturnType<typeof getDb>, wanted: string): string {
  let base = wanted.replace(/[^a-zA-Z0-9_]/g, "_").slice(0, 20);
  if (!USERNAME_RE.test(base)) base = `player_${randomBytes(3).toString("hex")}`;
  let candidate = base;
  for (let i = 2; db.prepare("SELECT 1 FROM users WHERE username = ?").get(candidate); i++) {
    const suffix = String(i);
    candidate = base.slice(0, 20 - suffix.length) + suffix;
  }
  return candidate;
}

export class AccountLinkConflictError extends Error {
  constructor() { super("An existing account needs ownership verification before linking. Sign in using its existing method."); }
}

/**
 * Resolve a Lakeside identity to a local user. `signedInUserId` is the local
 * account whose session made the request: holding that session and the fresh
 * Lakeside identity together proves ownership of both, so an unlinked local
 * account is linked in place instead of tripping the email-collision guard.
 */
export function linkOrCreateUser(identity: LakesideIdentity, signedInUserId?: string | null): UserRow {
  const db = getDb();
  const email = identity.email.toLowerCase();

  const byAhd = db.prepare("SELECT * FROM users WHERE ahd_user_id = ?").get(identity.ahdUserId) as UserRow | undefined;
  if (byAhd) return byAhd;

  if (signedInUserId) {
    const linked = db.prepare("UPDATE users SET ahd_user_id = ? WHERE id = ? AND ahd_user_id IS NULL")
      .run(identity.ahdUserId, signedInUserId);
    if (linked.changes === 1) return db.prepare("SELECT * FROM users WHERE id = ?").get(signedInUserId) as UserRow;
  }

  // A random unusable password: nothing bcrypt-verifies against it, so the
  // local password door is closed until the player deliberately sets one.
  const unusablePassword = () => bcrypt.hashSync(randomBytes(32).toString("hex"), 10);

  const byEmail = db.prepare("SELECT id FROM users WHERE email = ?").get(email);
  if (byEmail) throw new AccountLinkConflictError();

  const id = randomUUID();
  const username = availableUsername(db, identity.username);
  db.prepare(
    "INSERT INTO users (id, username, email, password_hash, created_at, ahd_user_id) VALUES (?, ?, ?, ?, ?, ?)",
  ).run(id, username, email, unusablePassword(), Date.now(), identity.ahdUserId);
  return db.prepare("SELECT * FROM users WHERE id = ?").get(id) as UserRow;
}

// ── Single-use handoff codes (60s TTL) ───────────────────────────────────────
// Kept local: lakeside-accounts still calls /api/lakeside/handoff and
// /api/internal/redeem-handoff on this host. Routing that pair through
// lakeside-auth would force a Phase-2 change on the portal; identity proof
// already goes through the broker, which is the part that needed centralizing.

export const HANDOFF_TTL_MS = 60_000;

export function mintHandoffCode(identity: LakesideIdentity, now = Date.now()): string {
  const code = randomBytes(24).toString("base64url");
  getDb().prepare(
    "INSERT INTO handoff_codes (code, ahd_user_id, email, username, expires_at, used) VALUES (?, ?, ?, ?, ?, 0)",
  ).run(code, identity.ahdUserId, identity.email, identity.username, now + HANDOFF_TTL_MS);
  return code;
}

/** Redeem a handoff code exactly once. Expired, unknown, or used → null. */
export function redeemHandoffCode(code: string, now = Date.now()): LakesideIdentity | null {
  const db = getDb();
  const claim = db.prepare(
    "UPDATE handoff_codes SET used = 1 WHERE code = ? AND used = 0 AND expires_at > ?",
  ).run(code, now);
  if (claim.changes === 0) return null;
  const row = db.prepare("SELECT ahd_user_id, email, username FROM handoff_codes WHERE code = ?").get(code) as
    { ahd_user_id: string; email: string; username: string };
  // Opportunistic sweep so the table never accumulates.
  db.prepare("DELETE FROM handoff_codes WHERE expires_at < ?").run(now - HANDOFF_TTL_MS);
  return { ahdUserId: row.ahd_user_id, email: row.email, username: row.username };
}

// ── Internal API auth (account portal) ───────────────────────────────────────

export function checkInternalToken(header: string | undefined): boolean {
  const expected = process.env.INTERNAL_TOKEN;
  if (!expected || !header?.startsWith("Bearer ")) return false;
  const got = Buffer.from(header.slice(7));
  const want = Buffer.from(expected);
  return got.length === want.length && timingSafeEqual(got, want);
}

/** Constant-time secret compare; false when the secret is unset (fail closed). */
export function secretEquals(provided: unknown, expected: string | undefined): boolean {
  if (!expected || typeof provided !== "string") return false;
  const a = Buffer.from(provided);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

// ── Return URL allowlist (contract: https only, exact hostnames) ─────────────

const ALLOWED_RETURN_HOSTS = new Set(["lakesidegames.net", "www.lakesidegames.net", "sim.ahousedividedgame.com"]);

/** Resolve a ?return= value to a safe absolute URL, or null if not allowed. */
export function resolveReturnUrl(raw: string | undefined, fallbackBase: string): string | null {
  const base = fallbackBase.replace(/\/+$/, "");
  if (!raw) return base + "/";
  if (raw.startsWith("/")) return base + raw; // relative path: same host
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:" || !ALLOWED_RETURN_HOSTS.has(url.hostname)) return null;
    return url.toString();
  } catch {
    return null;
  }
}
