// Platform entitlements consumer. Commerce lives on the Lakeside platform now
// (lakesidegames.net/account); this game is a consumer. We ask the platform
// which Margin of Victory products a signed-in identity owns and treat those as pack
// unlocks. Local activation-code unlocks are separate and OR'd on top by the
// caller (see activation.ts).
//
// Fail soft: any network error, timeout, missing token, or bad response yields
// an empty list so the game never blocks play on the platform being reachable.

import { getDb } from "./db.js";

const GAME = "electioneer";
const CACHE_MS = 30_000;
const TIMEOUT_MS = 5_000;

function entitlementsUrl(): string {
  return process.env.LAKESIDE_ENTITLEMENTS_URL || "https://lakesidegames.net/account/api/entitlements";
}

function redeemUrl(): string {
  return process.env.LAKESIDE_REDEEM_URL || "https://lakesidegames.net/account/api/redeem-code";
}

export interface RedeemResult {
  ok: boolean;
  error?: string;
  productId?: string;
  name?: string;
}

/**
 * Redeem an activation code on the Lakeside platform for an identity. The
 * platform owns the code table and records the $0 unlock in the one ledger, so
 * the grant then flows back through fetchPlatformPurchases like any purchase.
 * Fails soft with a friendly error when the platform is unreachable.
 */
export async function redeemCodeOnPlatform(identity: Identity, code: string): Promise<RedeemResult> {
  const token = process.env.INTERNAL_TOKEN;
  if (!token) return { ok: false, error: "Code redemption is temporarily unavailable" };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(redeemUrl(), {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ahdUserId: identity.ahdUserId ?? "", email: identity.email ?? "", code }),
      signal: controller.signal,
    });
    const body = (await res.json().catch(() => ({}))) as RedeemResult;
    if (res.ok && body.ok) {
      clearEntitlementsCache(); // surface the new grant immediately, not after the ~30s cache
      return body;
    }
    return { ok: false, error: body.error || "Could not redeem that code" };
  } catch {
    return { ok: false, error: "Code redemption is temporarily unavailable" };
  } finally {
    clearTimeout(timer);
  }
}

export interface PlatformPurchase {
  game: string;
  productId: string;
  name: string | null;
  provider: "stripe" | "code" | "apple" | "google" | "supporter";
  amountCents: number | null;
  currency: string | null;
  status: string;
  createdAt: number;
}

export interface Identity {
  ahdUserId: string | null;
  email: string | null;
}

interface CacheEntry { at: number; purchases: PlatformPurchase[] }
const cache = new Map<string, CacheEntry>();

/** Look up the local user's Lakeside identity (ahd_user_id + email). */
export function identityForUser(userId: string): Identity | null {
  const row = getDb().prepare("SELECT email, ahd_user_id FROM users WHERE id = ?").get(userId) as
    | { email: string; ahd_user_id: string | null }
    | undefined;
  if (!row) return null;
  return { ahdUserId: row.ahd_user_id, email: row.email };
}

/**
 * Fetch the platform's Margin of Victory purchases for an identity. Cached ~30s per
 * identity. Returns [] on any failure.
 */
export async function fetchPlatformPurchaseHistory(identity: Identity): Promise<PlatformPurchase[]> {
  const ahdUserId = identity.ahdUserId ?? "";
  const email = (identity.email ?? "").toLowerCase();
  if (!ahdUserId) return [];

  const token = process.env.INTERNAL_TOKEN;
  if (!token) return [];

  const key = `${ahdUserId}|${email}`;
  const now = Date.now();
  const hit = cache.get(key);
  if (hit && now - hit.at < CACHE_MS) return hit.purchases;

  const url = new URL(entitlementsUrl());
  url.searchParams.set("ahdUserId", ahdUserId);
  url.searchParams.set("email", email);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url.toString(), {
      headers: { Authorization: `Bearer ${token}` },
      signal: controller.signal,
    });
    if (!res.ok) return [];
    const body = (await res.json().catch(() => ({}))) as { purchases?: unknown };
    const raw = Array.isArray(body.purchases) ? body.purchases : [];
    const purchases: PlatformPurchase[] = raw
      .map((p) => p as Record<string, unknown>)
      .filter((p) => p && p.game === GAME && ["paid", "refunded"].includes(String(p.status)))
      .map((p) => ({
        game: GAME,
        productId: String(p.productId ?? p.packId ?? ""),
        name: (p.name ?? p.packName ?? null) as string | null,
        provider: ["apple", "google", "code", "stripe", "supporter"].includes(String(p.provider))
          ? p.provider as PlatformPurchase["provider"] : Number(p.amountCents ?? 0) > 0 ? "stripe" : "code",
        amountCents: typeof p.amountCents === "number" && Number.isSafeInteger(p.amountCents) ? p.amountCents : null,
        currency: typeof p.currency === "string" ? p.currency : null,
        status: String(p.status ?? "paid"),
        createdAt: Number(p.createdAt ?? now),
      }))
      .filter((p) => p.productId);
    cache.set(key, { at: now, purchases });
    return purchases;
  } catch {
    return [];
  } finally {
    clearTimeout(timer);
  }
}

export async function fetchPlatformPurchases(identity: Identity): Promise<PlatformPurchase[]> {
  return (await fetchPlatformPurchaseHistory(identity)).filter(p => p.status === "paid");
}

/** The distinct Margin of Victory product (pack) ids the identity owns. */
export async function ownedProductIds(identity: Identity): Promise<string[]> {
  const purchases = await fetchPlatformPurchases(identity);
  return [...new Set(purchases.map((p) => p.productId))];
}

/** Test-only: drop the per-identity cache. */
export function clearEntitlementsCache(): void {
  cache.clear();
}
