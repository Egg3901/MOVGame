// ─────────────────────────────────────────────────────────────────────────
// SCENARIO REGISTRY — one row per playable election across every country.
// The registry is metadata only (free/paid, pack, difficulty, routing); the
// scenario *content* stays where each engine path already keeps it:
//   - US:  content/scenarios.ts        (nativeId = "2024", "2020", …)
//   - UK:  content/uk/elections.ts     (nativeId = "2024", "1997", …)
//   - new: content/countries/*.ts      (nativeId = election id in the bundle)
// Shared by the client (picker, locks) and the server (entitlement).
// ─────────────────────────────────────────────────────────────────────────

export type CountryCode = "US" | "UK" | "CA" | "DE" | "FR" | "AU";
export type EnginePath = "us" | "uk" | "country";

export interface ScenarioMeta {
  scenarioId: string;      // global id: "us-2024", "uk-1997", "ca-2025"
  country: CountryCode;
  engine: EnginePath;      // which game shell runs it
  nativeId: string;        // the id the engine path uses internally
  year: number;
  free: boolean;
  packId?: string;
  // How hard it is to rewrite this election's outcome — i.e. to flip history
  // playing the historical underdog, on normal difficulty. Data-driven, from
  // the balance gauntlet's underdog-focused win rate (docs/balance/): genuine
  // toss-ups read "easy" (winnable from either side), decisive landslides read
  // "hard" (you're fighting the tide). Regenerate with scripts/balance-report.
  difficulty: "easy" | "medium" | "hard";
  label: string;
  description: string;
  flag: string;
  // Optional player-facing tags for setup / landing.
  // "tide" = historical landslide (flip-history fantasy is a challenge).
  // "challenge" = minor-party / kingmaker path, not tuned for majority wins.
  tags?: Array<"tide" | "challenge" | "coin-flip">;
}

/** Landslide / known-unwinnable years — surface a "historical tide" warning. */
export const TIDE_SCENARIO_IDS = new Set([
  "us-1984", "us-1972", "us-1964", "us-1936",
  "uk-1983", "uk-1997", "uk-2001", "uk-2024",
  "fr-2017",
]);

/** Genuine coin-flip years where the baseline winner is not a pushover. */
export const COIN_FLIP_SCENARIO_IDS = new Set(["uk-2017", "us-2000", "us-1960", "us-1968"]);

export function scenarioTags(meta: ScenarioMeta): Array<"tide" | "challenge" | "coin-flip"> {
  if (meta.tags) return meta.tags;
  const tags: Array<"tide" | "challenge" | "coin-flip"> = [];
  if (TIDE_SCENARIO_IDS.has(meta.scenarioId) || meta.difficulty === "hard") {
    // Only stamp "tide" on the most extreme landslides, not every hard year.
    if (TIDE_SCENARIO_IDS.has(meta.scenarioId)) tags.push("tide");
  }
  if (COIN_FLIP_SCENARIO_IDS.has(meta.scenarioId)) tags.push("coin-flip");
  return tags;
}

export function tideBlurb(meta: ScenarioMeta): string | null {
  const tags = scenarioTags(meta);
  if (tags.includes("tide")) {
    return "Historical tide: flipping this election is a challenge even on Easy. Fight the map, or enjoy the ride.";
  }
  if (tags.includes("coin-flip")) {
    return "Coin-flip election. The baseline is a near-tie and either side can win.";
  }
  return null;
}

const us = (year: number, label: string, description: string, free: boolean, difficulty: ScenarioMeta["difficulty"]): ScenarioMeta => ({
  scenarioId: `us-${year}`, country: "US", engine: "us", nativeId: String(year), year,
  free, packId: free ? undefined : "us-historical", difficulty, label, description, flag: "🇺🇸",
});

const uk = (year: number, label: string, description: string, difficulty: ScenarioMeta["difficulty"]): ScenarioMeta => ({
  scenarioId: `uk-${year}`, country: "UK", engine: "uk", nativeId: String(year), year,
  free: false, packId: "uk-elections", difficulty, label, description, flag: "🇬🇧",
});

