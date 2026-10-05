// Purchasable scenario bundles, one per country plus the Complete Collection.
// Price in USD cents. The server seeds
// activation codes per pack; redeeming a pack code unlocks every scenario in
// it. This file is shared by the client (labels) and the server (entitlement).

export interface ScenarioPack {
  id: string;
  name: string;
  description: string;
  price: number; // USD cents. Offline fallback only; the canonical price is the
                 // platform catalog (GET /api/catalog -> /account/api/catalog).
  scenarios: string[];
  // Retired from sale but still honoured for players who already own it.
  // Hidden from every storefront; entitlement checks still expand it.
  legacy?: boolean;
}

export const US_PAID = [
  "us-2016", "us-2012", "us-2008", "us-2004", "us-2000",
  "us-1996", "us-1992", "us-1988", "us-1984", "us-1980",
  "us-1976", "us-1972", "us-1968", "us-1964", "us-1960",
];
export const UK_ALL = [
  "uk-2024", "uk-2019", "uk-2017", "uk-2015", "uk-2010", "uk-2005", "uk-2001",
  "uk-1997", "uk-1992", "uk-1987", "uk-1983", "uk-1979",
  "uk-1974oct", "uk-1974feb", "uk-1970", "uk-1966", "uk-1964", "uk-1951",
];
export const CA_ALL = ["ca-2025", "ca-2021", "ca-2019", "ca-2015"];
export const DE_ALL = ["de-2025", "de-2021", "de-2017"];
export const FR_ALL = ["fr-2027", "fr-2022", "fr-2017", "fr-2012"];
export const AU_ALL = ["au-2025", "au-2022", "au-2019"];
export const GLOBAL_ALL = [...CA_ALL, ...DE_ALL, ...FR_ALL, ...AU_ALL];

export const ALL_PAID = [...US_PAID, ...UK_ALL, ...GLOBAL_ALL];

export const PACKS: ScenarioPack[] = [
  {
    id: "us-historical",
    name: "United States",
    description: "Fifteen presidential races from 1960 to 2016: the Kennedy debates, the Daisy ad, Watergate, the Reagan Revolution, the Florida recount, the Rust Belt upset.",
    price: 99,
    scenarios: US_PAID,
  },
  {
    id: "uk-elections",
    name: "United Kingdom",
    description: "Eighteen general elections, from Churchill's 1951 comeback to Starmer's 2024 landslide. Multiparty first past the post, hung parliaments and all.",
    price: 99,
    scenarios: UK_ALL,
  },
  {
    id: "canada",
    name: "Canada",
    description: "Four federal elections from 2015 to 2025: Sunny ways, two minorities, and the tariff-war comeback across 343 ridings.",
    price: 99,
    scenarios: CA_ALL,
  },
  {
    id: "germany",
    name: "Germany",
    description: "Three Bundestag elections from 2017 to 2025. Mixed-member proportional, five-plus parties, and coalition math after the count.",
    price: 99,
    scenarios: DE_ALL,
  },
  {
    id: "france",
    name: "France",
    description: "Four presidential elections from 2012 to 2027. Two rounds, a crowded first ballot, and a runoff decided by who the losers back.",
    price: 99,
    scenarios: FR_ALL,
  },
  {
    id: "australia",
    name: "Australia",
    description: "Three federal elections from 2019 to 2025. Preferential voting across 150 seats, teals, and the unlosable election.",
    price: 99,
    scenarios: AU_ALL,
  },
  {
    id: "complete",
    name: "Complete Collection",
    description: "Every country and every scenario in the game, plus any scenarios added later.",
    price: 399,
    scenarios: ALL_PAID,
  },
  {
    id: "global",
    name: "Global Elections Pack",
    description: "Canada, Germany, France, and Australia. No longer sold; existing owners keep every scenario in it.",
    price: 599,
    scenarios: GLOBAL_ALL,
    legacy: true,
  },
];

/** Packs offered for sale, in display order. Legacy packs stay entitlement-only. */
export const STORE_PACKS: ScenarioPack[] = PACKS.filter((p) => !p.legacy);

export const PACKS_BY_ID: Record<string, ScenarioPack> = Object.fromEntries(PACKS.map((p) => [p.id, p]));

/** The cheapest pack on sale that unlocks a scenario (for paywall prompts). */
export function packForScenario(scenarioId: string): ScenarioPack | undefined {
  return STORE_PACKS
    .filter((p) => p.scenarios.includes(scenarioId))
    .sort((a, b) => a.price - b.price)[0];
}

/** Whether owning `ownedPackIds` already covers every scenario in `packId`. */
export function packCovered(packId: string, ownedPackIds: readonly string[]): boolean {
  const target = PACKS_BY_ID[packId];
  if (!target) return false;
  if (ownedPackIds.includes(packId)) return true;
  const owned = new Set(ownedPackIds.flatMap((id) => PACKS_BY_ID[id]?.scenarios ?? []));
  return target.scenarios.every((s) => owned.has(s));
}
