import { create } from "zustand";
import {
  createUkGame,
  ukAdvanceTurn,
  projectUk,
  projectUkPreview,
  resolveUkPlayerEvent,
  UK_PLAYABLE,
  type UkGameState,
  type UkAction,
  type UkResult,
  type Difficulty,
} from "@engine/ukGame";
import type { PartyId } from "@engine/system";
import type { ReplayLog, ReplayMode } from "@lib/replay";
import { truncateToTurn } from "@lib/replay";
import { initUkReplayLog, recordUkWeek } from "./ukReplay";
import { mpActionCost, mpPlannedCost } from "@engine/mpBudget";

interface UkStore {
  game: UkGameState | null;
  history: UkGameState[]; // undo ring
  replay: ReplayLog | null; // compact per-turn timeline/replay log
  selectedRegionId: string | null;
  lastEventResult: { title: string; text: string } | null;

  newGame: (election: string, party: PartyId, seed?: string, difficulty?: Difficulty) => void;
  // Start a prebuilt (Campaign Editor) game. Always casual: never posts a score.
  startCustom: (game: UkGameState) => void;
  unload: () => void;
  reset: () => void;
  selectRegion: (id: string | null) => void;

  queueAction: (a: UkAction) => void;
  removeAction: (index: number) => void;
  clearActions: () => void;

  resolvePlayerEvent: (choiceId: string) => void;
  endTurn: () => void;
  undo: () => void;

  liveProjection: () => UkResult | null;
  previewProjection: () => UkResult | null;

  tryResumeAutosave: () => boolean;
}

const MAX_HISTORY = 12;
const AUTOSAVE_KEY = "campaign-uk-autosave";

function autosave(game: UkGameState) {
  try {
    localStorage.setItem(AUTOSAVE_KEY, JSON.stringify({ updatedAt: Date.now(), state: game }));
  } catch {
    /* private mode etc. */
  }
}

function loadAutosave(): UkGameState | null {
  try {
    const raw = localStorage.getItem(AUTOSAVE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { state?: UkGameState };
    if (!parsed.state?.regions || !parsed.state.playerParty) return null;
    // Backfill fields added after older saves.
    if (!parsed.state.lastRecap) parsed.state.lastRecap = [];
    return parsed.state;
  } catch {
    return null;
  }
}

export const useUkStore = create<UkStore>((set, get) => ({
  game: null,
  history: [],
  replay: null,
  selectedRegionId: null,
  lastEventResult: null,

  newGame: (election, party, seed, difficulty) => {
    const p = UK_PLAYABLE.includes(party) ? party : "lab";
    const game = createUkGame({ election, playerParty: p, seed: seed ?? `uk-${Date.now()}`, difficulty });
    const mode: ReplayMode = typeof seed === "string" && seed.startsWith("daily") ? "daily" : "casual";
    autosave(game);
    set({
      game,
      history: [],
      replay: initUkReplayLog(game, mode),
      selectedRegionId: null,
      lastEventResult: null,
    });
  },

  startCustom: (game) => {
    autosave(game);
    set({
      game,
      history: [],
      replay: initUkReplayLog(game, "casual"),
      selectedRegionId: null,
      lastEventResult: null,
    });
  },

  unload: () => set({ game: null, history: [], replay: null, selectedRegionId: null, lastEventResult: null }),

  reset: () => {
    try { localStorage.removeItem(AUTOSAVE_KEY); } catch { /* */ }
    set({ game: null, history: [], replay: null, selectedRegionId: null, lastEventResult: null });
  },

  selectRegion: (id) => set({ selectedRegionId: id }),

  queueAction: (a) => {
    const game = get().game;
    if (!game) return;
    const res = game.resources[game.playerParty];
    if (game.queuedActions.length >= res.maxActions) return;
    if (mpPlannedCost(game.queuedActions) + mpActionCost(a) > res.funds + 1e-9) return;
    const next = { ...game, queuedActions: [...game.queuedActions, a] };
    autosave(next);
    set({ game: next });
  },

  removeAction: (index) => {
    const game = get().game;
    if (!game) return;
    const next = { ...game, queuedActions: game.queuedActions.filter((_, i) => i !== index) };
    autosave(next);
    set({ game: next });
  },

  clearActions: () => {
    const game = get().game;
    if (!game) return;
    const next = { ...game, queuedActions: [] };
    autosave(next);
    set({ game: next });
  },

  resolvePlayerEvent: (choiceId) => {
    const game = get().game;
    if (!game?.pendingEvent) return;
    const next = resolveUkPlayerEvent(game, choiceId);
    autosave(next);
    set({
      game: next,
      lastEventResult: next.lastRecap[0]
        ? { title: next.lastRecap[0].label, text: next.lastRecap[0].detail }
        : null,
    });
  },

  endTurn: () => {
    const { game, history } = get();
    if (!game || game.pendingEvent) return;
    const next = ukAdvanceTurn(game);
    const priorLog = get().replay;
    const replay = priorLog ? recordUkWeek(priorLog, game, next) : priorLog;
    autosave(next);
    set({
      game: next,
      replay,
      history: [...history, game].slice(-MAX_HISTORY),
      selectedRegionId: null,
      lastEventResult: null,
    });
  },

  undo: () => {
    const { history } = get();
    if (history.length === 0) return;
    const prev = history[history.length - 1];
    const priorLog = get().replay;
    const replay = priorLog ? truncateToTurn(priorLog, prev.turn) : priorLog;
    autosave(prev);
    set({ game: prev, replay, history: history.slice(0, -1) });
  },

  liveProjection: () => {
    const game = get().game;
    return game ? projectUk(game) : null;
  },

  previewProjection: () => {
    const game = get().game;
    return game ? projectUkPreview(game) : null;
  },

  tryResumeAutosave: () => {
    const saved = loadAutosave();
    if (!saved) return false;
    set({ game: saved, history: [], selectedRegionId: null, lastEventResult: null });
    return true;
  },
}));