export const SCENARIO_REGISTRY: ScenarioMeta[] = [
  // ── United States (presidential) ──
  us(2024, "2024 · Harris v. Trump", "A late switch at the top of the ticket, the Sun Belt and the Blue Wall both in play.", true, "hard"),
  us(2020, "2020 · Biden v. Trump", "A pandemic on the ballot and a blue wall to rebuild. The original campaign.", true, "easy"),
  us(2016, "2016 · Clinton v. Trump", "The map looks settled and the blue wall looks safe. Don't be the one who let it crack.", false, "easy"),
  us(2012, "2012 · Obama v. Romney", "A slow recovery, a re-election fight, and a turnout war in the swing states.", false, "easy"),
  us(2008, "2008 · Obama v. McCain", "An open seat, two wars, and a financial system in free fall.", false, "hard"),
  us(2004, "2004 · Bush v. Kerry", "A wartime incumbent, a decorated challenger. It runs through Ohio.", false, "hard"),
  us(2000, "2000 · Gore v. Bush", "A knife's-edge electorate, and a recount waiting to happen.", false, "easy"),
  us(1996, "1996 · Clinton v. Dole", "A booming economy, a triangulating incumbent, and the last campaign of a war generation.", false, "hard"),
  us(1992, "1992 · Clinton v. Bush", "It's the economy, stupid. A broken tax pledge, a billionaire wildcard, and the Man from Hope.", false, "hard"),
  us(1988, "1988 · Dukakis v. Bush", "A 17-point summer lead, a tank ride, and the meanest ad war of its era.", false, "hard"),
  us(1984, "1984 · Mondale v. Reagan", "Morning in America. Fight the tide, or ride the biggest landslide of the age.", false, "hard"),
  us(1980, "1980 · Carter v. Reagan", "Hostages in Tehran, stagflation at home, and one debate to decide it all.", false, "hard"),
  us(1976, "1976 · Carter v. Ford", "A pardon in the air, a peanut farmer against Washington, and a fragile Solid South.", false, "hard"),
  us(1972, "1972 · McGovern v. Nixon", "A prairie liberal against a 520-EV landslide, and a break-in nobody is talking about yet.", false, "hard"),
  us(1968, "1968 · Humphrey v. Nixon", "A party split three ways, a burning summer, and Wallace holding the Deep South.", false, "hard"),
  us(1964, "1964 · Johnson v. Goldwater", "A martyred president's heir, a nuclear ad, and the birth of the Sun Belt right.", false, "hard"),
  us(1960, "1960 · Kennedy v. Nixon", "Television changes everything, and a hundred thousand votes decide the century's closest race.", false, "easy"),

  // ── United Kingdom (general elections) ──
  uk(2024, "2024 · Starmer's landslide", "Fourteen years of Conservative rule end. Or do they? Reform surges on the right.", "hard"),
  uk(2019, "2019 · Get Brexit Done", "Johnson's gamble: smash the Red Wall or lose the majority.", "hard"),
  uk(2017, "2017 · May's gamble", "A snap election, a collapsing lead, and a hung parliament in waiting.", "easy"),
  uk(2015, "2015 · Cameron v. Miliband", "Coalition's end, the SNP tide in Scotland, and a polling shock.", "hard"),
  uk(2010, "2010 · The TV-debate election", "Expenses, Cleggmania, and the first hung parliament in a generation.", "easy"),
  uk(2005, "2005 · Blair's third act", "Iraq shadows a tired landslide. How much of the majority survives?", "hard"),
  uk(2001, "2001 · The quiet landslide", "New Labour ascendant; the Tories search for a pulse.", "hard"),
  uk(1997, "1997 · Things Can Only Get Better", "Blair's New Labour against a Major government running on fumes.", "hard"),
  uk(1992, "1992 · Major's surprise", "The polls say Kinnock; the ballot boxes disagree. Defy the polls.", "hard"),
  uk(1987, "1987 · Thatcher's third", "The Iron Lady seeks a third term against a divided opposition.", "hard"),
  uk(1983, "1983 · The Falklands election", "Thatcher rampant, Labour's longest suicide note, the Alliance splitting the left.", "hard"),
  uk(1979, "1979 · Winter of Discontent", "Callaghan limps to the polls; Thatcher offers a revolution.", "hard"),
  {
    scenarioId: "uk-1974feb", country: "UK", engine: "uk", nativeId: "1974feb", year: 1974,
    free: false, packId: "uk-elections", difficulty: "easy", flag: "🇬🇧",
    label: "1974 (Feb) · Who governs Britain?",
    description: "The miners' strike and the three-day week. Heath asks who governs, and the answer is a hung parliament.",
  },
  {
    scenarioId: "uk-1974oct", country: "UK", engine: "uk", nativeId: "1974oct", year: 1974,
    free: false, packId: "uk-elections", difficulty: "medium", flag: "🇬🇧",
    label: "1974 (Oct) · Wilson's slim majority",
    description: "Eight months of minority rule, then back to the country for a working majority of three.",
  },
  uk(1970, "1970 · Heath's upset", "Wilson is cruising, the polls are wrong, and Heath wins the election nobody expected.", "medium"),
  uk(1966, "1966 · Wilson's landslide", "A majority of four becomes a mandate of ninety-eight. The white heat year.", "hard"),
  uk(1964, "1964 · Wilson v. Douglas-Home", "Thirteen years of Tory rule against the Profumo hangover. The narrowest of wins.", "easy"),
  uk(1951, "1951 · Churchill's comeback", "Attlee's exhausted majority against Churchill's last campaign.", "hard"),

  // ── New countries ──
  {
    scenarioId: "ca-2025", country: "CA", engine: "country", nativeId: "2025", year: 2025,
    free: false, packId: "canada", difficulty: "easy", flag: "🇨🇦",
    label: "2025 · Carney v. Poilievre",
    description: "A tariff war with the neighbour, a Liberal comeback for the ages, and 343 seats from coast to coast to coast.",
  },
  {
    scenarioId: "ca-2021", country: "CA", engine: "country", nativeId: "2021", year: 2021,
    free: false, packId: "canada", difficulty: "easy", flag: "🇨🇦",
    label: "2021 · Trudeau v. O'Toole",
    description: "A pandemic snap election nobody asked for. Gravel on the trail, a fourth wave rising, and 338 seats in play.",
  },
  {
    scenarioId: "ca-2019", country: "CA", engine: "country", nativeId: "2019", year: 2019,
    free: false, packId: "canada", difficulty: "hard", flag: "🇨🇦",
    label: "2019 · Trudeau v. Scheer",
    description: "SNC-Lavalin, the blackface photos, and a Liberal government that loses the popular vote but keeps the keys. A minority, 338 seats.",
  },
  {
    scenarioId: "ca-2015", country: "CA", engine: "country", nativeId: "2015", year: 2015,
    free: false, packId: "canada", difficulty: "hard", flag: "🇨🇦",
    label: "2015 · Trudeau v. Harper",
    description: "A decade of Harper, a 78-day marathon, and a third-place party that wakes up in a majority. Sunny ways over a tired throne.",
  },
  {
    scenarioId: "de-2025", country: "DE", engine: "country", nativeId: "2025", year: 2025,
    free: false, packId: "germany", difficulty: "hard", flag: "🇩🇪",
    label: "2025 · Merz v. Scholz",
    description: "The traffic-light coalition collapses. Five-plus parties, a firewall under strain, and 630 Bundestag seats.",
  },
  {
    scenarioId: "de-2021", country: "DE", engine: "country", nativeId: "2021", year: 2021,
    free: false, packId: "germany", difficulty: "hard", flag: "🇩🇪",
    label: "2021 · Scholz v. Laschet",
    description: "Merkel departs, a flood reshapes the race, and six parties fight over a 735-seat Bundestag.",
  },
  {
    scenarioId: "de-2017", country: "DE", engine: "country", nativeId: "2017", year: 2017,
    free: false, packId: "germany", difficulty: "hard", flag: "🇩🇪",
    label: "2017 · Merkel v. Schulz",
    description: "The Schulz train runs out of track and the AfD storms the Bundestag. Merkel's fourth term, a re-entering FDP, and 709 seats.",
  },
  {
    scenarioId: "fr-2027", country: "FR", engine: "country", nativeId: "2027", year: 2027,
    free: false, packId: "france", difficulty: "hard", flag: "🇫🇷",
    label: "2027 · The Republic's runoff",
    description: "Macron is term-limited. The centre defends the Élysée against the RN in a two-round fight for France.",
  },
  {
    scenarioId: "fr-2022", country: "FR", engine: "country", nativeId: "2022", year: 2022,
    free: false, packId: "france", difficulty: "hard", flag: "🇫🇷",
    label: "2022 · Macron v. Le Pen",
    description: "The rematch. Pouvoir d'achat against the front républicain, and a far narrower runoff than last time.",
  },
  {
    scenarioId: "fr-2017", country: "FR", engine: "country", nativeId: "2017", year: 2017,
    free: false, packId: "france", difficulty: "hard", flag: "🇫🇷",
    label: "2017 · Macron v. Le Pen",
    description: "A 39-year-old insurgent, a movement a year old, and the debate meltdown that sealed a landslide.",
  },
  {
    scenarioId: "fr-2012", country: "FR", engine: "country", nativeId: "2012", year: 2012,
    free: false, packId: "france", difficulty: "hard", flag: "🇫🇷",
    label: "2012 · Hollande v. Sarkozy",
    description: "A crisis president nobody warmed to against the man who promised to be normal. A left-right runoff decided by a whisker.",
  },
  {
    scenarioId: "au-2025", country: "AU", engine: "country", nativeId: "2025", year: 2025,
    free: false, packId: "australia", difficulty: "hard", flag: "🇦🇺",
    label: "2025 · Albanese v. Dutton",
    description: "Preferential voting, a cyclone-delayed budget, and a Trump-shaped shadow over 150 seats.",
  },
  {
    scenarioId: "au-2022", country: "AU", engine: "country", nativeId: "2022", year: 2022,
    free: false, packId: "australia", difficulty: "medium", flag: "🇦🇺",
    label: "2022 · Albanese v. Morrison",
    description: "Nine years of Coalition rule, a teal wave on the harbourside, and a PM who doesn't hold a hose.",
  },
  {
    scenarioId: "au-2019", country: "AU", engine: "country", nativeId: "2019", year: 2019,
    free: false, packId: "australia", difficulty: "hard", flag: "🇦🇺",
    label: "2019 · Morrison v. Shorten",
    description: "The unlosable election. A big-target Labor agenda, a wall of yellow ads, and a daggy-dad miracle. 151 seats under preferences.",
  },
];

export const SCENARIOS_BY_ID: Record<string, ScenarioMeta> = Object.fromEntries(
  SCENARIO_REGISTRY.map((s) => [s.scenarioId, s]),
);

export const FREE_SCENARIO_IDS = SCENARIO_REGISTRY.filter((s) => s.free).map((s) => s.scenarioId);

// Master paywall switch, shared by the client (locks, copy) and the server
// (entitlement checks). While false, every scenario is playable by everyone;
// the packs/activation machinery stays intact so flipping this back on
// restores the paid tiers without further changes.
//
// ON at release: US 2024, US 2020 and the daily challenge are free; every
// other scenario needs its country bundle or the Complete Collection.
export const PAYWALL_ENABLED = true;

export function isFreeScenario(scenarioId: string): boolean {
  const meta = SCENARIOS_BY_ID[scenarioId];
  if (!meta) return false; // unknown ids stay locked (leaderboard rejects them)
  if (!PAYWALL_ENABLED) return true;
  return meta.free;
}
