// FRANCE — the presidential runoff. The playable campaign is the
// entre-deux-tours (two finalists over a 100-point electoral pool; 51 wins
// the Élysée). Round-1 → round-2 advancement lives in engine/runoff.ts:
// first-round shares + a transfer matrix (front républicain / abstention)
// produce the second-round map. The authored per-région `v` below are the
// real second-round shares (calibration anchors); `FIRST_ROUND` +
// `TRANSFERS` document how those maps arise and are checked in runoff tests.
// Four runoffs ship: speculative 2027 (Philippe–Bardella), real 2022
// (Macron 58.55 / Le Pen 41.45), real 2017 (Macron 66.1 / 33.9), and real
// 2012 (Hollande 51.64 / Sarkozy 48.36). The bundle only carries two party
// slots (ens / rn), so 2012 maps the left finalist Hollande (PS) onto `ens`
// and the incumbent right finalist Sarkozy (UMP) onto `rn`: a left-vs-right
// runoff, not a centre-vs-far-right one. The leaders and labels carry the real
// identities; the far right (Le Pen) is a first-round casualty that turn.

import type { CountryBundle } from "@engine/countryGame";
import { FR_MAP } from "./paths/fr";
import type { Government } from "@engine/types";
import type { TransferMatrix, RoundShares } from "@engine/runoff";

/** National first-round shares (real 2017/2022; polling-informed 2027). */
export const FR_FIRST_ROUND: Record<string, RoundShares> = {
  // 2017 first round (approx.): Macron 24.0, Le Pen 21.3, Fillon 20.0, Mélenchon 19.6, Hamon 6.4, …
  "2017": { ens: 0.240, rn: 0.213, lfi: 0.196, lr: 0.200, other: 0.151 },
  // 2022 first round: Macron 27.9, Le Pen 23.2, Mélenchon 22.0, Zemmour 7.1, Pécresse 4.8, …
  "2022": { ens: 0.279, rn: 0.232, lfi: 0.220, lr: 0.048, other: 0.221 },
  // 2027 speculative: centre and RN clear, left fragmented, right collapsed.
  "2027": { ens: 0.26, rn: 0.25, lfi: 0.18, lr: 0.10, other: 0.21 },
  // 2012 first round: Hollande 28.6, Sarkozy 27.2, Le Pen 17.9, Mélenchon 11.1,
  // Bayrou 9.1. `ens` = Hollande (PS), `rn` = Sarkozy (UMP); here `lr` carries
  // the eliminated Le Pen (FN) vote that breaks to the right finalist.
  "2012": { ens: 0.286, rn: 0.272, lfi: 0.111, lr: 0.179, other: 0.152 },
};

/** Bloc-transfer matrices from eliminated first-round candidates into the runoff. */
export const FR_TRANSFERS: Record<string, TransferMatrix> = {
  // 2017: a firm front républicain — Fillon/Hamon voters break hard for Macron.
  "2017": {
    lfi: { ens: 0.52, rn: 0.12, _abstain: 0.36 },
    lr: { ens: 0.72, rn: 0.18, _abstain: 0.10 },
    other: { ens: 0.58, rn: 0.22, _abstain: 0.20 },
  },
  // 2022: softer transfers — Mélenchon's "troisième tour" frame, a drilled RN.
  "2022": {
    lfi: { ens: 0.42, rn: 0.18, _abstain: 0.40 },
    lr: { ens: 0.55, rn: 0.30, _abstain: 0.15 },
    other: { ens: 0.48, rn: 0.28, _abstain: 0.24 },
  },
  // 2027: front républicain fatigue — transfers still favour the centre, barely.
  "2027": {
    lfi: { ens: 0.35, rn: 0.25, _abstain: 0.40 },
    lr: { ens: 0.45, rn: 0.40, _abstain: 0.15 },
    other: { ens: 0.40, rn: 0.35, _abstain: 0.25 },
  },
  // 2012: the left (Mélenchon) breaks hard for Hollande (`ens`); the FN vote
  // (`lr` slot) breaks to Sarkozy (`rn`); Bayrou's centre (`other`) splits with
  // a slight edge to Hollande. The net leaves Hollande narrowly ahead.
  "2012": {
    lfi: { ens: 0.80, rn: 0.06, _abstain: 0.14 },
    lr: { ens: 0.20, rn: 0.60, _abstain: 0.20 },
    other: { ens: 0.42, rn: 0.38, _abstain: 0.20 },
  },
};

