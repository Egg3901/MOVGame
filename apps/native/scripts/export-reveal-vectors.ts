import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { createGame } from "../../../src/engine/setup";
import { beginGame } from "../../../src/engine/turn";
import { advanceCampaignWeek } from "../../../src/engine/campaignWeek";
import { createUkGame, ukAdvanceTurn } from "../../../src/engine/ukGame";
import { createCountryGame, countryAdvanceTurn } from "../../../src/engine/countryGame";
import { COUNTRIES } from "../../../src/content/countries";
import { usReveal, ukReveal, countryReveal } from "../../../src/ui/electionNight/adapters";
import { buildSchedule } from "../../../src/lib/electionReveal";

const vectors = [];
for (const country of ["US", "UK", "CA", "DE", "FR", "AU"]) {
  const seed = "seat-reveal";
  let snapshot: string;
  let data;
  if (country === "US") {
    let game = beginGame(createGame({ seed, scenario: "2024", playerCandidate: "dem", difficulty: "normal" }));
    for (let i = 0; i < 9; i++) game = advanceCampaignWeek(game, "normal");
    snapshot = JSON.stringify({ version: 1, seed, difficulty: "normal", state: game });
    data = usReveal(game);
  } else if (country === "UK") {
    let game = createUkGame({ seed, election: "2024", playerParty: "lab" });
    for (let i = 0; i < 6; i++) game = ukAdvanceTurn(game, { disableAi: true, autoResolvePlayerEvents: true });
    snapshot = JSON.stringify({ version: 1, uk: game });
    data = ukReveal(game);
  } else {
    const bundle = COUNTRIES[country];
    const election = Object.keys(bundle.elections).at(-1)!;
    const playerParty = bundle.system.parties[0].id;
    let game = createCountryGame(bundle, { seed, election, playerParty });
    for (let i = 0; i < 6; i++) game = countryAdvanceTurn(game, bundle, { disableAi: true, autoResolvePlayerEvents: true });
    snapshot = JSON.stringify({ version: 1, country: game });
    data = countryReveal(bundle, game);
  }
  const { map, ...normalized } = data;
  const schedule = buildSchedule(data.units, data.threshold);
  const allocation: Record<string, number> = {};
  for (const unit of data.units) for (const [party, seats] of Object.entries(unit.allocation ?? { [unit.winnerId]: unit.units })) allocation[party] = (allocation[party] ?? 0) + seats;
  if ((Object.entries(allocation).find(([, seats]) => seats >= data.threshold)?.[0] ?? null) !== schedule.projectedId) throw Error(`False projection: ${country}`);
  vectors.push({ snapshot, data: normalized, schedule });
}
const serialized = JSON.stringify(vectors);
const chunks = serialized.match(/[\s\S]{1,10000}/gu)!;
const text = '// Generated from all six web election-night adapters and schedules.\npackage com.lakesidegames.electioneer.engine\n\ninternal val REVEAL_WEB_VECTORS: String by lazy {\n    listOf(\n' + chunks.map(s => '        ' + JSON.stringify(s).replace(/\$/g, '\\$') + ',\n').join('') + '    ).joinToString("")\n}\n';
const target = fileURLToPath(new URL('../shared/src/commonTest/kotlin/com/lakesidegames/electioneer/engine/RevealWebVectors.kt', import.meta.url));
if (process.argv.includes('--check')) { if (readFileSync(target, 'utf8') !== text) throw Error('Reveal web vectors are stale'); } else writeFileSync(target, text);
console.log(`Verified ${vectors.length} web election-night result allocations and schedules.`);
