// Shared multiparty AI for the UK and country engines. The balance gauntlet's
// focused/scattershot bots lived in harness.ts; this module is the in-game
// planner those bots now call, with difficulty tiers (easy/normal/hard) that
// mirror the US AI's efficiency / mistake / foresight knobs.

import type { PartyId, MajorityRule } from "./system";
import type { StateContest } from "./types";
import type { Rng } from "./rng";
import { tallyRegion, computeSeatsResult, formGovernment } from "./multiparty";

export type MpDifficulty = "easy" | "normal" | "hard";

export interface MpAiConfig {
  // 0..1 budget-efficiency: higher = spends more, smarter target mix.
  efficiency: number;
  // 0..1 chance of a misallocation (shuffle targets) per planning pass.
  mistakeRate: number;
  // How many close regions the AI considers.
  foresight: number;
}

export const MP_DIFFICULTY: Record<MpDifficulty, MpAiConfig> = {
  easy: { efficiency: 0.55, mistakeRate: 0.35, foresight: 3 },
  normal: { efficiency: 0.85, mistakeRate: 0.12, foresight: 4 },
  hard: { efficiency: 1.0, mistakeRate: 0.04, foresight: 6 },
};

/** Action verbs shared by UkAction / CountryAction (and the gauntlet bots). */
export interface MpActionLike {
  type: "canvass" | "rally" | "ground_game" | "gotv" | "fundraise" | "broadcast" | "oppo_research";
  party: PartyId;
  regionId?: string;
  spend?: number;
  mode?: "positive" | "contrast";
  targetParty?: PartyId;
}

export interface MpView {
  regions: StateContest[];
  turn: number;
  totalTurns: number;
  funds: number;
  actions: number;
  // Optional government-formation context — when present in the final stretch,
  // the planner biases toward coalition math instead of pure largest-party.
  majority?: MajorityRule;
  abstaining?: PartyId[];
  compatible?: (lead: PartyId, partner: PartyId) => boolean;
}

interface MpTarget {
  region: StateContest;
  margin: number;
  rivalParty: PartyId | undefined;
  // Extra weight from the late-game coalition pass (0 when not applicable).
  coalitionBoost: number;
}

// Preserve content order for mathematical ties across JS and native math runtimes.
// Differences smaller than a trillionth must not change a campaign's AI plan.
function targetRank(value: number): number {
  return Math.round(value * 1e12);
}

// Regions ranked by how close the party sits to the local top rival.
export function mpTargets(view: MpView, party: PartyId, max: number): MpTarget[] {
  const standing = view.regions.filter((r) => r.baselineShare?.[party] !== undefined);
  const rows: MpTarget[] = standing.map((r) => {
    const { shareByParty } = tallyRegion(r);
    const mine = shareByParty[party] ?? 0;
    const rival = Math.max(
      0,
      ...Object.keys(shareByParty)
        .filter((p) => p !== party)
        .map((p) => shareByParty[p] ?? 0),
    );
    return {
      region: r,
      margin: mine - rival,
      rivalParty: topRival(shareByParty, party),
      coalitionBoost: 0,
    };
  });
  // Winnable = within striking distance of the local lead; fall back to the
  // closest contests so small parties still campaign somewhere.
  const winnable = rows.filter((r) => r.margin > -0.2);
  const pool = winnable.length > 0 ? winnable : rows;
  return pool.sort((a, b) => targetRank(Math.abs(a.margin)) - targetRank(Math.abs(b.margin))).slice(0, max);
}

function topRival(shareByParty: Record<string, number>, party: PartyId): PartyId | undefined {
  let best: PartyId | undefined;
  let bestShare = -1;
  for (const [p, s] of Object.entries(shareByParty)) {
    if (p === party) continue;
    if (s > bestShare) {
      bestShare = s;
      best = p as PartyId;
    }
  }
  return best;
}

