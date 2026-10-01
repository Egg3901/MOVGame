import { create } from "zustand";
import { registerCampaignCustomScenario } from "@content/customScenario";
import {
  createGame,
  beginGame,
  resolveEvent,
  resolveDebate,
  applyAction,
  projectElection,
  createRng,
  type GameState,
  type CampaignAction,
  type DebateResult,
  type NewGameOptions,
  type Projection,
  type CandidateId,
  orderedPlan,
  planBonusMultiplier,
  planBonusesForAction,
} from "@engine/index";
import { EVENTS_BY_ID } from "@content/events";
import { advanceCampaignWeek } from "@engine/campaignWeek";
import { localProvider } from "@persistence/local";
import { remoteProvider } from "@persistence/remote";
import type { SaveMeta, SaveRecord } from "@persistence/types";
import type { ReplayLog, ReplayMode } from "@lib/replay";
import { truncateToTurn } from "@lib/replay";
import { initUsReplayLog, recordUsWeek, syncUsReplayLog } from "./usReplay";
import { canQueueAction } from "@engine/actionBudget";
import { loadUsSessionAutosave, removeUsSessionAutosave, saveUsSessionAutosave } from "@persistence/resume";

const AUTOSAVE_ID = "autosave";
const UNDO_DEPTH = 12;

export type Difficulty = "easy" | "normal" | "hard";

interface EventResult {
  title: string;
  text: string;
}

interface GameStore {
  game: GameState | null;
  history: GameState[]; // snapshot ring buffer (undo)
  replay: ReplayLog | null; // compact per-turn timeline/replay log
  difficulty: Difficulty;
  selectedStateId: string | null;
  lastEventResult: EventResult | null;
  lastDebate: DebateResult | null;
  saves: SaveMeta[];

  newGame: (opts: NewGameOptions & { difficulty?: Difficulty }) => void;
  unload: () => void;
  selectState: (id: string | null) => void;
  setDifficulty: (d: Difficulty) => void;

  queueAction: (action: CampaignAction) => void;
  removeQueuedAction: (index: number) => void;
  clearQueue: () => void;

  resolvePlayerEvent: (eventId: string, choiceId: string) => void;
  dismissDebate: () => void;
  endTurn: () => void;
  undo: () => void;

  // Projection of the map *if the queued actions were resolved now* (preview).
  previewProjection: () => Projection | null;
  liveProjection: () => Projection | null;

  refreshSaves: () => Promise<void>;
  saveGame: (name: string) => Promise<void>;
  loadGame: (id: string) => Promise<void>;
  deleteSave: (id: string) => Promise<void>;
  exportSave: () => string | null;
  importSave: (json: string) => void;
}

// Merge local and remote save lists by id, newest updatedAt wins, sorted newest
// first. Local-only and remote-only ids both survive; a shared id keeps whichever
// side was touched last. Used by refreshSaves so cross-device saves appear.
function mergeSaveMetas(local: SaveMeta[], remote: SaveMeta[]): SaveMeta[] {
  const byId = new Map<string, SaveMeta>();
  for (const m of [...local, ...remote]) {
    const prev = byId.get(m.id);
    if (!prev || m.updatedAt > prev.updatedAt) byId.set(m.id, m);
  }
  return [...byId.values()].sort((a, b) => b.updatedAt - a.updatedAt);
}

let localAutosaveQueue: Promise<void> = Promise.resolve();

function autosave(game: GameState) {
  const record: SaveRecord = {
    id: AUTOSAVE_ID,
    name: "Autosave",
    updatedAt: Date.now(),
    turn: game.turn,
    playerCandidate: game.playerCandidate,
    state: structuredClone(game),
  };
  // Synchronous last-known plan closes the refresh window before IndexedDB commits.
  saveUsSessionAutosave(record);
  // Serialize writes so rapid plan edits cannot let an older save land last.
  localAutosaveQueue = localAutosaveQueue.then(() => localProvider.save(record)).catch(() => {});
  // Mirror to the cloud when signed in. RemoteSyncProvider swallows failures,
  // so this never affects offline play.
  void remoteProvider.save(record);
}

// Persist the replay log next to the autosave so a resumed game (or a results
// screen reached after reload) still has its full timeline. Fire-and-forget.
function autosaveReplay(log: ReplayLog | null) {
  if (!log) return;
  const rec = { id: AUTOSAVE_ID, updatedAt: Date.now(), log };
  void localProvider.saveReplay(rec).catch(() => {});
  void remoteProvider.saveReplay(rec);
}

