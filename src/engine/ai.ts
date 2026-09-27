import type { CampaignAction, CandidateId, GameState, IssueId } from "./types";
import type { Rng } from "./rng";
import { projectElection } from "./voteModel";
import { issueAlignment } from "./actions";
import { OPPONENT_OF } from "@content/candidates";
import { HISTORICAL_EVENTS, GENERIC_DEBATES } from "@content/events";
import { BLOCS } from "@content/blocs";
import { ISSUE_IDS } from "@content/issues";

// Is a scheduled debate coming within the next `turns` weeks? Lets the AI prep
// ahead of showtime instead of being caught flat-footed on the big stage.
function debateWithin(game: GameState, turns: number): boolean {
  const historical = (game.eventMode ?? "historical") === "historical";
  const deck = historical
    ? HISTORICAL_EVENTS[game.scenarioId ?? "2020"] ?? HISTORICAL_EVENTS["2020"]
    : GENERIC_DEBATES;
  return deck.some(
    (e) => e.isDebate && e.trigger.kind === "scheduled" && e.trigger.turn > game.turn && e.trigger.turn <= game.turn + turns,
  );
}

// The issue whose higher salience would most help the AI: its own coalition's
// alignment advantage, weighted by how much room that issue has left to climb.
function bestIssueForAi(game: GameState, ai: CandidateId): IssueId | null {
  let best: IssueId | null = null;
  let bestScore = 0.05;
  for (const issueId of ISSUE_IDS) {
    const headroom = 1 - (game.salience[issueId] ?? 0.5);
    if (headroom < 0.12) continue;
    let adv = 0;
    for (const blocId of Object.keys(BLOCS)) adv += issueAlignment(game, ai, blocId, issueId) - 0.5;
    const score = adv * headroom;
    if (score > bestScore) {
      bestScore = score;
      best = issueId;
    }
  }
  return best;
}

export interface AiConfig {
  // 0..1 budget-efficiency multiplier; higher = spends more, smarter.
  efficiency: number;
  // 0..1 chance of a misallocation per planning pass.
  mistakeRate: number;
  // How many target states the AI considers (foresight depth).
  foresight: number;
  // Persuasion yield of AI actions. Cash and action-slot costs are unchanged.
  actionPower?: number;
}

export const DIFFICULTY: Record<"easy" | "normal" | "hard", AiConfig> = {
  easy: { efficiency: 0.55, mistakeRate: 0.3, foresight: 3, actionPower: 0.45 },
  normal: { efficiency: 0.8, mistakeRate: 0.15, foresight: 5, actionPower: 0.53 },
  hard: { efficiency: 1.0, mistakeRate: 0.05, foresight: 8, actionPower: 1 },
};

function aiShareOf(demShare: number, ai: CandidateId): number {
  return ai === "dem" ? demShare : 1 - demShare;
}

// Competitive band (distance from 50/50) flexes with the scoreboard: trailers
// reach further; comfortable leaders defend only real tossups.
export function competitiveBand(evMargin: number): number {
  if (evMargin < -80) return 0.11;
  if (evMargin < -40) return 0.09;
  if (evMargin < -15) return 0.07;
  if (evMargin > 60) return 0.03;
  if (evMargin > 30) return 0.04;
  return 0.055;
}

// Ranks contests by expected EV gain per ad dollar — close, cheap, high-EV
// states float to the top (Section 8). Exported so the gauntlet focused bot
// can share the same ranking and stay ≥ scattershot.
export interface Target {
  stateId: string;
  ev: number;
  aiShare: number;
  mediaCost: number;
  // Expected EV flipped per $1M of media, given current closeness.
  evPerDollar: number;
  priority: number;
}

export function rankTargets(game: GameState, ai: CandidateId, cfg: AiConfig): Target[] {
  const proj = projectElection(game);
  const evMargin = proj.ev[ai] - proj.ev[OPPONENT_OF[ai]];
  const band = competitiveBand(evMargin);
  const targets: Target[] = [];
  for (const sr of proj.contests) {
    const st = game.states.find((s) => s.id === sr.stateId);
    if (!st || st.blocs.length === 0) continue;
    const aiShare = aiShareOf(sr.demShare, ai);
    const dist = Math.abs(sr.demShare - 0.5);
    if (dist > band) continue;
    const closeness = 1 - dist * 2; // 1 = coin flip
    // Soft diminishing returns past ~$8M media cost so CA/NY don't dominate
    // just because they have huge EV when somehow inside a widened band.
    const costScale = Math.max(0.35, Math.min(1.4, 8 / Math.max(1, st.mediaMarketCost)));
    // Rough EV-gain-per-dollar: flippable EV × closeness / media cost.
    // A state the AI already leads slightly is still worth defending (closeness
    // stays high near 50); deep leads/trails are filtered by `band`.
    const evPerDollar = (st.electoralVotes * closeness * costScale) / Math.max(0.5, st.mediaMarketCost);
    targets.push({
      stateId: st.id,
      ev: st.electoralVotes,
      aiShare,
      mediaCost: st.mediaMarketCost,
      evPerDollar,
      priority: evPerDollar,
    });
  }
  targets.sort((a, b) => b.priority - a.priority);
  return targets.slice(0, cfg.foresight);
}

