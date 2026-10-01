import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { validateCustomScenario, makeDefaultCustomScenario, makeDefaultMultiparty, registerCustomScenario, buildUkCustomGame, buildCountryCustomGame, TRAIT_KEYS, MP_TRAIT_KEYS } from "../../../src/content/customScenario";
import { ISSUE_IDS } from "../../../src/content/issues";
import { BLOC_IDS } from "../../../src/content/blocs";
import { createGame } from "../../../src/engine/setup";
import { beginGame } from "../../../src/engine/turn";
import { advanceCampaignWeek } from "../../../src/engine/campaignWeek";
import { projectElection, computeResult } from "../../../src/engine/voteModel";
import { ukAdvanceTurn, projectUk } from "../../../src/engine/ukGame";
import { countryAdvanceTurn, projectCountry } from "../../../src/engine/countryGame";

const defaults = [makeDefaultCustomScenario(), makeDefaultMultiparty("uk"), ...["CA", "DE", "FR", "AU"].map(id => makeDefaultMultiparty("country", id))];
for (const [i, doc] of defaults.entries()) { doc.id = `custom-editor-${i}`; doc.createdAt = 100; doc.updatedAt = 200; }
const validations: { input: string; result: unknown }[] = [];
function capture(raw: unknown) { validations.push({ input: JSON.stringify(raw), result: validateCustomScenario(raw) }); }
function change(raw: unknown, path: string, value: unknown) {
  const draft = structuredClone(raw) as any;
  const keys = path.split("/");
  let target = draft;
  for (const key of keys.slice(0, -1)) target = target[key];
  if (value === undefined) delete target[keys.at(-1)!]; else target[keys.at(-1)!] = value;
  capture(draft);
}
for (const doc of defaults) {
  capture(doc);
  for (const [path, bad] of [["version", 3], ["engine", "alien"], ["id", "2024"], ["label", " "], ["label", "x".repeat(61)], ["tagline", null], ["tagline", "x".repeat(201)], ["year", 1787], ["year", 2101], ["year", "2024"]] as const) change(doc, path, bad);
}
const us = defaults[0];
for (const side of ["dem", "rep"]) {
  for (const [path, bad] of [["name", ""], ["name", "x".repeat(41)], ["shortName", "x".repeat(17)], ["party", "Independent"], ["color", "#123"], ["traits", []], ["issuePositions", null], ["baseFavorability", []]] as const) change(us, `${side}/${path}`, bad);
  for (const key of TRAIT_KEYS) for (const bad of [-1, 101, "60", undefined]) change(us, `${side}/traits/${key}`, bad);
  for (const key of ISSUE_IDS) for (const bad of [-1.1, 1.1, "0", undefined]) change(us, `${side}/issuePositions/${key}`, bad);
  for (const key of BLOC_IDS) for (const bad of [-2, 2, "0", 0]) change(us, `${side}/baseFavorability/${key}`, bad);
}
change(us, "dem/baseFavorability/unknown", 0.2);
change(us, "eventMode", "random");
change(us, "year", 2024.5);
change(us, "version", 1);
change(us, "engine", undefined);
for (const doc of defaults.slice(1)) {
  for (const [path, bad] of [["mp", null], ["mp/baseElection", "missing"], ["mp/parties", []], ["mp/parties", [doc.mp!.parties[0]]], ["mp/parties/0", null], ["mp/parties/0/partyId", "absent"], ["mp/parties/1/partyId", doc.mp!.parties[0].partyId], ["mp/parties/0/name", "x".repeat(49)], ["mp/parties/0/shortName", "x".repeat(17)], ["mp/parties/0/leaderName", "x".repeat(49)], ["mp/parties/0/color", "#bad"], ["mp/parties/0/baseSupport", 2], ["mp/parties/0/baseSupport", "0"]] as const) change(doc, path, bad);
  for (const key of MP_TRAIT_KEYS) for (const bad of [-1, 101, "60", undefined]) change(doc, `mp/parties/0/${key}`, bad);
  if (doc.engine === "country") change(doc, "mp/countryId", "XX");
}
for (const raw of [null, [], 0, "text"]) capture(raw);
const campaigns = defaults.map(source => {
  const cs = structuredClone(source);
  cs.label = "Edited election"; cs.tagline = "Custom rules";
  if (cs.engine === "us") { cs.dem.name = "Test Candidate"; cs.dem.traits.charisma = 91; cs.rep.traits.energy = 33; cs.dem.baseFavorability[BLOC_IDS[0]] = .25; cs.dem.issuePositions[ISSUE_IDS[0]] = .8; }
  else { const p = cs.mp!.parties[0]; p.name = "Custom Party"; p.shortName = "CUSTOM"; p.color = "#abcdef"; p.leaderName = "Test Leader"; p.charisma = 93; p.energy = 34; p.baseSupport = .25; cs.mp!.parties[1].baseSupport = -.15; }
  const validated = validateCustomScenario(cs);
  if (!validated.ok) throw Error(validated.errors.join("\n"));
  capture(cs);
  const weeks: unknown[] = [];
  if (cs.engine === "us") {
    registerCustomScenario(cs);
    let game = beginGame(createGame({ scenario: cs.id, seed: cs.id, playerCandidate: "dem", difficulty: "normal", eventMode: cs.eventMode, customScenario: JSON.stringify(cs) }));
    function facts() { return { turn: game.turn, rng: game.rngState, units: projectElection(game).ev, popular: computeResult(game).popularShare, leaders: game.candidates, resources: game.resources }; }
    weeks.push(facts());
    while (game.phase !== "result") { game = advanceCampaignWeek(game, "normal"); weeks.push(facts()); }
  } else if (cs.engine === "uk") {
    let game = buildUkCustomGame(cs);
    function facts() { return { turn: game.turn, rng: game.rngState, units: projectUk(game).seats, popular: projectUk(game).voteShare, leaders: game.leaders, resources: game.resources }; }
    weeks.push(facts());
    while (game.phase !== "result") { game = ukAdvanceTurn(game, { autoResolvePlayerEvents: true }); weeks.push(facts()); }
  } else {
    const built = buildCountryCustomGame(cs); let game = built.game;
    function facts() { return { turn: game.turn, rng: game.rngState, units: projectCountry(game, built.country).seats, popular: projectCountry(game, built.country).voteShare, leaders: game.leaders, resources: game.resources }; }
    weeks.push(facts());
    while (game.phase !== "result") { game = countryAdvanceTurn(game, built.country, { autoResolvePlayerEvents: true }); weeks.push(facts()); }
  }
  return { json: JSON.stringify(cs), weeks };
});
const serialized = JSON.stringify({ validations, campaigns });
const chunks = serialized.match(/[\s\S]{1,10000}/gu)!;
const output = '// Generated from the web editor validator and six complete edited campaigns.\npackage com.lakesidegames.electioneer.engine\n\ninternal val EDITOR_WEB_VECTORS: String by lazy {\n    listOf(\n' + chunks.map(s => '        ' + JSON.stringify(s).replace(/\$/g, '\\$') + ',\n').join('') + '    ).joinToString("")\n}\n';
const path = fileURLToPath(new URL('../shared/src/commonTest/kotlin/com/lakesidegames/electioneer/engine/EditorWebVectors.kt', import.meta.url));
if (process.argv.includes('--check')) { if (readFileSync(path, 'utf8') !== output) throw Error('Native editor vectors are stale'); } else writeFileSync(path, output);
console.log(`Verified ${validations.length} web validation cases and ${campaigns.length} edited campaigns.`);
