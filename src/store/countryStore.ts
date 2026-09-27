import { create } from "zustand";
import {
  createCountryGame,
  countryAdvanceTurn,
  projectCountry,
  projectCountryPreview,
  resolveCountryPlayerEvent,
  playablePartiesIn,
  type CountryBundle,
  type CountryGameState,
  type CountryAction,
  type CountryResult,
  type Difficulty,
} from "@engine/countryGame";
import { COUNTRIES } from "@content/countries";
import type { PartyId } from "@engine/system";
import type { ReplayLog, ReplayMode } from "@lib/replay";
import { truncateToTurn } from "@lib/replay";
import { initCountryReplayLog, recordCountryWeek } from "./countryReplay";

interface CountryStore {
  country: CountryBundle | null;
  game: CountryGameState | null;
  history: CountryGameState[];
  replay: ReplayLog | null; // compact per-turn timeline/replay log
  selectedRegionId: string | null;
  lastEventResult: { title: string; text: string } | null;

  newGame: (countryId: string, election: string, party: PartyId, seed?: string, difficulty?: Difficulty) => void;
  // Start a prebuilt (Campaign Editor) game with its cloned bundle. Casual only.
  startCustom: (country: CountryBundle, game: CountryGameState) => void;
  unload: () => void;
  reset: () => void;
  selectRegion: (id: string | null) => void;

  queueAction: (a: CountryAction) => void;
  removeAction: (index: number) => void;
  clearActions: () => void;

  resolvePlayerEvent: (choiceId: string) => void;
  endTurn: () => void;
  undo: () => void;

  liveProjection: () => CountryResult | null;
  previewProjection: () => CountryResult | null;

  tryResumeAutosave: (countryId: string) => boolean;
}

const MAX_HISTORY = 12;
const autosaveKey = (countryId: string) => `campaign-country-autosave-${countryId}`;

function autosave(countryId: string, game: CountryGameState) {
  try {
    localStorage.setItem(autosaveKey(countryId), JSON.stringify({ updatedAt: Date.now(), state: game }));
  } catch {
    /* private mode etc. */
  }
}

function loadAutosave(countryId: string): CountryGameState | null {
  try {
    const raw = localStorage.getItem(autosaveKey(countryId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { state?: CountryGameState };
    if (!parsed.state?.regions || !parsed.state.playerParty) return null;
    if (!parsed.state.lastRecap) parsed.state.lastRecap = [];
    return parsed.state;
  } catch {
    return null;
  }
}

export const useCountryStore = create<CountryStore>((set, get) => ({
  country: null,
  game: null,
  history: [],
  replay: null,
  selectedRegionId: null,
  lastEventResult: null,

  newGame: (countryId, election, party, seed, difficulty) => {
    const country = COUNTRIES[countryId];
    if (!country) return;
    const playable = playablePartiesIn(country, election);
    const p = playable.includes(party) ? party : playable[0];
    const game = createCountryGame(country, { election, playerParty: p, seed: seed ?? `${countryId}-${Date.now()}`, difficulty });
    const mode: ReplayMode = typeof seed === "string" && seed.startsWith("daily") ? "daily" : "casual";
    autosave(countryId, game);
    set({
      country,
      game,
      history: [],
      replay: initCountryReplayLog(game, country, mode),
      selectedRegionId: null,
      lastEventResult: null,
    });
  },

  startCustom: (country, game) => {
    autosave(country.id, game);
    set({
      country,
      game,
      history: [],
      replay: initCountryReplayLog(game, country, "casual"),
      selectedRegionId: null,
      lastEventResult: null,
    });
  },

  unload: () => set({ country: null, game: null, history: [], replay: null, selectedRegionId: null, lastEventResult: null }),

  reset: () => {
    const country = get().country;
    if (country) {
      try { localStorage.removeItem(autosaveKey(country.id)); } catch { /* */ }
    }
    set({ country: null, game: null, history: [], replay: null, selectedRegionId: null, lastEventResult: null });
  },

  selectRegion: (id) => set({ selectedRegionId: id }),

  queueAction: (a) => {
    const game = get().game;
    if (!game) return;
    const res = game.resources[game.playerParty];
    if (game.queuedActions.length >= res.maxActions) return;
    const next = { ...game, queuedActions: [...game.queuedActions, a] };
    autosave(next.countryId, next);
    set({ game: next });
  },

  removeAction: (index) => {
    const game = get().game;
    if (!game) return;
    const next = { ...game, queuedActions: game.queuedActions.filter((_, i) => i !== index) };
    autosave(next.countryId, next);
    set({ game: next });
  },

  clearActions: () => {
    const game = get().game;
    if (!game) return;
    const next = { ...game, queuedActions: [] };
    autosave(next.countryId, next);
    set({ game: next });
  },

  resolvePlayerEvent: (choiceId) => {
    const { game, country } = get();
    if (!game?.pendingEvent || !country) return;
    const next = resolveCountryPlayerEvent(game, country, choiceId);
    autosave(country.id, next);
    set({
      game: next,
      lastEventResult: next.lastRecap[0]
        ? { title: next.lastRecap[0].label, text: next.lastRecap[0].detail }
        : null,
    });
  },

  endTurn: () => {
    const { game, country, history } = get();
    if (!game || !country || game.pendingEvent) return;
    const next = countryAdvanceTurn(game, country);
    const priorLog = get().replay;
    const replay = priorLog ? recordCountryWeek(priorLog, game, next, country) : priorLog;
    autosave(country.id, next);
    set({
      game: next,
      replay,
      history: [...history, game].slice(-MAX_HISTORY),
      selectedRegionId: null,
      lastEventResult: null,
    });
  },

  undo: () => {
    const { history, country } = get();
    if (history.length === 0) return;
    const prev = history[history.length - 1];
    const priorLog = get().replay;
    const replay = priorLog ? truncateToTurn(priorLog, prev.turn) : priorLog;
    if (country) autosave(country.id, prev);
    set({ game: prev, replay, history: history.slice(0, -1) });
  },

  liveProjection: () => {
    const { game, country } = get();
    return game && country ? projectCountry(game, country) : null;
  },

  previewProjection: () => {
    const { game, country } = get();
    return game && country ? projectCountryPreview(game, country) : null;
  },

  tryResumeAutosave: (countryId) => {
    const country = COUNTRIES[countryId];
    if (!country) return false;
    const saved = loadAutosave(countryId);
    if (!saved) return false;
    set({ country, game: saved, history: [], selectedRegionId: null, lastEventResult: null });
    return true;
  },
}));