// Bring a loaded replay log back in step with the game it belongs to. Two
// cases:
//   - No log at all (an old save from before the replay log existed, or a
//     save/import path that does not carry one). Start a brand new log seeded
//     from the game's current turn, so the Timeline and post-game WhyReport
//     have something to show from this point forward instead of crashing on
//     a null log. We default to "casual" mode here: GameState does not carry
//     a "this was a daily challenge" flag, and a reseeded log for an old save
//     is the rare case, so showing the (otherwise-gated) scrubber is a safe
//     degrade rather than a real leak.
//   - A log whose last snapshot is ahead of the loaded game's turn (should not
//     normally happen, but can if a save and its log fall out of sync).
//     Truncate it back so the timeline never shows a turn the loaded game
//     has not reached yet.
function resumeReplayLog(game: GameState, log: ReplayLog | null): ReplayLog {
  if (!log) return initUsReplayLog(game, "casual");
  const aheadOfGame = log.snapshots.some((s) => s.turn > game.turn);
  return aheadOfGame ? truncateToTurn(log, game.turn) : syncUsReplayLog(log, game);
}

// Applies the queued player actions to a throwaway clone so the UI can preview
// the resulting map without committing the turn.
function projectWithQueue(game: GameState): Projection {
  const clone: GameState = structuredClone(game);
  const rng = createRng(`preview:${game.seed}:${game.turn}`);
  const plan = orderedPlan(clone.queuedActions);
  const completed: CampaignAction[] = [];
  for (const action of plan) {
    if (action.candidate !== clone.playerCandidate) continue;
    const bonuses = planBonusesForAction(action, completed);
    const causeCount = clone.causes.length;
    applyAction(clone, action, rng, planBonusMultiplier(bonuses), bonuses);
    if (clone.causes.length > causeCount) completed.push(action);
  }
  return projectElection(clone);
}

