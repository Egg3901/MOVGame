// Build-time content export: serializes the web game's src/content tables to
// JSON bundles consumed by the KMP shared module (Phase 2, Option A).
//
// Usage from the repository root: npm run native:content
//
// Reads web content from this checkout and writes bundles plus a
// manifest into shared/src/commonMain/resources/bundles/. Functions (AI
// predicates, government text) and map SVG paths do not serialize and stay
// hand-ported in shared content/ (see UkContent.kt).
import * as fs from "node:fs";
import * as path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB_REPO = path.resolve(HERE, "../../..");
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
  const { SCENARIO_REGISTRY } = await import(`${WEB_REPO}/src/content/scenarioRegistry.ts`);
  const { GRID, GRID_ROWS, GRID_COLS, SPLIT_UNITS } = await import(`${WEB_REPO}/src/content/mapLayout.ts`);
  const { UK_VIEWBOX, REGION_PATHS } = await import(`${WEB_REPO}/src/content/uk/regionPaths.ts`);

  const stripBundle = (b: any) => {
    const { compatible, governmentText, map, ...rest } = b;
    return rest;
  };

  const usTiles = [...GRID, ...SPLIT_UNITS.flatMap((group, row) => group.ids.map((id, col) => ({ id, row: GRID_ROWS + row, col })))];
  const usMap = { viewBox: `0 0 ${GRID_COLS * 40} ${(GRID_ROWS + SPLIT_UNITS.length) * 40}`,
    shapes: Object.fromEntries(usTiles.map(({ id, row, col }) => {
      const x = col * 40 + 2, y = row * 40 + 2;
      return [id, { d: `M${x} ${y} L${x + 36} ${y} L${x + 36} ${y + 36} L${x} ${y + 36} Z` }];
    })) };

  const bundles: Record<string, unknown> = {
    "scenario-registry": SCENARIO_REGISTRY,
    "maps": { US: usMap, UK: { viewBox: UK_VIEWBOX, shapes: REGION_PATHS },
      CA: ca.CANADA.map, DE: de.GERMANY.map, FR: fr.FRANCE.map, AU: au.AUSTRALIA.map },
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

  const check = process.argv.includes("--check");
  if (!check) fs.mkdirSync(path.join(OUT, "countries"), { recursive: true });
  const webHead = execFileSync("git", ["rev-parse", "HEAD"], { cwd: WEB_REPO, encoding: "utf8" }).trim();
  const manifest: Record<string, { bytes: number; events?: number }> = {};
  for (const [name, data] of Object.entries(bundles)) {
    const text = JSON.stringify(data);
    const target = path.join(OUT, `${name}.json`);
    if (check) {
      if (fs.readFileSync(target, "utf8") !== text) throw new Error(`Stale native bundle: ${name}; run npm run native:content`);
    } else {
      fs.writeFileSync(target, text);
    }
    manifest[name] = { bytes: text.length };
  }
  if (!check) fs.writeFileSync(
    path.join(OUT, "manifest.json"),
    JSON.stringify(
      { webHead, exportedAt: new Date().toISOString(), bundles: manifest },
      null,
      2,
    ),
  );
  console.log((check ? "verified " : "wrote ") + Object.keys(bundles).length + " bundles to " + OUT);
}

main();