// Late-campaign coalition pass: when no single party can reach majority, bias
// targets toward (a) regions where a natural partner is weak / flippable toward
// the partner, and (b) regions where the leading rival is vulnerable — so the
// AI improves government-formation math, not just largest-party seat count.
function applyCoalitionBias(
  view: MpView,
  party: PartyId,
  targets: MpTarget[],
  foresight: number,
): MpTarget[] {
  if (!view.majority) return targets;
  const turnsLeft = view.totalTurns - view.turn;
  if (turnsLeft > 2) return targets;

  const abstaining = view.abstaining ?? [];
  const compatible = view.compatible ?? (() => true);
  const projected = computeSeatsResult(view.regions, view.majority, abstaining, compatible);
  const seats = projected.seats;
  const mySeats = seats[party] ?? 0;
  const gov = formGovernment(seats, view.majority, abstaining, projected.largestParty, compatible);

  // Already on course for a majority (ours or anyone's firm majority) — no need.
  if (gov.kind === "majority") return targets;

  // Natural partners: compatible parties ranked by current seats (largest first).
  const partners = Object.keys(seats)
    .filter((p) => p !== party && !abstaining.includes(p) && compatible(party, p))
    .sort((a, b) => (seats[b] ?? 0) - (seats[a] ?? 0));
  const bestPartner = partners[0];
  if (!bestPartner) return targets;

  // Leading rival = largest incompatible (or simply largest other) party.
  const rival = Object.keys(seats)
    .filter((p) => p !== party)
    .sort((a, b) => (seats[b] ?? 0) - (seats[a] ?? 0))[0];

  // Coalition path is viable if we + partner clear the effective threshold.
  const abstainSeats = abstaining.reduce((s, p) => s + (seats[p] ?? 0), 0);
  const threshold =
    view.majority.effectiveThreshold ??
    Math.floor((view.majority.total - abstainSeats) / 2) + 1;
  const coalitionSeats = mySeats + (seats[bestPartner] ?? 0);
  // Near a solo majority → keep seat-maxing (don't divert to partner regions).
  // Only switch to coalition math when a majority alone is clearly out of reach
  // but a partner still gets us over the line (classic hung-parliament path).
  const soloGap = threshold - mySeats;
  if (mySeats >= threshold) return targets;
  if (soloGap <= 15) return targets;
  if (coalitionSeats < threshold) return targets;

  // Re-score every standing region with a coalition boost, then re-pick top N.
  const standing = view.regions.filter((r) => r.baselineShare?.[party] !== undefined);
  const rescored: MpTarget[] = standing.map((r) => {
    const { shareByParty } = tallyRegion(r);
    const mine = shareByParty[party] ?? 0;
    const rivalShare = Math.max(
      0,
      ...Object.keys(shareByParty)
        .filter((p) => p !== party)
        .map((p) => shareByParty[p] ?? 0),
    );
    const margin = mine - rivalShare;
    const partnerShare = shareByParty[bestPartner] ?? 0;
    const leadingRivalShare = rival ? (shareByParty[rival] ?? 0) : 0;
    // Partner-weak regions: partner is close behind a rival → help them (or
    // take the seat ourselves if we're closer). Rival-vulnerable: rival leads
    // narrowly and we or the partner can contest.
    let boost = 0;
    if (partnerShare > 0.08 && partnerShare + 0.12 >= rivalShare && margin < 0.05) {
      boost += 0.04; // partner can take or hold this with a push
    }
    if (leadingRivalShare > mine && leadingRivalShare - Math.max(mine, partnerShare) < 0.1) {
      boost += 0.05; // knock the rival off a knife-edge seat
    }
    if (margin > -0.08 && margin < 0.08) boost += 0.02; // still value our own tossups
    return {
      region: r,
      margin,
      rivalParty: topRival(shareByParty, party),
      coalitionBoost: boost,
    };
  });

  // Sort by (closeness + coalition boost); prefer boosted regions.
  rescored.sort((a, b) => {
    const scoreA = -Math.abs(a.margin) + a.coalitionBoost;
    const scoreB = -Math.abs(b.margin) + b.coalitionBoost;
    return targetRank(scoreB) - targetRank(scoreA);
  });
  return rescored.slice(0, foresight);
}

