#!/usr/bin/env node
// Build the exact MOVGame revision in web.pin for a desktop distribution channel.
// Node is used here because Tauri runs beforeBuildCommand on Windows as well.
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, cpSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const web = join(root, "web");
const dist = join(root, "dist");
const channel = process.argv[2] ?? "desktop-direct";
const noBuild = process.argv.slice(3).includes("--no-build");
const channels = new Set(["web", "desktop-direct", "steam"]);
if (!channels.has(channel)) throw new Error(`Unknown channel: ${channel}`);

const pin = readFileSync(join(root, "web.pin"), "utf8").trim();
if (!/^[a-f0-9]{40}$/.test(pin)) throw new Error("web.pin must contain one full commit SHA");

let askpassDir;
const gitEnv = { ...process.env, GIT_TERMINAL_PROMPT: "0" };
const gitPrefix = [];
if (process.env.MOVGAME_READ_TOKEN) {
  askpassDir = mkdtempSync(join(tmpdir(), "movgame-askpass-"));
  const askpass = join(askpassDir, "askpass.mjs");
  writeFileSync(askpass, `const prompt = process.argv.slice(2).join(" ");\nprocess.stdout.write(prompt.includes("Username") ? "x-access-token\\n" : process.env.MOVGAME_READ_TOKEN + "\\n");\n`);
  const launcher = process.platform === "win32" ? join(askpassDir, "askpass.cmd") : join(askpassDir, "askpass.sh");
  if (process.platform === "win32") {
    writeFileSync(launcher, `@echo off\r\nnode "%~dp0askpass.mjs" %*\r\n`);
  } else {
    writeFileSync(launcher, `#!/bin/sh\nexec node "${askpass}" "$@"\n`, { mode: 0o700 });
  }
  gitEnv.GIT_ASKPASS = launcher;
  gitPrefix.push("-c", "credential.helper=");
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? root,
    env: command === "git" ? gitEnv : process.env,
    stdio: options.capture ? ["ignore", "pipe", "inherit"] : "inherit",
    encoding: "utf8",
    shell: process.platform === "win32" && command === "npm",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} failed (${result.status})`);
  return options.capture ? result.stdout.trim() : undefined;
}

try {
  if (!existsSync(join(web, ".git"))) {
    run("git", [...gitPrefix, "clone", "--quiet", "https://github.com/Egg3901/MOVGame.git", web]);
  }
  run("git", [...gitPrefix, "-C", web, "fetch", "--quiet", "origin"]);
  run("git", [...gitPrefix, "-C", web, "checkout", "--quiet", "--detach", pin]);
  const head = run("git", [...gitPrefix, "-C", web, "rev-parse", "HEAD"], { capture: true });
  if (head !== pin) throw new Error(`Fetched web commit ${head} differs from web.pin ${pin}`);

  // npm ci also refreshes dependencies when a reviewed pin changes the lockfile.
  run("npm", ["ci"], { cwd: web });
  if (noBuild) {
    process.stdout.write(`Web source ${pin} ready (no build)\n`);
  } else {
    run("npm", channel === "web" ? ["run", "build"] : ["run", "build", "--", "--mode", channel], { cwd: web });
    rmSync(dist, { recursive: true, force: true });
    cpSync(join(web, "dist"), dist, { recursive: true });
    process.stdout.write(`Web bundle ${pin} (${channel}) -> ${dist}\n`);
  }
} finally {
  if (askpassDir) rmSync(askpassDir, { recursive: true, force: true });
}
