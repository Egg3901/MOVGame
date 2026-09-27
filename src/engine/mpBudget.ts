// Multiparty campaign funds are measured in millions of local currency.
export function mpActionCost(action: { type: string; spend?: number }): number {
  switch (action.type) {
    case "broadcast": return Math.max(0.5, action.spend ?? 1.5);
    case "ground_game": return 1.5;
    case "gotv": return 1;
    case "surrogate": return 0.25;
    case "oppo_research": return 2;
    default: return 0;
  }
}

export function mpPlannedCost(actions: readonly { type: string; spend?: number }[]): number {
  return actions.reduce((sum, action) => sum + mpActionCost(action), 0);
}
