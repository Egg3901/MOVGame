// Build-time content export: serializes the web game's src/content tables to
// JSON bundles consumed by the KMP shared module (Phase 2, Option A).
//
// Usage (needs the web checkout + its tsx):
//   WEB_REPO=/root/projects/ahd-sim ./node_modules/.bin/tsx scripts/export-content-bundles.ts
//
// Reads web.pin for the source-of-truth revision and writes bundles plus a
// manifest into shared/src/commonMain/resources/bundles/. Functions (AI
// predicates, government text) and map SVG paths do not serialize and stay
// hand-ported in shared content/ (see UkContent.kt).
import * as fs from "node:fs";
import * as path from "node:path";
import { execSync } from "node:child_process";

const WEB_REPO = process.env.WEB_REPO ?? "/root/projects/ahd-sim";
const HERE = path.dirname(new URL(import.meta.url).pathname);
const OUT = path.resolve(HERE, "../shared/src/commonMain/resources/bundles");

async function main() {
  const { BLOCS } = await import(`${WEB_REPO}/src/content/blocs.ts`);
  const { CANDIDATES, OPPONENT_OF } = await import(`${WEB_REPO}/src/content/candidates.ts`);
  const { STAFF_POOL } = await import(`${WEB_REPO}/src/content/staff.ts`);
  const { STATE_SEEDS, TOTAL_EV } = await import(`${WEB_REPO}/src/content/states.ts`);
  const { ISSUES } = await import(`${WEB_REPO}/src/content/issues.ts`);
  const { RUNNING_MATES } = await import(`${WEB_REPO}/src/content/runningMates.ts`);
  const { SCENARIOS, SCENARIO_IDS } = await import(`${WEB_REPO}/src/content/scenarios.ts`);
  const ev = await import(`${WEB_REPO}/src/content/events.ts`);
  const { ENDORSEMENT_EVENTS } = await import(`${WEB_REPO}/src/content/endorsements.ts`);
  const hist = await import(`${WEB_REPO}/src/content/historicalEvents.ts`);
  const ukParties = await import(`${WEB_REPO}/src/content/uk/parties.ts`);
  const ukRegions = await import(`${WEB_REPO}/src/content/uk/regions.ts`);
  const ukBlocs = await import(`${WEB_REPO}/src/content/uk/blocs.ts`);
  const ukIssues = await import(`${WEB_REPO}/src/content/uk/issues.ts`);
  const ukLeaders = await import(`${WEB_REPO}/src/content/uk/leaders.ts`);
  const ukEvents = await import(`${WEB_REPO}/src/content/uk/events.ts`);
  const ukElectionEvents = await import(`${WEB_REPO}/src/content/uk/electionEvents.ts`);
  const ukElections = await import(`${WEB_REPO}/src/content/uk/elections.ts`);
  const au = await import(`${WEB_REPO}/src/content/countries/australia.ts`);
  const ca = await import(`${WEB_REPO}/src/content/countries/canada.ts`);
  const fr = await import(`${WEB_REPO}/src/content/countries/france.ts`);
  const de = await import(`${WEB_REPO}/src/content/countries/germany.ts`);

  const stripBundle = (b: any) => {
    const { compatible, governmentText, map, ...rest } = b;
    return rest;
  };

  const bundles: Record<string, unknown> = {
    "us-blocs": { blocs: Object.values(BLOCS) },
    "us-candidates": { candidates: CANDIDATES, opponentOf: OPPONENT_OF },
    "us-staff": { pool: STAFF_POOL },
    "us-events": {
      events: ev.EVENTS,
      endorsements: ENDORSEMENT_EVENTS,
      debates: hist.GENERIC_DEBATES,
    },
    "us-setup": {
      seeds: STATE_SEEDS,
      totalEv: TOTAL_EV,
      issues: Object.values(ISSUES),
      mates: RUNNING_MATES,
      scenarios: Object.values(SCENARIOS),
      ids: SCENARIO_IDS,
    },
    "uk": {
      abstaining: ukParties.UK_ABSTAINING,
      system: ukParties.UK_SYSTEM,
      regions: ukRegions.UK_REGIONS,
      blocs: ukBlocs.UK_BLOCS,
      issues: ukIssues.UK_ISSUES,
      leaders: ukLeaders.UK_LEADERS,
      events: ukEvents.UK_EVENTS,
      eventChance: ukEvents.UK_EVENT_CHANCE,
      electionEvents: ukElectionEvents.UK_ELECTION_EVENTS,
      pools: ukElections.UK_BOUNDARY_POOLS,
      majorities: ukElections.UK_ELECTION_MAJORITY,
      elections: ukElections.UK_ELECTIONS,
      ids: ukElections.UK_ELECTION_IDS,
    },
    "countries/au": stripBundle(au.AUSTRALIA),
    "countries/ca": stripBundle(ca.CANADA),
    "countries/fr": stripBundle(fr.FRANCE),
    "countries/de": stripBundle(de.GERMANY),
  };
  // Historical events live in their own module, not the events index.
  (bundles["us-events"] as any).historical = {
    "2024": hist.HIST_2024, "2020": hist.HIST_2020, "2016": hist.HIST_2016,
    "2012": hist.HIST_2012, "2008": hist.HIST_2008, "2004": hist.HIST_2004,
    "2000": hist.HIST_2000, "1996": hist.HIST_1996, "1992": hist.HIST_1992,
    "1988": hist.HIST_1988, "1984": hist.HIST_1984, "1980": hist.HIST_1980,
    "1976": hist.HIST_1976, "1972": hist.HIST_1972, "1968": hist.HIST_1968,
    "1964": hist.HIST_1964, "1960": hist.HIST_1960,
  };

  fs.mkdirSync(path.join(OUT, "countries"), { recursive: true });
  let webPin = "unknown";
  try {
    webPin = fs.readFileSync(path.resolve(HERE, "../web.pin"), "utf8").trim();
  } catch { /* no pin file */ }
  let webHead = "unknown";
  try {
    webHead = execSync("git rev-parse --short HEAD", { cwd: WEB_REPO }).toString().trim();
  } catch { /* not a checkout */ }
  const manifest: Record<string, { bytes: number; events?: number }> = {};
  for (const [name, data] of Object.entries(bundles)) {
    const text = JSON.stringify(data);
    fs.writeFileSync(path.join(OUT, `${name}.json`), text);
    manifest[name] = { bytes: text.length };
  }
  fs.writeFileSync(
    path.join(OUT, "manifest.json"),
    JSON.stringify(
      { webPin, webHead, exportedAt: new Date().toISOString(), bundles: manifest },
      null,
      2,
    ),
  );
  console.log("wrote " + Object.keys(bundles).length + " bundles to " + OUT);
}

main();
