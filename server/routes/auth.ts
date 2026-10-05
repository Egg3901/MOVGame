import { Router } from "express";
import { randomUUID } from "node:crypto";
import { getDb, type UserRow } from "../db.js";
import { signToken, hashPassword, checkPassword, requireAuth, type AuthedRequest } from "../auth.js";
import { unlockedForUserWithPlatform } from "../activation.js";
import { identityForUser, redeemCodeOnPlatform } from "../entitlements.js";

const USERNAME_RE = /^[a-zA-Z0-9_]{3,20}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const authRouter = Router();

authRouter.post("/register", async (req, res) => {
  const { username, email, password } = req.body ?? {};
  if (typeof username !== "string" || !USERNAME_RE.test(username)) {
    return res.status(400).json({ error: "Username must be 3–20 characters (letters, numbers, underscore)" });
  }
  if (typeof email !== "string" || !EMAIL_RE.test(email)) {
    return res.status(400).json({ error: "Invalid email address" });
  }
  if (typeof password !== "string" || password.length < 8) {
    return res.status(400).json({ error: "Password must be at least 8 characters" });
  }

  const db = getDb();
  const normEmail = email.toLowerCase();
  if (db.prepare("SELECT 1 FROM users WHERE email = ?").get(normEmail)) {
    return res.status(409).json({ error: "Email already registered" });
  }
  if (db.prepare("SELECT 1 FROM users WHERE username = ?").get(username)) {
    return res.status(409).json({ error: "Username already taken" });
  }

  const id = randomUUID();
  db.prepare("INSERT INTO users (id, username, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)").run(
    id, username, normEmail, await hashPassword(password), Date.now(),
  );
  res.json({ token: signToken({ userId: id, username }), user: { id, username, email: normEmail } });
});

authRouter.post("/login", async (req, res) => {
  const { email, password } = req.body ?? {};
  if (typeof email !== "string" || typeof password !== "string") {
    return res.status(400).json({ error: "Email and password required" });
  }
  const user = getDb().prepare("SELECT * FROM users WHERE email = ?").get(email.toLowerCase()) as UserRow | undefined;
  if (!user || !(await checkPassword(password, user.password_hash))) {
    return res.status(401).json({ error: "Invalid email or password" });
  }
  res.json({
    token: signToken({ userId: user.id, username: user.username }),
    user: { id: user.id, username: user.username, email: user.email },
  });
});

authRouter.get("/me", requireAuth, async (req: AuthedRequest, res) => {
  const row = getDb().prepare("SELECT id, username, email, created_at, ahd_user_id FROM users WHERE id = ?")
    .get(req.auth!.userId) as { id: string; username: string; email: string; created_at: number; ahd_user_id: string | null } | undefined;
  if (!row) return res.status(404).json({ error: "User not found" });
  const { ahd_user_id, ...user } = row;
  res.json({ user: { ...user, ahdLinked: !!ahd_user_id }, unlocked: await unlockedForUserWithPlatform(req.auth!.userId) });
});

// Account deletion (App Store Guideline 5.1.1(v)). Removes the game account and
// everything keyed to it: saves, scores, achievements, activations. Purchases
// recorded on the Lakeside platform belong to the Lakeside identity and stay
// there. Activation codes the player redeemed are kept as spent, unlinked.
authRouter.delete("/account", requireAuth, (req: AuthedRequest, res) => {
  const userId = req.auth!.userId;
  const db = getDb();
  const removed = db.transaction(() => {
    for (const table of ["cloud_saves", "leaderboard", "daily_scores", "achievements", "activations", "purchases"]) {
      db.prepare(`DELETE FROM ${table} WHERE user_id = ?`).run(userId);
    }
    db.prepare("UPDATE activation_codes SET redeemed_by = NULL WHERE redeemed_by = ?").run(userId);
    return db.prepare("DELETE FROM users WHERE id = ?").run(userId).changes;
  })();
  if (removed === 0) return res.status(404).json({ error: "User not found" });
  res.json({ deleted: true });
});

authRouter.post("/activate", requireAuth, async (req: AuthedRequest, res) => {
  const { code } = req.body ?? {};
  if (typeof code !== "string" || !code.trim()) return res.status(400).json({ error: "Code required" });
  // Codes are redeemed on the Lakeside platform now (one ledger for codes and
  // Stripe purchases). The grant flows back through platform entitlements.
  const identity = identityForUser(req.auth!.userId);
  if (!identity) return res.status(400).json({ error: "Sign in with your Lakeside account to redeem a code" });
  const out = await redeemCodeOnPlatform(identity, code);
  if (!out.ok) return res.status(400).json({ error: out.error });
  res.json({ packId: out.productId, packName: out.name, unlocked: await unlockedForUserWithPlatform(req.auth!.userId) });
});

authRouter.get("/activations", requireAuth, async (req: AuthedRequest, res) => {
  const rows = getDb().prepare("SELECT * FROM activations WHERE user_id = ?").all(req.auth!.userId);
  res.json({ activations: rows, unlocked: await unlockedForUserWithPlatform(req.auth!.userId) });
});
