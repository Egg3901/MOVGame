// Lakeside ID linking, handoff codes, internal auth, activation codes, and the
// platform entitlements consumer. Commerce (Stripe checkout + the purchases
// ledger) moved to the Lakeside platform, so those tests are gone. Uses a
// throwaway SQLite file (CAMPAIGN_DB_PATH must be set before the db module
// loads, hence the dynamic imports).

import { describe, it, expect, beforeAll, afterEach, vi } from "vitest";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

process.env.CAMPAIGN_DB_PATH = join(mkdtempSync(join(tmpdir(), "campaign-test-")), "test.db");
process.env.INTERNAL_TOKEN = "test-internal-token";
process.env.LAKESIDE_AUTH_BASE_URL = "https://auth.example.test";
process.env.LAKESIDE_AUTH_INTERNAL_TOKEN = "test-lakeside-auth-token";

const dbMod = await import("../db.ts");
const activation = await import("../activation.ts");
const lakeside = await import("../lakeside.ts");
const entitlements = await import("../entitlements.ts");

function makeUser(overrides: Partial<{ username: string; email: string; ahd: string | null }> = {}) {
  const id = randomUUID();
  const username = overrides.username ?? `u_${id.slice(0, 8)}`;
  const email = overrides.email ?? `${username}@example.com`;
  dbMod.getDb().prepare(
    "INSERT INTO users (id, username, email, password_hash, created_at, ahd_user_id) VALUES (?, ?, ?, ?, ?, ?)",
  ).run(id, username, email, "x", Date.now(), overrides.ahd ?? null);
  return { id, username, email };
}

beforeAll(() => {
  dbMod.getDb();
});

afterEach(() => {
  vi.restoreAllMocks();
  entitlements.clearEntitlementsCache();
});

describe("platform entitlements consumer", () => {
  function mockPlatform(purchases: unknown) {
    return vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ purchases }), { status: 200 }),
    );
  }

  it("unlocks an owned platform pack, OR'd with local activation codes", async () => {
    const user = makeUser({ ahd: "ahd_ent_1", email: "ent1@example.com" });
    // Local code unlock of the global pack.
    const [code] = activation.generateCodes(null, "global", 1);
    activation.redeemCode(user.id, code);
    // Platform reports the user owns the US historical pack.
    const spy = mockPlatform([{ game: "electioneer", productId: "us-historical", status: "paid" }]);

    const unlocked = await activation.unlockedForUserWithPlatform(user.id);
    expect(unlocked.packIds).toContain("global");        // local code
    expect(unlocked.packIds).toContain("us-historical"); // platform
    expect(unlocked.scenarioIds).toContain("us-2016");   // expanded from the pack
    // Bearer token + query identity are sent to the platform.
    const url = String(spy.mock.calls[0][0]);
    expect(url).toContain("ahdUserId=ahd_ent_1");
    expect((spy.mock.calls[0][1] as RequestInit).headers).toMatchObject({
      Authorization: "Bearer test-internal-token",
    });
  });

  it("ignores refunded and other-game rows", async () => {
    const user = makeUser({ ahd: "ahd_ent_2", email: "ent2@example.com" });
    mockPlatform([
      { game: "electioneer", productId: "complete", status: "refunded" },
      { game: "metroforge", productId: "complete", status: "paid" },
    ]);
    const owned = await activation.unlockedForUserWithPlatform(user.id);
    expect(owned.packIds).toEqual([]);
  });

  it("fails soft (no platform unlock) when the platform errors", async () => {
    const user = makeUser({ ahd: "ahd_ent_3", email: "ent3@example.com" });
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("network down"));
    const unlocked = await activation.unlockedForUserWithPlatform(user.id);
    expect(unlocked.packIds).toEqual([]);
    // Fail soft: when the platform is unreachable the scenario stays locked
    // (paywall on) rather than throwing, so play never crashes on a platform
    // outage. With the paywall off every scenario is free, so it opens instead.
    const { PAYWALL_ENABLED } = await import("../../src/content/scenarioRegistry.js");
    expect(await activation.canAccessScenarioWithPlatform(user.id, "us-2016")).toBe(!PAYWALL_ENABLED);
  });
});