export const FRANCE: CountryBundle = {
  id: "FR",
  label: "France",
  flag: "🇫🇷",
  currency: "€",
  unitName: "point",
  unitNamePlural: "points",
  goalText: "51 of 100 electoral points",
  defaultSeatElasticity: 1.0,
  eventChance: 0.75,
  map: FR_MAP,

  system: {
    id: "FR",
    label: "France",
    parties: [
      { id: "ens", name: "The Centre (Ensemble & allies)", shortName: "Centre", color: "#f2b41c" },
      { id: "rn", name: "Rassemblement National", shortName: "RN", color: "#0f3b8c" },
    ],
    allocation: { id: "regional_seats_curve", label: "Two-round runoff (entre-deux-tours points)", unit: "point" },
    majority: { total: 100, threshold: 51 },
  },
  playable: ["ens", "rn"],
  abstaining: [],
  // A runoff has no coalitions: one candidate wins the presidency outright.
  compatible: () => false,
  governmentText: (g: Government, partyName) => {
    if (g.kind === "majority") return `${partyName(g.party)} wins the presidency with ${g.seats} points.`;
    if (g.kind === "minority") return `${partyName(g.party)} scrapes the narrowest of wins: ${g.seats} points.`;
    if (g.kind === "hung") return `Dead heat. France recounts through the night; the Republic holds its breath.`;
    return undefined;
  },

  issues: [
    { id: "cost_of_living", name: "Pouvoir d'achat", blurb: "Purchasing power: the issue that decides French elections." },
    { id: "immigration_identity", name: "Immigration & identity", blurb: "The RN's home turf: borders, laïcité, national identity." },
    { id: "security", name: "Security", blurb: "Crime, policing, and the sense of order." },
    { id: "pensions", name: "Pensions", blurb: "The retirement-age wound that never closed." },
    { id: "europe", name: "Europe & the franc débat", blurb: "France's place in the EU, and who pays for it." },
    { id: "public_services", name: "Public services", blurb: "Hospitals, schools, and the deserted countryside." },
  ],

  blocs: [
    { id: "urban_graduate", name: "Urban graduates", share: 0.26, turnoutPropensity: 0.78,
      tilt: { ens: 0.45, rn: -0.45 } },
    { id: "periurban_worker", name: "Periurban workers", share: 0.28, turnoutPropensity: 0.66,
      tilt: { rn: 0.5, ens: -0.5 } },
    { id: "rural", name: "Rural France", share: 0.20, turnoutPropensity: 0.7,
      tilt: { rn: 0.3, ens: -0.3 } },
    { id: "young", name: "Young voters (18-29)", share: 0.16, turnoutPropensity: 0.52,
      tilt: { rn: 0.15, ens: -0.15 } },
    { id: "senior", name: "Seniors (65+)", share: 0.24, turnoutPropensity: 0.85,
      tilt: { ens: 0.45, rn: -0.45 } },
  ],

  regions: [
    { id: "HDF", name: "Hauts-de-France", abbr: "HdF", seats: 9, electorate: 2900, tile: { row: 0, col: 2 },
      profile: { periurban_worker: 1.3, urban_graduate: 0.8 } },
    { id: "NOR", name: "Normandie", abbr: "Nor", seats: 5, electorate: 1700, tile: { row: 0, col: 1 },
      profile: { rural: 1.15, periurban_worker: 1.1 } },
    { id: "GE", name: "Grand Est", abbr: "GE", seats: 8, electorate: 2700, tile: { row: 0, col: 3 },
      profile: { periurban_worker: 1.2 } },
    { id: "BRE", name: "Bretagne", abbr: "Bre", seats: 5, electorate: 1800, tile: { row: 1, col: 0 },
      profile: { urban_graduate: 1.1, senior: 1.1 } },
    { id: "PDL", name: "Pays de la Loire", abbr: "PdL", seats: 6, electorate: 2000, tile: { row: 1, col: 1 },
      profile: { senior: 1.1, rural: 1.1 } },
    { id: "IDF", name: "Île-de-France", abbr: "IdF", seats: 18, electorate: 5600, tile: { row: 1, col: 2 },
      profile: { urban_graduate: 1.5, young: 1.2, rural: 0.3, senior: 0.8 } },
    { id: "BFC", name: "Bourgogne-Franche-Comté", abbr: "BFC", seats: 4, electorate: 1400, tile: { row: 1, col: 3 },
      profile: { rural: 1.3, periurban_worker: 1.1 } },
    { id: "CVL", name: "Centre-Val de Loire", abbr: "CVL", seats: 4, electorate: 1300, tile: { row: 2, col: 2 },
      profile: { rural: 1.25 } },
    { id: "NAQ", name: "Nouvelle-Aquitaine", abbr: "NAq", seats: 9, electorate: 3100, tile: { row: 2, col: 1 },
      profile: { senior: 1.15, rural: 1.15 } },
    { id: "ARA", name: "Auvergne-Rhône-Alpes", abbr: "ARA", seats: 12, electorate: 4000, tile: { row: 2, col: 3 },
      profile: { urban_graduate: 1.05 } },
    { id: "OCC", name: "Occitanie", abbr: "Occ", seats: 9, electorate: 3000, tile: { row: 3, col: 2 },
      profile: { rural: 1.1, young: 1.05 } },
    { id: "PACA", name: "Provence-Alpes-Côte d'Azur", abbr: "PACA", seats: 8, electorate: 2600, tile: { row: 3, col: 3 },
      profile: { senior: 1.2, periurban_worker: 1.1 } },
    { id: "COM", name: "Corse & Outre-mer", abbr: "DOM", seats: 3, electorate: 1200, tile: { row: 3, col: 0 },
      profile: { young: 1.15, periurban_worker: 1.1 } },
  ],

  elections: {
    "2027": {
      id: "2027",
      year: 2027,
      label: "2027 · The Republic's Runoff",
      tagline: "April 2027. Macron is term-limited, the first round is done, and for the first time the RN enters a runoff at parity. Two weeks. Every point counts.",
      salience: { cost_of_living: 0.9, immigration_identity: 0.8, security: 0.65, pensions: 0.6, public_services: 0.55, europe: 0.5 },
      regions: {
        HDF:  { v: { ens: 0.42, rn: 0.58 }, s: { ens: 4, rn: 5 } },
        NOR:  { v: { ens: 0.48, rn: 0.52 }, s: { ens: 2, rn: 3 } },
        GE:   { v: { ens: 0.45, rn: 0.55 }, s: { ens: 4, rn: 4 } },
        BRE:  { v: { ens: 0.60, rn: 0.40 }, s: { ens: 3, rn: 2 } },
        PDL:  { v: { ens: 0.58, rn: 0.42 }, s: { ens: 4, rn: 2 } },
        IDF:  { v: { ens: 0.62, rn: 0.38 }, s: { ens: 11, rn: 7 } },
        BFC:  { v: { ens: 0.47, rn: 0.53 }, s: { ens: 2, rn: 2 } },
        CVL:  { v: { ens: 0.50, rn: 0.50 }, s: { ens: 2, rn: 2 } },
        NAQ:  { v: { ens: 0.54, rn: 0.46 }, s: { ens: 5, rn: 4 } },
        ARA:  { v: { ens: 0.53, rn: 0.47 }, s: { ens: 6, rn: 6 } },
        OCC:  { v: { ens: 0.52, rn: 0.48 }, s: { ens: 5, rn: 4 } },
        PACA: { v: { ens: 0.43, rn: 0.57 }, s: { ens: 3, rn: 5 } },
        COM:  { v: { ens: 0.52, rn: 0.48 }, s: { ens: 2, rn: 1 } },
      },
      events: [
        { id: "succession_2027", headline: "The post-Macron succession scramble spills into the open as the centre squabbles over its own crown", role: "any", party: "ens", weight: 3, appeal: -0.02, momentum: -5, turn: 0 },
        { id: "normalisation_2027", headline: "Normalization pays: prime-time profiles cast the RN as a party of government, suits and spreadsheets", role: "any", party: "rn", weight: 3, appeal: 0.03, momentum: 7, turn: 1 },
        { id: "degradation_dette_2027", headline: "A ratings agency downgrades French debt, and the bill for the Macron years lands on the centre's doorstep", role: "any", party: "ens", weight: 3, appeal: -0.03, momentum: -7, turn: 2 },
        { id: "peur_cohabitation_2027", headline: "Cohabitation dread: markets and Brussels game out an RN Élysée, and hesitant voters flinch", role: "any", party: "rn", weight: 3, appeal: -0.03, momentum: -7, turn: 3 },
        { id: "front_republicain_2027", headline: "One more time with feeling: the front républicain question. Eliminated candidates' voters break, reluctantly, for the centre", role: "any", party: "ens", weight: 3, appeal: 0.035, momentum: 8, turn: 4 },
        { id: "banlieue_2027", headline: "A banlieue security flashpoint drags the final days onto the RN's ground", role: "any", party: "rn", weight: 2, appeal: 0.02, momentum: 5 },
      ],
    },

    "2022": {
      id: "2022",
      year: 2022,
      label: "2022 · Macron v. Le Pen",
      tagline: "April 24, 2022. The rematch nobody wanted twice: pouvoir d'achat against l'État McKinsey, war on the continent, and a much better-drilled Le Pen. Two weeks to hold the Republic's line.",
      salience: { cost_of_living: 0.95, immigration_identity: 0.7, security: 0.55, pensions: 0.7, public_services: 0.5, europe: 0.6 },
      // The historical runoff still opens at 59-41. A disciplined RN operation
      // can convert its late cost-of-living message into a narrow upset, while
      // idle play keeps the historical favourite ahead.
      campaignPower: { rn: 1.35 },
      regions: {
        HDF:  { v: { ens: 0.473, rn: 0.527 }, s: { ens: 4, rn: 5 } },
        NOR:  { v: { ens: 0.558, rn: 0.442 }, s: { ens: 3, rn: 2 } },
        GE:   { v: { ens: 0.517, rn: 0.483 }, s: { ens: 4, rn: 4 } },
        BRE:  { v: { ens: 0.685, rn: 0.315 }, s: { ens: 4, rn: 1 } },
        PDL:  { v: { ens: 0.667, rn: 0.333 }, s: { ens: 4, rn: 2 } },
        IDF:  { v: { ens: 0.738, rn: 0.262 }, s: { ens: 13, rn: 5 } },
        BFC:  { v: { ens: 0.536, rn: 0.464 }, s: { ens: 2, rn: 2 } },
        CVL:  { v: { ens: 0.556, rn: 0.444 }, s: { ens: 2, rn: 2 } },
        NAQ:  { v: { ens: 0.597, rn: 0.403 }, s: { ens: 6, rn: 3 } },
        ARA:  { v: { ens: 0.587, rn: 0.413 }, s: { ens: 7, rn: 5 } },
        OCC:  { v: { ens: 0.558, rn: 0.442 }, s: { ens: 5, rn: 4 } },
        PACA: { v: { ens: 0.494, rn: 0.506 }, s: { ens: 4, rn: 4 } },
        COM:  { v: { ens: 0.410, rn: 0.590 }, s: { ens: 1, rn: 2 } },
      },
      events: [
        { id: "mckinsey_2022", headline: "The McKinsey affair: millions in consulting fees, zero corporate tax. 'l'État McKinsey' sticks to the incumbent", role: "any", party: "ens", weight: 3, appeal: -0.03, momentum: -7, turn: 0 },
        { id: "retraite_65_2022", headline: "Retraite à 65 ans: the pension line detonates on the left, and Macron's own reform becomes the attack ad", role: "any", party: "ens", weight: 3, appeal: -0.03, momentum: -6, turn: 1 },
        { id: "pouvoir_achat_2022", headline: "One theme, hammered daily: Le Pen's pouvoir d'achat campaign owns the caddie and the fuel pump", role: "any", party: "rn", weight: 3, appeal: 0.035, momentum: 8, turn: 2 },
        { id: "prets_russes_2022", headline: "The Putin handshake photo, brandished on set: the Russian bank loan. 'Vous dépendez du pouvoir russe'", role: "any", party: "rn", weight: 3, appeal: -0.035, momentum: -8, turn: 3 },
        { id: "debat_2022", headline: "A calmer entre-deux-tours: Le Pen survives the rematch, but loses it on points", role: "any", party: "ens", weight: 4, appeal: 0.04, momentum: 9, turn: 4 },
        { id: "melenchon_3e_tour_2022", headline: "'Élisez-moi Premier ministre': Mélenchon reframes the runoff as round one of a troisième tour, deflating {party}", role: "any", weight: 3, appeal: -0.02, momentum: -5 },
        { id: "essence_2022", headline: "Pump prices spike again. Every full tank is a Le Pen leaflet, and the incumbent owns the price board", role: "any", party: "ens", weight: 2, appeal: -0.02, momentum: -5 },
        { id: "caddie_2022", headline: "Split-screen politics: McKinsey invoices against the supermarket caddie. The cost-of-living contrast lands for the RN", role: "any", party: "rn", weight: 2, appeal: 0.02, momentum: 5 },
        { id: "stature_ukraine_2022", headline: "War on the continent: Kyiv calls the Élysée and the commander-in-chief effect kicks in for the centre", role: "any", party: "ens", weight: 2, appeal: 0.03, momentum: 7 },
      ],
    },

    "2017": {
      id: "2017",
      year: 2017,
      label: "2017 · The Front Républicain",
      tagline: "May 7, 2017. A 39-year-old insurgent with a startup for a party against the FN's first runoff since 2002. The dam holds everywhere. The only question is by how much.",
      salience: { cost_of_living: 0.6, immigration_identity: 0.7, security: 0.6, pensions: 0.35, public_services: 0.5, europe: 0.85 },
      regions: {
        HDF:  { v: { ens: 0.529, rn: 0.471 }, s: { ens: 5, rn: 4 } },
        NOR:  { v: { ens: 0.630, rn: 0.370 }, s: { ens: 3, rn: 2 } },
        GE:   { v: { ens: 0.594, rn: 0.406 }, s: { ens: 5, rn: 3 } },
        BRE:  { v: { ens: 0.754, rn: 0.246 }, s: { ens: 4, rn: 1 } },
        PDL:  { v: { ens: 0.740, rn: 0.260 }, s: { ens: 4, rn: 2 } },
        IDF:  { v: { ens: 0.788, rn: 0.212 }, s: { ens: 14, rn: 4 } },
        BFC:  { v: { ens: 0.618, rn: 0.382 }, s: { ens: 2, rn: 2 } },
        CVL:  { v: { ens: 0.643, rn: 0.357 }, s: { ens: 3, rn: 1 } },
        NAQ:  { v: { ens: 0.683, rn: 0.317 }, s: { ens: 6, rn: 3 } },
        ARA:  { v: { ens: 0.659, rn: 0.341 }, s: { ens: 8, rn: 4 } },
        OCC:  { v: { ens: 0.636, rn: 0.364 }, s: { ens: 6, rn: 3 } },
        PACA: { v: { ens: 0.554, rn: 0.446 }, s: { ens: 4, rn: 4 } },
        COM:  { v: { ens: 0.620, rn: 0.380 }, s: { ens: 2, rn: 1 } },
      },
      events: [
        { id: "whirlpool_2017", headline: "The Whirlpool car park: Le Pen's selfies against Macron's hour in the crowd. Both camps claim Amiens", role: "any", weight: 3, appeal: 0.03, momentum: 8, turn: 1 },
        { id: "front_republicain_2017", headline: "The front républicain holds its nose and rallies: 'contre l'extrême droite', and so, for Macron", role: "any", party: "ens", weight: 3, appeal: 0.03, momentum: 7, turn: 2 },
        { id: "obama_2017", headline: "'En Marche!' Obama's endorsement video hands the insurgent a global-stage glow", role: "any", party: "ens", weight: 2, appeal: 0.02, momentum: 6, turn: 3 },
        { id: "debat_2017", headline: "Debate meltdown: an aggressive, fact-free entre-deux-tours. Le Pen loses the presidency on live TV", role: "any", party: "rn", weight: 4, appeal: -0.045, momentum: -12, turn: 4 },
        { id: "macronleaks_2017", headline: "#MacronLeaks dumps 36 hours before the vote. The campaign blackout blunts it, the doubt lingers", role: "any", party: "ens", weight: 3, appeal: -0.02, momentum: -6, turn: 5 },
        { id: "ni_ni_2017", headline: "'Ni-ni': Mélenchon won't say the name, and the abstention question gnaws at the centre's margin", role: "any", party: "ens", weight: 3, appeal: -0.025, momentum: -6 },
        { id: "marine_seule_2017", headline: "Le Pen steps 'above the party' for the runoff, and the de-demonized Marine plays presidential for a news cycle", role: "any", party: "rn", weight: 3, appeal: 0.025, momentum: 6 },
        { id: "colere_ouvriere_2017", headline: "Deindustrialized France seethes: shuttered factories put pouvoir d'achat on the RN's side of the ledger", role: "any", party: "rn", weight: 2, appeal: 0.02, momentum: 5 },
      ],
    },

    "2012": {
      id: "2012",
      year: 2012,
      label: "2012 · Hollande v. Sarkozy",
      // ens = Hollande (Parti socialiste); rn = Sarkozy (UMP). A left-vs-right
      // runoff mapped onto the bundle's two slots.
      tagline: "May 6, 2012. A one-term incumbent nobody warmed to against a Socialist who promised to be normal. 'Le changement, c'est maintenant' against a crisis president. Two weeks, and a narrow win either way.",
      salience: { cost_of_living: 0.85, public_services: 0.6, europe: 0.6, security: 0.55, immigration_identity: 0.5, pensions: 0.55 },
      regions: {
        HDF:  { v: { ens: 0.535, rn: 0.465 }, s: { ens: 5, rn: 4 } },
        NOR:  { v: { ens: 0.515, rn: 0.485 }, s: { ens: 3, rn: 2 } },
        GE:   { v: { ens: 0.455, rn: 0.545 }, s: { ens: 4, rn: 4 } },
        BRE:  { v: { ens: 0.565, rn: 0.435 }, s: { ens: 3, rn: 2 } },
        PDL:  { v: { ens: 0.515, rn: 0.485 }, s: { ens: 3, rn: 3 } },
        IDF:  { v: { ens: 0.555, rn: 0.445 }, s: { ens: 10, rn: 8 } },
        BFC:  { v: { ens: 0.515, rn: 0.485 }, s: { ens: 2, rn: 2 } },
        CVL:  { v: { ens: 0.505, rn: 0.495 }, s: { ens: 2, rn: 2 } },
        NAQ:  { v: { ens: 0.565, rn: 0.435 }, s: { ens: 5, rn: 4 } },
        ARA:  { v: { ens: 0.490, rn: 0.510 }, s: { ens: 6, rn: 6 } },
        OCC:  { v: { ens: 0.560, rn: 0.440 }, s: { ens: 5, rn: 4 } },
        PACA: { v: { ens: 0.430, rn: 0.570 }, s: { ens: 3, rn: 5 } },
        COM:  { v: { ens: 0.580, rn: 0.420 }, s: { ens: 2, rn: 1 } },
      },
      events: [
        { id: "crise_2012", headline: "The euro crisis is the backdrop to everything: austerity, unemployment past three million, and {party} owns five years of it", role: "any", party: "rn", weight: 3, appeal: -0.03, momentum: -7, turn: 0 },
        { id: "merah_2012", headline: "The Toulouse and Montauban killings freeze the campaign, and the security-and-identity ground briefly favours the incumbent's {party}", role: "any", party: "rn", weight: 3, appeal: 0.03, momentum: 7, turn: 1 },
        { id: "president_normal_2012", headline: "'Moi, président de la République': the 'normal president' anaphora lands the debate for {party}", role: "any", party: "ens", weight: 4, appeal: 0.035, momentum: 9, turn: 2 },
        { id: "front_national_2012", headline: "'Ni Sarkozy': after a record far-right first round, Le Pen's voters won't be told where to go, and the right finalist's {party} can't bank them all", role: "any", party: "rn", weight: 3, appeal: -0.025, momentum: -6, turn: 3 },
        { id: "changement_maintenant_2012", headline: "'Le changement, c'est maintenant': ten years of rejection of the sitting president crystallize behind {party}", role: "any", party: "ens", weight: 3, appeal: 0.03, momentum: 7, turn: 4 },
        { id: "bayrou_ralliement_2012", headline: "Bayrou announces he will vote Hollande, and the centre's blessing nudges wavering moderates toward {party}", role: "any", party: "ens", weight: 2, appeal: 0.02, momentum: 5 },
        { id: "meeting_droite_2012", headline: "A giant Concorde rally and a hard pivot to the right rally the incumbent's {party} base in the final days", role: "any", party: "rn", weight: 2, appeal: 0.02, momentum: 5 },
      ],
    },
  },

  leaders: {
    "2027": {
      ens: { partyId: "ens", name: "Édouard Philippe", charisma: 58, energy: 62, competence: 76, machine: 70 },
      rn:  { partyId: "rn", name: "Jordan Bardella", charisma: 72, energy: 74, competence: 52, machine: 66 },
    },
    // 2022: the incumbent technocrat vs the softened, better-drilled challenger.
    "2022": {
      ens: { partyId: "ens", name: "Emmanuel Macron", charisma: 64, energy: 62, competence: 78, machine: 76 },
      rn:  { partyId: "rn", name: "Marine Le Pen", charisma: 62, energy: 66, competence: 60, machine: 62 },
    },
    // 2017: the insurgent with a startup machine vs the meltdown-debate Le Pen.
    "2017": {
      ens: { partyId: "ens", name: "Emmanuel Macron", charisma: 74, energy: 80, competence: 70, machine: 52 },
      rn:  { partyId: "rn", name: "Marine Le Pen", charisma: 60, energy: 64, competence: 36, machine: 58 },
    },
    // 2012: ens = the Socialist challenger, rn = the incumbent of the right.
    "2012": {
      ens: { partyId: "ens", name: "François Hollande", charisma: 54, energy: 60, competence: 64, machine: 68 },
      rn:  { partyId: "rn", name: "Nicolas Sarkozy", charisma: 66, energy: 74, competence: 66, machine: 74 },
    },
  },

  events: [
    { id: "debat_entre_deux", headline: "{party}'s candidate wins the entre-deux-tours debate", role: "any", weight: 4, appeal: 0.035, momentum: 12 },
    { id: "front_republicain", headline: "The front républicain stirs, and eliminated candidates' voters break for {party}", role: "any", weight: 3, appeal: 0.03, momentum: 8 },
    { id: "derapage", headline: "A dérapage: an ugly remark from a {party} surrogate dominates the cycle", role: "any", weight: 3, appeal: -0.035, momentum: -9 },
    { id: "prix_essence", headline: "A fuel-price spike lands hardest on {party}", role: "leader", weight: 2, appeal: -0.025, momentum: -5 },
    { id: "securite_incident", headline: "A security incident pushes the campaign onto {party}'s ground", role: "any", weight: 2, appeal: 0.03, momentum: 7 },
    { id: "abstention_alarm", headline: "Abstention warnings mobilize {party}'s ground game", role: "player", weight: 2, appeal: 0.02, momentum: 6 },
    { id: "presse_ralliement", headline: "A wave of editorials rallies to {party}", role: "any", weight: 2, appeal: 0.02, momentum: 5 },
    { id: "meeting_geant", headline: "A packed stadium meeting electrifies {party}", role: "player", weight: 2, appeal: 0.025, momentum: 9 },
    {
      id: "debat_call",
      headline: "The networks offer {party} a debate slot",
      role: "player",
      weight: 4,
      prompt: "A head-to-head is on the table. How do you play it?",
      choices: [
        { id: "accept_attack", text: "Accept and go on the attack", resultText: "You land blows and take a few. The overnight polls twitch your way.", appeal: 0.03, momentum: 10, rivalAppeal: -0.015 },
        { id: "accept_safe", text: "Accept and play it safe", resultText: "A steady, on-message night. No gaffes, no fireworks.", appeal: 0.015, momentum: 4 },
        { id: "decline", text: "Decline: protect the lead, avoid the ambush", resultText: "You dodge the studio lights. The press calls it caution.", appeal: -0.01, momentum: -4 },
      ],
    },
    {
      id: "front_fork",
      headline: "{party} must decide how hard to lean on the front républicain",
      role: "player",
      weight: 3,
      prompt: "The runoff arithmetic is everything. What's the play?",
      choices: [
        { id: "court_centre", text: "Court the centre: soft tone, broad tent", resultText: "Moderates warm up. Your base yawns.", appeal: 0.02, momentum: 5 },
        { id: "base_first", text: "Base first: turn out your own", resultText: "The faithful roar. The centre looks away.", appeal: 0.025, momentum: 8 },
        { id: "contrast", text: "Make it a contrast night against the rival", resultText: "You define them, and it lands.", appeal: 0.02, momentum: 6, rivalAppeal: -0.02 },
      ],
    },
    {
      id: "programme_fork",
      headline: "{party}'s programme committee is split on the big pledge",
      role: "player",
      weight: 3,
      prompt: "The draft is ready. Which way do you push?",
      choices: [
        { id: "bold", text: "Go bold with a defining, risky pledge", resultText: "The base roars. The spreadsheets at HQ look nervous.", appeal: 0.035, momentum: 12 },
        { id: "cautious", text: "Play it cautious: no hostages to fortune", resultText: "No spark, no self-inflicted wounds.", appeal: 0.01, momentum: 2 },
        { id: "contrast", text: "Make it a contrast document against the rival", resultText: "You define them more than yourself, and it lands.", appeal: 0.02, momentum: 6, rivalAppeal: -0.02 },
      ],
    },
  ],
};