// Cap how many non-ad "overhead" actions can eat the weekly pool before ads
// and rallies get their turn. Hard AIs keep almost every slot for persuasion.
function overheadCap(cfg: AiConfig, slots: number): number {
  if (cfg.efficiency >= 0.95) return Math.min(2, Math.max(1, Math.floor(slots * 0.2)));
  if (cfg.efficiency >= 0.75) return Math.min(3, Math.max(1, Math.floor(slots * 0.3)));
  return Math.min(4, Math.max(2, Math.floor(slots * 0.4)));
}

// Builds the AI's action list for this turn. Budget-first: reserve most slots
// for ads/rallies, spend a paced cash slice, and only then fill overhead
// (fundraise / ground / debate prep) into the leftover cap.
export function planAiActions(game: GameState, rng: Rng, cfg: AiConfig): CampaignAction[] {
  const ai = OPPONENT_OF[game.playerCandidate];
  const res = game.resources[ai];
  const actions: CampaignAction[] = [];
  let targets = rankTargets(game, ai, cfg);
  if (targets.length === 0) return actions;

  // Occasional misallocation: shuffle the priority order (a "mistake").
  if (rng.chance(cfg.mistakeRate)) {
    const i = rng.int(0, targets.length - 1);
    const j = rng.int(0, targets.length - 1);
    [targets[i], targets[j]] = [targets[j], targets[i]];
  }

  const slots = res.actions;
  let budget = slots;
  const turnsLeft = game.totalTurns - game.turn;
  const maxOverhead = overheadCap(cfg, slots);
  // Pace ad spend across remaining weeks; efficiency scales how aggressively
  // the AI empties the war chest.
  const adBudget = (res.cash / Math.max(2, turnsLeft)) * (0.85 + cfg.efficiency * 0.35);
  const prioritySum = targets.reduce((s, t) => s + t.priority, 0) || 1;
  const proj = projectElection(game);
  const losing = proj.ev[ai] < proj.ev[OPPONENT_OF[ai]] - 20;

  // ── Persuasion first (ads + rallies + late GOTV) ────────────────────────
  // Interleave rally then ad per target so the map marker lands and the spend
  // spreads across the competitive band instead of dumping into one state.
  const persuasion: CampaignAction[] = [];
  for (const t of targets) {
    persuasion.push({ type: "rally", candidate: ai, stateId: t.stateId });
    const spend = (adBudget * t.priority) / prioritySum;
    if (spend >= 200_000 && res.cash >= spend) {
      const mode = t.aiShare < 0.49 ? "contrast" : "positive";
      persuasion.push({ type: "advertise", candidate: ai, stateId: t.stateId, adMode: mode, spend });
    }
  }
  // Issue ad on the AI's best frame when there's cash and a clear issue edge.
  if (res.cash > 6_000_000 && rng.chance(0.2 + cfg.efficiency * 0.3)) {
    const issueId = bestIssueForAi(game, ai);
    if (issueId) {
      persuasion.unshift({
        type: "advertise",
        candidate: ai,
        stateId: targets[0].stateId,
        adMode: "issue",
        issueId,
        spend: 6_000_000,
      });
    }
  }
  if (turnsLeft <= 2) {
    for (const t of targets.slice(0, 3)) {
      const st = game.states.find((s) => s.id === t.stateId);
      if (st && st.groundGame[ai] > 0.05) {
        persuasion.push({ type: "gotv", candidate: ai, stateId: t.stateId });
      }
    }
  }

  // Reserve enough slots that overhead can't starve persuasion. At least half
  // the pool (rounded up) goes to persuasion when there is anything to do.
  const persuasionReserve = Math.min(persuasion.length, Math.max(Math.ceil(slots * 0.55), slots - maxOverhead));
  for (const a of persuasion) {
    if (actions.length >= persuasionReserve || budget <= 0) break;
    actions.push(a);
    budget -= 1;
  }

  // ── Overhead into the remaining cap ─────────────────────────────────────
  let overheadUsed = 0;
  const canOverhead = () => overheadUsed < maxOverhead && budget > 0;

  const cashFloor = 30_000_000 * Math.min(4, Math.max(1, turnsLeft));
  if (res.cash < cashFloor && canOverhead()) {
    const richest = [...targets].sort((a, b) => b.ev - a.ev)[0];
    actions.push({ type: "fundraise", candidate: ai, stateId: richest.stateId });
    budget -= 1;
    overheadUsed += 1;
  }
  if (losing && res.cash > 2_000_000 && canOverhead() && rng.chance(0.2 + cfg.efficiency * 0.25)) {
    actions.push({ type: "oppo_research", candidate: ai });
    budget -= 1;
    overheadUsed += 1;
  }
  // Ground game early only — sticky field offices, not a weekly habit that
  // crowds out ads. Cap at one per turn.
  if (turnsLeft > 3 && res.staffCapacity > 0 && canOverhead()) {
    actions.push({ type: "ground_game", candidate: ai, stateId: targets[0].stateId });
    budget -= 1;
    overheadUsed += 1;
  }
  if (debateWithin(game, 2) && canOverhead()) {
    const t = game.candidates[ai].traits;
    if (Math.min(t.debatePrep, t.policyKnowledge) < 86 && rng.chance(0.5 + cfg.efficiency * 0.45)) {
      actions.push({ type: t.debatePrep <= t.policyKnowledge ? "debate_prep" : "policy_prep", candidate: ai });
      budget -= 1;
      overheadUsed += 1;
    }
  }

  // Fill any leftover slots with more persuasion (never more overhead).
  for (const a of persuasion) {
    if (budget <= 0) break;
    if (actions.includes(a)) continue;
    actions.push(a);
    budget -= 1;
  }

  return actions.slice(0, slots);
}