describe("lakeside identity", () => {
  it("maps a lakeside-auth AHD identity and rejects incomplete/non-AHD ones", () => {
    expect(lakeside.identityFromAuth({
      provider: "ahd", id: "ahd123", email: "Player@Example.com", username: "player",
    })).toEqual({ ahdUserId: "ahd123", email: "Player@Example.com", username: "player" });
    expect(lakeside.identityFromAuth({
      provider: "discord", id: "d1", email: "d@e.com", username: "disc",
    })).toBeNull();
    expect(lakeside.identityFromAuth({
      provider: "ahd", id: "ahd123", username: "player",
    })).toBeNull();
  });

  it("builds a lakeside-auth client from env and a login URL", () => {
    const client = lakeside.getLakesideAuthClient();
    expect(client).not.toBeNull();
    expect(client!.loginUrl("ahd", "https://sim.ahousedividedgame.com/api/lakeside/login?return=%2F"))
      .toBe("https://auth.example.test/auth/ahd?return=https%3A%2F%2Fsim.ahousedividedgame.com%2Fapi%2Flakeside%2Flogin%3Freturn%3D%252F");
  });

  it("a proven provider link resolves the same account after an email change", () => {
    const local = makeUser({ email: "linkme@example.com", ahd: "ahd_link_1" });
    expect(lakeside.linkOrCreateUser({ ahdUserId: "ahd_link_1", email: "other@example.com", username: "x" }).id).toBe(local.id);
  });

  it("creates a fresh local user when nothing matches", () => {
    const u = lakeside.linkOrCreateUser({ ahdUserId: "ahd_new_1", email: "brand-new@example.com", username: "NewPlayer" });
    expect(u.ahd_user_id).toBe("ahd_new_1");
    expect(u.email).toBe("brand-new@example.com");
  });

  it("email collisions never replace a password or an existing provider link", () => {
    for (const ahd of [null, "existing-provider-id"]) {
      const local = makeUser({ email: `collision-${ahd}@example.com`, ahd });
      const query = dbMod.getDb().prepare("SELECT ahd_user_id, password_hash FROM users WHERE id = ?");
      const before = query.get(local.id);
      expect(() => lakeside.linkOrCreateUser({ ahdUserId: "attacker", email: local.email.toUpperCase(), username: "attacker" }))
        .toThrow(lakeside.AccountLinkConflictError);
      expect(query.get(local.id)).toEqual(before);
    }
  });
});

describe("handoff codes", () => {
  const identity = { ahdUserId: "ahd_h1", email: "h@example.com", username: "handoff" };

  it("redeems exactly once", () => {
    const code = lakeside.mintHandoffCode(identity);
    expect(lakeside.redeemHandoffCode(code)).toEqual(identity);
    expect(lakeside.redeemHandoffCode(code)).toBeNull();
  });

  it("expires after 60 seconds", () => {
    const now = Date.now();
    const code = lakeside.mintHandoffCode(identity, now);
    expect(lakeside.redeemHandoffCode(code, now + lakeside.HANDOFF_TTL_MS + 1)).toBeNull();
  });

  it("rejects unknown codes", () => {
    expect(lakeside.redeemHandoffCode("not-a-code")).toBeNull();
  });
});

describe("internal API auth", () => {
  it("accepts only the exact bearer token", () => {
    expect(lakeside.checkInternalToken("Bearer test-internal-token")).toBe(true);
    expect(lakeside.checkInternalToken("Bearer wrong")).toBe(false);
    expect(lakeside.checkInternalToken(undefined)).toBe(false);
    expect(lakeside.checkInternalToken("test-internal-token")).toBe(false);
  });
});

describe("return URL allowlist", () => {
  const base = "https://sim.ahousedividedgame.com";
  it("allows relative paths, allowlisted https hosts, and nothing else", () => {
    expect(lakeside.resolveReturnUrl("/foo", base)).toBe(`${base}/foo`);
    expect(lakeside.resolveReturnUrl("https://lakesidegames.net/games/electioneer/", base))
      .toBe("https://lakesidegames.net/games/electioneer/");
    expect(lakeside.resolveReturnUrl("https://evil.example.com/", base)).toBeNull();
    expect(lakeside.resolveReturnUrl("http://lakesidegames.net/", base)).toBeNull();
    expect(lakeside.resolveReturnUrl(undefined, base)).toBe(`${base}/`);
  });
});

describe("code redemption purchases backfill", () => {
  it("records a provider=code purchase row when a code is redeemed", () => {
    const user = makeUser({ ahd: "ahd_code_1" });
    const [code] = activation.generateCodes(null, "global", 1);
    const out = activation.redeemCode(user.id, code);
    expect(out.ok).toBe(true);
    const row = dbMod.getDb().prepare("SELECT * FROM purchases WHERE provider_ref = ?").get(code) as Record<string, unknown>;
    expect(row.provider).toBe("code");
    expect(row.pack_id).toBe("global");
    expect(row.amount_cents).toBe(0);
    expect(row.ahd_user_id).toBe("ahd_code_1");
  });
});

describe('account-link conflict HTTP response', () => {
  it('returns 409 without issuing a token or changing the existing account', async () => {
    const { default: express } = await import('express');
    const { lakesideRouter } = await import('../routes/lakeside.ts');
    const local = makeUser({ email: 'http-collision@example.com', ahd: 'real-owner' });
    const code = lakeside.mintHandoffCode({ ahdUserId: 'different-owner', email: local.email, username: 'different' });
    const app = express();
    app.use(express.json());
    app.use(lakesideRouter);
    const server = app.listen(0, '127.0.0.1');
    await new Promise<void>(resolve => server.once('listening', resolve));
    try {
      const port = (server.address() as { port: number }).port;
      const response = await fetch(`http://127.0.0.1:${port}/api/lakeside/exchange`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }),
      });
      expect(response.status).toBe(409);
      const body = await response.json();
      expect(body.code).toBe('ACCOUNT_LINK_REQUIRES_PROOF');
      expect(body.token).toBeUndefined();
      const row = dbMod.getDb().prepare('SELECT ahd_user_id, password_hash FROM users WHERE id = ?').get(local.id);
      expect(row).toEqual({ ahd_user_id: 'real-owner', password_hash: 'x' });
    } finally {
      await new Promise<void>(resolve => server.close(() => resolve()));
    }
  });
});
