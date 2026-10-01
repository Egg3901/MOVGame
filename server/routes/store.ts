import { Router } from "express";
import { requireAuth, type AuthedRequest } from "../auth.js";
import { identityForUser, clearEntitlementsCache } from "../entitlements.js";
import { unlockedForUser } from "../activation.js";
import { PACKS_BY_ID } from "../../src/content/packs.js";

export const storeRouter = Router();
storeRouter.use((_req, res, next) => {
  res.set("Cache-Control", "private, no-store");
  next();
});

async function platform(path: string, data?: Record<string, unknown>): Promise<{ status: number; body: Record<string, unknown> }> {
  const token = process.env.MOV_STORE_BRIDGE_TOKEN;
  if (data && !token) return { status: 503, body: { error: "Shared store purchases are not configured yet." } };
  try {
    const base = process.env.LAKESIDE_STORE_URL || "https://lakesidegames.net/account/api/store";
    const response = await fetch(base.replace(/\/+$/, "") + "/" + path, {
      method: data ? "POST" : "GET",
      headers: { "Content-Type": "application/json", ...(data ? { Authorization: "Bearer " + token } : {}) },
      body: data ? JSON.stringify(data) : undefined,
      redirect: "error",
      signal: AbortSignal.timeout(60_000),
    });
    const body = await response.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("Invalid platform response");
    // An upstream credential failure is not a revoked player session.
    return { status: response.status === 401 ? 503 : response.status, body: body as Record<string, unknown> };
  } catch {
    return { status: 503, body: { error: "Store verification is temporarily unavailable. Try again." } };
  }
}

storeRouter.get("/catalog", async (_req, res) => {
  const result = await platform("catalog");
  if (result.status !== 200) return res.json({ sharedOwnership: true, purchasesEnabled: false, products: [] });
  res.json(result.body);
});

storeRouter.use(requireAuth);
storeRouter.post(["/binding", "/verify", "/ownership"], async (req: AuthedRequest, res) => {
  const userId = req.auth!.userId;
  const identity = identityForUser(userId);
  if (!identity?.ahdUserId) return res.status(403).json({ error: "Sign in with your Lakeside account to manage shared purchases." });
  const path = req.path.slice(1);
  const data: Record<string, unknown> = { ahdUserId: identity.ahdUserId };
  if (path === "verify") {
    const body = req.body;
    if (body?.store === "apple" && typeof body.signedTransaction === "string" && body.signedTransaction.length <= 40_000) {
      data.store = "apple"; data.signedTransaction = body.signedTransaction;
    } else if (body?.store === "google" && typeof body.purchaseToken === "string" && body.purchaseToken.length <= 4096) {
      data.store = "google"; data.purchaseToken = body.purchaseToken;
    } else return res.status(400).json({ error: "A supported store receipt is required." });
  }
  const result = await platform(path, data);
  if (result.status !== 200) return res.status(result.status >= 400 && result.status < 600 ? result.status : 503)
    .json({ error: typeof result.body.error === "string" ? result.body.error : "Store verification is temporarily unavailable." });
  if (path === "verify") {
    clearEntitlementsCache();
    return res.json(result.body);
  }
  if (result.body.owner !== identity.ahdUserId) return res.status(503).json({ error: "Shared account ownership could not be verified." });
  if (path === "ownership") {
    const packs = result.body.packIds;
    if (!Array.isArray(packs) || !packs.every(p => typeof p === "string" && !!PACKS_BY_ID[p]) ||
        typeof result.body.verifiedAt !== "number" || !Number.isSafeInteger(result.body.verifiedAt) ||
        result.body.verifiedAt < Date.now() - 300_000 || result.body.verifiedAt > Date.now() + 300_000) {
      return res.status(503).json({ error: "Shared account ownership could not be read." });
    }
    const local = unlockedForUser(userId).packIds;
    return res.json({ owner: userId, packIds: [...new Set([...packs, ...local])], verifiedAt: result.body.verifiedAt });
  }
  res.json({ ...result.body, owner: userId });
});
