import { describe, it, expect, beforeAll, afterAll } from "vitest";
import express from "express";
import http, { type Server } from "node:http";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

process.env.CAMPAIGN_DB_PATH = join(mkdtempSync(join(tmpdir(), "mov-store-test-")), "test.db");
process.env.JWT_SECRET = "fixture-store-jwt";
process.env.MOV_STORE_BRIDGE_TOKEN = "fixture-store-bridge";
const { getDb } = await import("../db.ts");
const { signToken } = await import("../auth.ts");
const { storeRouter } = await import("../routes/store.ts");
const { fetchPlatformPurchaseHistory, fetchPlatformPurchases, clearEntitlementsCache } = await import("../entitlements.ts");
let api: Server, platform: Server, base: string;
let platformStatus = 200;
let platformBody: Record<string, unknown> = {};
let received: Record<string, unknown> = {}, credential = "";
function user(linked: boolean) {
  const id = randomUUID(), username = "u_" + id.slice(0, 8);
  getDb().prepare("INSERT INTO users (id, username, email, password_hash, created_at, ahd_user_id) VALUES (?, ?, ?, 'x', ?, ?)")
    .run(id, username, username + "@example.com", Date.now(), linked ? "lakeside-" + id : null);
  return { id, owner: "lakeside-" + id, token: signToken({ userId: id, username }) };
}
async function call(path: string, token?: string, body: unknown = {}) {
  const r = await fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) }, body: JSON.stringify(body) });
  return { status: r.status, cache: r.headers.get("cache-control"), body: await r.json() };
}
beforeAll(async () => {
  platform = http.createServer(async (req, res) => {
    const chunks: Buffer[] = []; for await (const chunk of req) chunks.push(chunk);
    received = JSON.parse(Buffer.concat(chunks).toString() || "{}"); credential = req.headers.authorization || "";
    res.writeHead(platformStatus, { "Content-Type": "application/json" }); res.end(JSON.stringify(platformBody));
  });
  await new Promise<void>(r => platform.listen(0, "127.0.0.1", () => r()));
  const address = platform.address() as { port: number };
  process.env.LAKESIDE_STORE_URL = "http://127.0.0.1:" + address.port;
  process.env.LAKESIDE_ENTITLEMENTS_URL = process.env.LAKESIDE_STORE_URL;
  process.env.INTERNAL_TOKEN = "fixture-entitlements";
  const app = express(); app.use(express.json()); app.use("/api/store", storeRouter);
  await new Promise<void>(r => { api = app.listen(0, "127.0.0.1", () => r()); });
  base = "http://127.0.0.1:" + (api.address() as { port: number }).port + "/api/store";
});
afterAll(async () => {
  await Promise.all([new Promise<void>(r => api.close(() => r())), new Promise<void>(r => platform.close(() => r()))]);
});

describe("shared store proxy", () => {
  it("requires authenticated, linked identity on every private operation", async () => {
    for (const path of ["/binding", "/verify", "/ownership"]) expect((await call(path)).status).toBe(401);
    expect((await call("/binding", user(false).token)).status).toBe(403);
  });
  it("derives account ownership and sends only the dedicated bridge credential", async () => {
    const a = user(true);
    platformStatus = 200; platformBody = { owner: a.owner, appAccountToken: randomUUID(), playAccountId: "a".repeat(64), purchasesEnabled: false, products: [] };
    const response = await call("/binding", a.token, { ahdUserId: "victim", email: "victim@example.com" });
    expect(response.status).toBe(200); expect(received).toEqual({ ahdUserId: a.owner });
    expect(credential).toBe("Bearer fixture-store-bridge"); expect(response.body.owner).toBe(a.id);
    expect(response.cache).toBe("private, no-store");
  });
  it("forwards only bounded, supported receipt fields", async () => {
    const a = user(true); platformBody = { verified: true, status: "paid", packId: "global", environment: "Production" };
    expect((await call("/verify", a.token, { store: "google", purchaseToken: "fixture-token", ahdUserId: "victim", packId: "complete" })).status).toBe(200);
    expect(received).toEqual({ ahdUserId: a.owner, store: "google", purchaseToken: "fixture-token" });
    expect((await call("/verify", a.token, { store: "google", purchaseToken: "x".repeat(4097) })).status).toBe(400);
    expect((await call("/verify", a.token, { store: "web", purchaseToken: "x" })).status).toBe(400);
  });
  it("rejects wrong-owner and malformed authoritative wallets", async () => {
    const a = user(true); platformBody = { owner: "other", packIds: ["global"], verifiedAt: Date.now() };
    expect((await call("/ownership", a.token)).status).toBe(503);
    platformBody = { owner: a.owner, packIds: ["unrecognized"], verifiedAt: Date.now() };
    expect((await call("/ownership", a.token)).status).toBe(503);
    platformBody = { owner: a.owner, packIds: ["global"], verifiedAt: Date.now() };
    expect((await call("/ownership", a.token)).body.packIds).toEqual(["global"]);
  });
  it("preserves claim conflicts and keeps upstream auth failures distinct from logout", async () => {
    const a = user(true); platformStatus = 409; platformBody = { error: "This purchase belongs to another Lakeside account." };
    expect((await call("/verify", a.token, { store: "apple", signedTransaction: "signed" })).status).toBe(409);
    platformStatus = 401; platformBody = { error: "Bridge authentication required." };
    expect((await call("/binding", a.token)).status).toBe(503);
    platformStatus = 503;
    const r = await fetch(base + "/catalog");
    expect((await r.json()).purchasesEnabled).toBe(false);
    platformStatus = 200;
  });
  it("does not infer ownership from email and preserves actual providers and refund history", async () => {
    platformBody = { purchases: [
      { game: "electioneer", productId: "global", name: "Global", provider: "apple", amountCents: null, currency: null, status: "refunded", createdAt: 1000 },
      { game: "electioneer", productId: "global", name: "Global", provider: "google", amountCents: null, currency: null, status: "paid", createdAt: 1001 },
      { game: "electioneer", productId: "complete", status: "pending" },
    ] };
    clearEntitlementsCache();
    const identity = { ahdUserId: "history-owner", email: "contact@example.com" };
    const history = await fetchPlatformPurchaseHistory(identity);
    expect(history).toHaveLength(2); expect(history[0].provider).toBe("apple"); expect(history[0].amountCents).toBeNull();
    expect((await fetchPlatformPurchases(identity)).map(p => p.provider)).toEqual(["google"]);
    expect(await fetchPlatformPurchases({ ahdUserId: null, email: identity.email })).toEqual([]);
  });
});
