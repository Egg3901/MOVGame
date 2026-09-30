import { advanceTurn } from "./turn";
import { DIFFICULTY } from "./ai";
import { projectElection } from "./voteModel";
import { createRng } from "./rng";
import { STAFF_BY_ID, staffEffects } from "../content/staff";
import type { GameState } from "./types";

/** The complete US campaign week used by clients, including staff upkeep. */
export function advanceCampaignWeek(game: GameState, difficulty: "easy" | "normal" | "hard"): GameState {
  if (game.phase === "result") return game;
  const next = advanceTurn(game, game.queuedActions, game.seed, { difficulty: DIFFICULTY[difficulty] });

  // Weekly salaries come off the war chest; a staffer whose loyalty is thin
  // may walk when the map turns ugly.
  const player = next.playerCandidate;
  const hires = next.staff?.[player] ?? [];
  if (hires.length > 0 && next.phase !== "result") {
    const fx = staffEffects(next, player);
    next.resources[player].cash = Math.max(0, next.resources[player].cash - fx.salaryPerWeek);
    next.lastRecap.push({
      label: "Staff payroll",
      detail: hires.map((id) => STAFF_BY_ID[id]?.role ?? id).join(", "),
      marginDelta: undefined,
    });

    const proj = projectElection(next);
    const playerEV = player === "dem" ? proj.ev.dem : proj.ev.rep;
    if (playerEV < 195) {
      const remaining: string[] = [];
      for (const id of hires) {
        const def = STAFF_BY_ID[id];
        const quits = def && def.loyalty < 65 && createRng(`staff:${next.seed}:${next.turn}:${id}`).next() < 0.22;
        if (quits) {
          // Losing an action-granting staffer shrinks the weekly pool too.
          const slots = def.effects.maxActions ?? 0;
          next.resources[player].maxActions = Math.max(1, next.resources[player].maxActions - slots);
          next.resources[player].actions = Math.min(next.resources[player].actions, next.resources[player].maxActions);
          next.lastRecap.unshift({
            label: `${def.emoji} ${def.name} quits the campaign`,
            detail: `"${def.role}s don't go down with the ship." The polls looked terminal.`,
          });
        } else {
          remaining.push(id);
        }
      }
      next.staff = { ...next.staff, [player]: remaining };
    }
  }
  return next;
}