export function mpFocusedActions(
  view: MpView,
  party: PartyId,
  cfg: MpAiConfig = MP_DIFFICULTY.normal,
  rng?: Rng,
): MpActionLike[] {
  let targets = applyCoalitionBias(view, party, mpTargets(view, party, cfg.foresight), cfg.foresight);
  if (targets.length === 0) return [];

  // Occasional misallocation: shuffle priority (a "mistake").
  if (rng && targets.length > 1 && rng.chance(cfg.mistakeRate)) {
    const i = rng.int(0, targets.length - 1);
    const j = rng.int(0, targets.length - 1);
    [targets[i], targets[j]] = [targets[j], targets[i]];
  }

  const slots = view.actions;
  const turnsLeft = Math.max(1, view.totalTurns - view.turn);
  const out: MpActionLike[] = [];
  let funds = view.funds;

  if (funds < 2) {
    out.push({ type: "fundraise", party });
    funds += 1.5;
  }

  // Trailing hard AIs occasionally dig dirt nationally.
  const nationalShare = averageShare(view.regions, party);
  const losing = nationalShare < 0.28;
  if (losing && cfg.efficiency > 0.8 && funds >= 2 && slots > 2 && (!rng || rng.chance(0.15 + cfg.efficiency * 0.2))) {
    out.push({ type: "oppo_research", party });
    funds -= 2;
  }

  // One paid push per target: GOTV in the last stretch, field offices earlier;
  // canvass (free) when the kitty is empty. Plus a rally on the closest race.
  out.push({ type: "rally", party, regionId: targets[0].region.id });

  for (const t of targets) {
    if (out.length >= slots) break;
    if (turnsLeft <= 2 && funds >= 1) {
      out.push({ type: "gotv", party, regionId: t.region.id });
      funds -= 1;
    } else if (funds >= 1.5 && cfg.efficiency >= 0.7) {
      // Disciplined AIs buy a small regional broadcast on their closest race.
      if (out.length < slots - 1 && funds >= 2.5 && t === targets[0]) {
        const foe = t.rivalParty;
        out.push({
          type: "broadcast",
          party,
          regionId: t.region.id,
          spend: 1.5,
          mode: t.margin < 0 ? "contrast" : "positive",
          targetParty: foe,
        });
        funds -= 1.5;
      } else {
        out.push({ type: "ground_game", party, regionId: t.region.id });
        funds -= 1.5;
      }
    } else if (funds >= 1.5) {
      out.push({ type: "ground_game", party, regionId: t.region.id });
      funds -= 1.5;
    } else {
      out.push({ type: "canvass", party, regionId: t.region.id });
    }
  }

  // Remaining slots: free canvassing round-robin over the same targets.
  let i = 0;
  while (out.length < slots) {
    out.push({ type: "canvass", party, regionId: targets[i % targets.length].region.id });
    i++;
  }
  return out.slice(0, slots);
}

export function mpScattershotActions(view: MpView, party: PartyId): MpActionLike[] {
  const standing = view.regions.filter((r) => r.baselineShare?.[party] !== undefined);
  if (standing.length === 0) return [];
  const slots = view.actions;
  const out: MpActionLike[] = [];
  if (view.funds < 1) out.push({ type: "fundraise", party });
  let i = 0;
  while (out.length < slots) {
    out.push({ type: "canvass", party, regionId: standing[i % standing.length].id });
    i++;
  }
  return out.slice(0, slots);
}

function averageShare(regions: StateContest[], party: PartyId): number {
  let num = 0;
  let den = 0;
  for (const r of regions) {
    if (r.baselineShare?.[party] === undefined) continue;
    const { shareByParty, totalVotes } = tallyRegion(r);
    num += (shareByParty[party] ?? 0) * totalVotes;
    den += totalVotes;
  }
  return den > 0 ? num / den : 0;
}

/** In-game planner used by ukGame / countryGame. */
export function planMultipartyAi(
  view: MpView,
  party: PartyId,
  difficulty: MpDifficulty,
  rng: Rng,
): MpActionLike[] {
  const cfg = MP_DIFFICULTY[difficulty];
  // Easy AI occasionally goes scattershot (sloppy opponent).
  if (difficulty === "easy" && rng.chance(0.35)) return mpScattershotActions(view, party);
  return mpFocusedActions(view, party, cfg, rng);
}
