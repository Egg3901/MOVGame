#!/usr/bin/env node
// Build the web source from the same checkout as the native clients.
import { spawnSync } from "node:child_process";
import { cpSync, rmSync } from "node:fs";
import { dirname, resolve, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const web = resolve(root, "../..");
const channel = process.argv[2] ?? "desktop-direct";
if (!["web", "desktop-direct", "steam"].includes(channel)) {
  throw new Error(`Unknown channel: ${channel}`);
}
if (process.argv.slice(3).includes("--no-build")) {
  console.log("Desktop uses web source from the current checkout");
} else {
  const args = channel === "web" ? ["run", "build"] : ["run", "build", "--", "--mode", channel];
  const result = spawnSync("npm", args, {
    cwd: web, stdio: "inherit", shell: process.platform === "win32",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
  const dist = join(root, "dist");
  rmSync(dist, { recursive: true, force: true });
  cpSync(join(web, "dist"), dist, { recursive: true });
  console.log(`Web bundle (${channel}) copied to native desktop dist`);
}