export const useGameStore = create<GameStore>((set, get) => ({
  game: null,
  history: [],
  replay: null,
  difficulty: "normal",
  selectedStateId: null,
  lastEventResult: null,
  lastDebate: null,
  saves: [],

  newGame: (opts) => {
    const difficulty = opts.difficulty ?? "normal";
    // Daily-challenge games are gated: the live scrubber is hidden mid-game so
    // it can't be used to scout the shared board. The daily seed is the string
    // "daily-<date>" before it is hashed into GameState.seed.
    const mode: ReplayMode =
      typeof opts.seed === "string" && opts.seed.startsWith("daily") ? "daily" : "casual";
    // The Daily Challenge is the same race for everyone, scored and compared
    // head to head, so its length can't be a player choice: always the
    // standard 9-week campaign, no matter what a casual game's setup screen
    // passed through.
    const fresh = beginGame(createGame(mode === "daily" ? { ...opts, totalTurns: 9 } : opts));
    const replay = initUsReplayLog(fresh, mode);
    autosave(fresh);
    autosaveReplay(replay);
    set({
      game: fresh,
      history: [],
      replay,
      difficulty,
      selectedStateId: null,
      lastEventResult: null,
      lastDebate: null,
    });
  },

  unload: () => set({ game: null, history: [], replay: null, selectedStateId: null, lastEventResult: null, lastDebate: null }),

  selectState: (id) => set({ selectedStateId: id }),
  setDifficulty: (d) => set({ difficulty: d }),

  queueAction: (action) => {
    const game = get().game;
    if (!game || !canQueueAction(game, action)) return;
    const next = structuredClone(game);
    next.queuedActions.push(action);
    autosave(next);
    set({ game: next });
  },

  removeQueuedAction: (index) => {
    const game = get().game;
    if (!game) return;
    const next = structuredClone(game);
    next.queuedActions.splice(index, 1);
    autosave(next);
    set({ game: next });
  },

  clearQueue: () => {
    const game = get().game;
    if (!game) return;
    const next = structuredClone(game);
    next.queuedActions = [];
    autosave(next);
    set({ game: next });
  },

  resolvePlayerEvent: (eventId, choiceId) => {
    const game = get().game;
    if (!game) return;
    const next = structuredClone(game);
    const event = EVENTS_BY_ID[eventId];
    if (event?.isDebate) {
      // Debate night: resolve both tickets at once, score it, surface the card.
      const debate = resolveDebate(next, event, { [next.playerCandidate]: choiceId });
      autosave(next);
      set({
        game: next,
        lastDebate: debate,
        lastEventResult: { title: event.title, text: debate.resultText[next.playerCandidate] },
      });
      return;
    }
    const result = resolveEvent(next, eventId, choiceId, next.playerCandidate);
    autosave(next);
    set({
      game: next,
      lastEventResult: result
        ? { title: event?.title ?? eventId, text: result.resultText }
        : get().lastEventResult,
    });
  },
  dismissDebate: () => set({ lastDebate: null }),

  endTurn: () => {
    const game = get().game;
    if (!game || game.phase === "result") return;
    const history = [...get().history, game].slice(-UNDO_DEPTH);
    const next = advanceCampaignWeek(game, get().difficulty);

    // Record the completed week into the compact replay log.
    const priorLog = get().replay;
    const replay = priorLog ? recordUsWeek(priorLog, game, next) : priorLog;

    autosave(next);
    autosaveReplay(replay);
    set({ game: next, history, replay, lastEventResult: null, lastDebate: null });
  },

  undo: () => {
    const history = [...get().history];
    const prev = history.pop();
    if (!prev) return;
    // Keep the replay log in step with the rewind so the timeline never shows a
    // week that has been undone.
    const priorLog = get().replay;
    const replay = priorLog ? truncateToTurn(priorLog, prev.turn) : priorLog;
    autosave(prev);
    autosaveReplay(replay);
    set({ game: prev, history, replay });
  },

  previewProjection: () => {
    const game = get().game;
    if (!game) return null;
    return projectWithQueue(game);
  },

  liveProjection: () => {
    const game = get().game;
    if (!game) return null;
    return projectElection(game);
  },

  refreshSaves: async () => {
    await localAutosaveQueue;
    // Local-first: Dexie is the source of truth and always works offline. When
    // signed in, fold in any cloud saves and let the newest updatedAt win per
    // id, so a save made on another device shows up here.
    const local = await localProvider.list();
    const remote = await remoteProvider.list(); // [] when logged out or offline
    set({ saves: mergeSaveMetas(local, remote) });
  },

  saveGame: async (name) => {
    const game = get().game;
    if (!game) return;
    const id = `save-${Date.now()}`;
    const updatedAt = Date.now();
    const record: SaveRecord = {
      id,
      name,
      updatedAt,
      turn: game.turn,
      playerCandidate: game.playerCandidate,
      state: game,
    };
    await localProvider.save(record);
    void remoteProvider.save(record); // fire-and-forget cloud mirror
    const log = get().replay;
    if (log) {
      const rec = { id, updatedAt, log };
      await localProvider.saveReplay(rec).catch(() => {});
      void remoteProvider.saveReplay(rec);
    }
    await get().refreshSaves();
  },

  loadGame: async (id) => {
    // Prefer the local copy; fall back to the cloud (e.g. a save made on
    // another device that has not been pulled into this browser yet).
    let record = await localProvider.load(id);
    if (id === AUTOSAVE_ID) {
      const session = loadUsSessionAutosave();
      if (session && (!record || session.updatedAt >= record.updatedAt)) record = session;
    }
    if (!record) record = await remoteProvider.load(id);
    if (!record) return;
    registerCampaignCustomScenario(record.state);
    let replayRec = await localProvider.loadReplay(id).catch(() => null);
    if (!replayRec) replayRec = await remoteProvider.loadReplay(id);
    const replay = resumeReplayLog(record.state, replayRec?.log ?? null);
    autosave(record.state);
    autosaveReplay(replay);
    set({ game: record.state, history: [], replay, lastEventResult: null });
  },

  deleteSave: async (id) => {
    await localProvider.remove(id);
    if (id === AUTOSAVE_ID) removeUsSessionAutosave();
    void remoteProvider.remove(id);
    await get().refreshSaves();
  },

  exportSave: () => {
    // Decision: export the raw GameState only, not the replay log. The log is
    // cheap to persist but re-deriving it during play (recordUsWeek runs off
    // GameState.timeline anyway) is not something export/import needs to
    // preserve exactly, and skipping it keeps the exported JSON exactly what
    // it already was for every existing save file (no new version field to
    // carry, nothing to migrate). Import regenerates a fresh log from the
    // imported turn instead (see importSave / resumeReplayLog), which is the
    // simpler correct option and degrades gracefully (Timeline still renders,
    // just starting from the import point).
    const game = get().game;
    if (!game) return null;
    return JSON.stringify(game, null, 2);
  },

  importSave: (json) => {
    try {
      const state = JSON.parse(json) as GameState;
      if (!state.states || !state.candidates) throw new Error("Invalid save");
      registerCampaignCustomScenario(state);
      // Exported saves are just the raw GameState JSON (see exportSave), so an
      // imported save never carries a replay log. Regenerating a fresh log
      // from the current turn is the simpler-and-correct option here (see
      // exportSave for why we did not also serialize the log): the Timeline
      // and WhyReport still render, just starting from the import point
      // rather than crashing or showing stale history.
      const replay = resumeReplayLog(state, null);
      autosave(state);
      set({ game: state, history: [], replay, lastEventResult: null });
    } catch (e) {
      console.error("Import failed", e);
      throw e;
    }
  },
}));

export type { CandidateId };
