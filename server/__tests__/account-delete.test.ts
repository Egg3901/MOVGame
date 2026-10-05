// Account deletion: the route removes the user and every row keyed to them,
// leaves other players untouched, and requires a valid session.

import { describe, it, expect, beforeAll, afterAll } from "vitest";
import express from "express";
import type { Server } from "node:http";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

process.env.CAMPAIGN_DB_PATH = join(mkdtempSync(join(tmpdir(), "campaign-delete-test-")), "test.db");
process.env.JWT_SECRET = "test-jwt-secret";

const dbMod = await import("../db.ts");
const auth = await import("../auth.ts");
const { authRouter } = await import("../routes/auth.ts");

let server: Server;
let base: string;

function makeUser() {
  const id = randomUUID();
  const username = `u_${id.slice(0, 8)}`;
  const db = dbMod.getDb();
  db.prepare("INSERT INTO users (id, username, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)")
    .run(id, username, `${username}@example.com`, "x", Date.now());
  db.prepare("INSERT INTO achievements (user_id, scenario_id, achievement_id, earned_at) VALUES (?, ?, ?, ?)").run(id, "us-2024", "first-win", Date.now());
  return { id, token: auth.signToken({ userId: id, username }) };
}

beforeAll(async () => {
  const app = express();
  app.use(express.json());
  app.use("/api/auth", authRouter);
  await new Promise<void>((resolve) => { server = app.listen(0, resolve); });
  const addr = server.address();
  base = `http://127.0.0.1:${typeof addr === "object" && addr ? addr.port : 0}`;
});
afterAll(() => server?.close());

describe("DELETE /api/auth/account", () => {
  it("requires a session", async () => {
    const res = await fetch(`${base}/api/auth/account`, { method: "DELETE" });
    expect(res.status).toBe(401);
  });

  it("removes the account and its rows, and nobody else's", async () => {
    const victim = makeUser();
    const other = makeUser();
    const res = await fetch(`${base}/api/auth/account`, { method: "DELETE", headers: { Authorization: `Bearer ${victim.token}` } });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ deleted: true });
    const db = dbMod.getDb();
    expect(db.prepare("SELECT 1 FROM users WHERE id = ?").get(victim.id)).toBeUndefined();
    expect(db.prepare("SELECT COUNT(*) AS n FROM achievements WHERE user_id = ?").get(victim.id)).toEqual({ n: 0 });
    expect(db.prepare("SELECT 1 FROM users WHERE id = ?").get(other.id)).toBeDefined();
    expect(db.prepare("SELECT COUNT(*) AS n FROM achievements WHERE user_id = ?").get(other.id)).toEqual({ n: 1 });
    const again = await fetch(`${base}/api/auth/account`, { method: "DELETE", headers: { Authorization: `Bearer ${victim.token}` } });
    expect(again.status).toBe(404);
  });
});
