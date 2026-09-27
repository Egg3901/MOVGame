import type { CampaignAction, GameState } from "./types";

// Reserve every known cash expense when the player plans the week. Fundraising
// is realized only during resolution, so it cannot finance earlier commitments.
export function actionCashCost(action: CampaignAction): number {
  switch (action.type) {
    case "advertise": return Math.max(0, action.spend ?? 0);
    case "surrogate": return 250_000;
    case "ground_game": return 1_500_000;
    case "gotv": return 1_000_000;
    case "oppo_research": return 2_000_000;
    default: return 0;
  }
}

export function plannedCashCost(game: GameState): number {
  return game.queuedActions
    .filter((action) => action.candidate === game.playerCandidate)
    .reduce((sum, action) => sum + actionCashCost(action), 0);
}

export function canQueueAction(game: GameState, action: CampaignAction): boolean {
  const player = game.playerCandidate;
  const day = action.day ?? 1;
  if (action.candidate !== player || day < 1 || day > 7) return false;
  if (game.queuedActions.length >= game.resources[player].maxActions) return false;
  if (game.queuedActions.filter((queued) => (queued.day ?? 1) === day).length >= 3) return false;
  return plannedCashCost(game) + actionCashCost(action) <= game.resources[player].cash;
}
