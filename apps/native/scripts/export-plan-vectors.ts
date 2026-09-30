// Regenerate with: npx tsx apps/native/scripts/export-plan-vectors.ts
// Expectations come from the web engines, independently of the Kotlin port.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { COUNTRIES } from "../../../src/content/countries/index";
import { UK_ELECTIONS } from "../../../src/content/uk/elections";
import { createUkGame, ukAdvanceTurn, projectUkPreview, playablePartiesIn as ukParties } from "../../../src/engine/ukGame";
import { createCountryGame, countryAdvanceTurn, projectCountryPreview, playablePartiesIn, type CountryAction } from "../../../src/engine/countryGame";
import { createGame } from "../../../src/engine/setup";
import { advanceTurn } from "../../../src/engine/turn";
import { applyAction } from "../../../src/engine/actions";
import { createRng } from "../../../src/engine/rng";
import { projectElection } from "../../../src/engine/voteModel";
import { orderedPlan, planBonusMultiplier, planBonusesForAction } from "../../../src/engine/planBonuses";
import type { CampaignAction } from "../../../src/engine/types";

const vectors: unknown[] = [];
for (const countryId of ["UK", ...Object.keys(COUNTRIES)]) {
  const country = COUNTRIES[countryId];
  const elections = countryId === "UK" ? UK_ELECTIONS : country.elections;
  for (const electionId of Object.keys(elections)) {
    const party = (countryId === "UK" ? ukParties(electionId) : playablePartiesIn(country, electionId))[0];
    for (const failedSetup of [false, true]) {
      const options = { election: electionId, playerParty: party, seed: 4242, difficulty: "normal" as const };
      const game = countryId === "UK" ? createUkGame(options) : createCountryGame(country, options);
      const target = game.regions.find(r => r.baselineShare?.[party] !== undefined)!.id;
      const issue = Object.keys(game.salience)[0];
      game.resources[party].actions = 12;
      game.resources[party].funds = failedSetup ? 0 : 100;
      const plan: CountryAction[] = failedSetup ? [
        {type: "gotv", party, regionId: target, day: 3},
        {type: "fundraise", party, day: 2},
        {type: "ground_game", party, regionId: target, day: 1},
      ] : [
        {type: "broadcast", party, regionId: target, mode: "contrast", spend: 1.5, day: 6},
        {type: "broadcast", party, regionId: target, mode: "issue", issueId: issue, spend: 1.5, day: 5},
        {type: "gotv", party, regionId: target, day: 4},
        {type: "broadcast", party, regionId: target, mode: "positive", spend: 1.5, day: 3},
        {type: "oppo_research", party, regionId: target, day: 2},
        {type: "policy_prep", party, day: 2},
        {type: "ground_game", party, regionId: target, day: 1},
        {type: "rally", party, regionId: target, day: 1},
      ];
      // The two structural action contracts differ only in their issue enums.
      game.queuedActions = plan as typeof game.queuedActions;
      const preview = countryId === "UK" ? projectUkPreview(game as ReturnType<typeof createUkGame>)
        : projectCountryPreview(game as ReturnType<typeof createCountryGame>, country);
      const next = countryId === "UK" ? ukAdvanceTurn(game as ReturnType<typeof createUkGame>, {disableAi: true})
        : countryAdvanceTurn(game as ReturnType<typeof createCountryGame>, country, {disableAi: true});
      vectors.push({countryId, electionId, party, target, issue, failedSetup, plan,
        previewSeats: preview.seats, previewVote: preview.voteShare,
        seats: next.regions.length && (countryId === "UK" ? projectUkPreview(next as ReturnType<typeof createUkGame>)
          : projectCountryPreview(next as ReturnType<typeof createCountryGame>, country)).seats,
        rng: next.rngState, funds: next.resources[party].funds, salience: next.salience[issue],
        bonuses: next.causes.filter(c => c.cause.startsWith("Plan bonus:")).map(c => c.cause)});
    }
  }
}
const destination = new URL("../shared/src/commonTest/kotlin/com/lakesidegames/electioneer/engine/PlanWebVectors.kt", import.meta.url);
const usVectors: unknown[] = [];
for (const player of ["dem", "rep"] as const) {
  for (const kind of ["bonuses", "undated", "failed_setup"]) {
    const game = createGame({seed: 4242, scenario: "2024", playerCandidate: player, difficulty: "normal"});
    game.resources[player].actions = 12;
    game.resources[player].cash = kind === "failed_setup" ? 0 : 100_000_000;
    const plan: CampaignAction[] = kind === "failed_setup" ? [
      {type: "gotv", candidate: player, stateId: "PA", day: 3},
      {type: "fundraise", candidate: player, day: 2},
      {type: "ground_game", candidate: player, stateId: "PA", day: 1},
    ] : [
      {type: "advertise", candidate: player, stateId: "PA", adMode: "contrast", spend: 1_500_000, day: 6},
      {type: "advertise", candidate: player, stateId: "PA", adMode: "issue", issueId: "economy", spend: 1_500_000, day: 5},
      {type: "gotv", candidate: player, stateId: "PA", day: 4},
      {type: "advertise", candidate: player, stateId: "PA", adMode: "positive", spend: 1_500_000, day: 3},
      {type: "oppo_research", candidate: player, stateId: "PA", day: 2},
      {type: "policy_prep", candidate: player, day: 2},
      {type: "ground_game", candidate: player, stateId: "PA", day: 1},
      {type: "rally", candidate: player, stateId: "PA", day: 1},
    ];
    if (kind === "undated") plan.forEach(a => delete a.day);
    game.queuedActions = plan;
    const clone = structuredClone(game);
    const rng = createRng(`preview:${game.seed}:${game.turn}`);
    const completed: CampaignAction[] = [];
    for (const action of orderedPlan(plan)) {
      const bonuses = planBonusesForAction(action, completed);
      const before = clone.causes.length;
      applyAction(clone, action, rng, planBonusMultiplier(bonuses), bonuses);
      if (clone.causes.length > before) completed.push(action);
    }
    const preview = projectElection(clone);
    const next = advanceTurn(game, plan, "web-plan-vector");
    usVectors.push({player, kind, plan, preview,
      rng: next.rngState, cash: next.resources[player].cash, salience: next.salience.economy,
      paMargin: next.states.find(s => s.id === "PA")!.blocs.map(b => b.campaignMargin),
      bonuses: next.causes.filter(c => c.cause.startsWith("Plan bonus:")).map(c => c.cause), recap: next.lastRecap});
  }
}
const list = (name: string, cases: unknown[]) => `internal val ${name} = listOf(\n${cases.map(v => `    """${JSON.stringify(v)}"""`).join(",\n")}\n).joinToString(",", "[", "]")\n`;
const output = `// Generated by apps/native/scripts/export-plan-vectors.ts. Do not edit.\npackage com.lakesidegames.electioneer.engine\n\n${list("PLAN_WEB_VECTORS", vectors)}\n${list("US_PLAN_WEB_VECTORS", usVectors)}`;
if (process.argv.includes("--check")) {
  if (readFileSync(fileURLToPath(destination), "utf8") !== output) {
    throw new Error("Native plan vectors differ from the web engines. Regenerate and check the Kotlin results.");
  }
} else {
  writeFileSync(fileURLToPath(destination), output);
}
console.log(`Verified ${vectors.length + usVectors.length} web plan vectors.`);
