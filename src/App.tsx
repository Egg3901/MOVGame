import { useEffect, useRef, useState } from "react";
import { useGameStore } from "@store/gameStore";
import { useUkStore } from "@store/ukStore";
import { useCountryStore } from "@store/countryStore";
import { useAuthStore } from "@store/authStore";
import { SetupScreen } from "@ui/SetupScreen";
import { USMap } from "@ui/USMap";
import { StatePanel } from "@ui/StatePanel";
import { IntelPanel } from "@ui/IntelPanel";
import { ActionPanel } from "@ui/ActionPanel";
import { EvBar } from "@ui/EvBar";
import { NewsTicker } from "@ui/NewsTicker";
import { OnboardingCoach, COACH_DONE_KEY } from "@ui/coach/OnboardingCoach";
import { getScenario } from "@content/scenarios";
import { money, turnLabel } from "@ui/format";
import { lazy, Suspense } from "react";
import { LandingPage, type LandingDestination } from "@ui/LandingPage";
import { LegalPage } from "@ui/LegalPage";
import { dailyAssignment, utcDateString } from "@lib/daily";
import { SCENARIOS_BY_ID } from "@content/scenarioRegistry";
import { registerSavedCustomScenarios } from "@persistence/local";
import type { ResumeTarget } from "@persistence/resume";
import { Spinner } from "@ui/Skeleton";
import { hashSeed } from "@engine/rng";

// The UK and country shells carry their engines, content, and map geometry —
// they load on demand so the main bundle stays lean (the US game is the
// default/resume path and stays eager).
const UkApp = lazy(() => import("@ui/uk/UkApp").then((m) => ({ default: m.UkApp })));
const CountryApp = lazy(() => import("@ui/country/CountryApp").then((m) => ({ default: m.CountryApp })));
const LeaderboardScreen = lazy(() => import("@ui/LeaderboardScreen").then((m) => ({ default: m.LeaderboardScreen })));

// The results screen and every in-game overlay (recap/event/debate/guide/
// candidates/stats/settings/timeline) only ever mount after a real user
// action (a turn ends, a button is clicked). None of them are on the first
// paint / resume path, so they load on demand and keep the main chunk lean.
const ResultsScreen = lazy(() => import("@ui/ResultsScreen").then((m) => ({ default: m.ResultsScreen })));
const EventModal = lazy(() => import("@ui/EventModal").then((m) => ({ default: m.EventModal })));
const DebateScorecard = lazy(() => import("@ui/DebateScorecard").then((m) => ({ default: m.DebateScorecard })));
const RecapModal = lazy(() => import("@ui/RecapModal").then((m) => ({ default: m.RecapModal })));
const GuidePage = lazy(() => import("@ui/GuidePage").then((m) => ({ default: m.GuidePage })));
const CandidateScreen = lazy(() => import("@ui/CandidateScreen").then((m) => ({ default: m.CandidateScreen })));
const StatsScreen = lazy(() => import("@ui/StatsScreen").then((m) => ({ default: m.StatsScreen })));
const TimelineView = lazy(() => import("@ui/TimelineView").then((m) => ({ default: m.TimelineView })));
const SettingsModal = lazy(() => import("@ui/SettingsModal").then((m) => ({ default: m.SettingsModal })));
const AuthModals = lazy(() => import("@ui/auth/AuthModals").then((m) => ({ default: m.AuthModals })));

const LazyFallback = () => (
  <div className="app screen center"><div className="setup"><p className="sub"><Spinner /></p></div></div>
);

// Lightweight fallback for the overlay modals (recap/event/debate/guide/
// candidates/stats/settings/timeline) — matches their own overlay/modal
// shell so there's no full-screen flash while the chunk loads.
const ModalFallback = () => (
  <div className="overlay"><div className="modal"><p className="sub"><Spinner /></p></div></div>
);
import { armAudio, sfx } from "@lib/sfx";
import { useHotkeys } from "@lib/hotkeys";
import { Vote, X, Settings } from "lucide-react";
import { BRAND } from "./brand";

// True when the viewport is in the single-column mobile layout. Re-renders on
// viewport changes so State Detail can switch between inline and bottom-sheet.
function useIsMobile(): boolean {
  const hasMM = typeof window !== "undefined" && typeof window.matchMedia === "function";
  const [mobile, setMobile] = useState(() => hasMM && window.matchMedia("(max-width: 768px)").matches);
  useEffect(() => {
    if (!hasMM) return;
    const mq = window.matchMedia("(max-width: 768px)");
    const fn = () => setMobile(mq.matches);
    mq.addEventListener("change", fn);
    return () => mq.removeEventListener("change", fn);
  }, [hasMM]);
  return mobile;
}

function SaveControls() {
  const exportSave = useGameStore((s) => s.exportSave);
  const importSave = useGameStore((s) => s.importSave);
  const saveGame = useGameStore((s) => s.saveGame);
  const signedIn = useAuthStore((s) => !!s.user);
  const fileRef = useRef<HTMLInputElement>(null);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2200); };

  const doExport = () => {
    const json = exportSave();
    if (!json) return;
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `campaign-save-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
    flash("Save exported");
  };

  const doImport = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      try { importSave(String(reader.result)); flash("Save imported"); }
      catch { flash("Import failed — invalid file"); }
    };
    reader.readAsText(file);
  };

  return (
    <div className="row" style={{ gap: 6 }}>
      <button className="ghost small" onClick={async () => { await saveGame(`Save ${new Date().toLocaleString()}`); flash(signedIn ? "Saved and syncing" : "Saved locally"); }}>Save</button>
      <button className="ghost small" onClick={doExport}>Export</button>
      <button className="ghost small" onClick={() => fileRef.current?.click()}>Import</button>
      <input ref={fileRef} type="file" accept="application/json" style={{ display: "none" }}
        onChange={(e) => { const f = e.target.files?.[0]; if (f) doImport(f); e.target.value = ""; }} />
      <span className="muted small" title={signedIn
        ? "Your saves sync to your account across devices."
        : "Saves are kept in this browser only. Sign in to sync across devices."}>
        {signedIn ? "Synced" : "Local only"}
      </span>
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}

function GameScreen({ onHome }: { onHome: () => void }) {
  const game = useGameStore((s) => s.game)!;
  const endTurn = useGameStore((s) => s.endTurn);
  const undo = useGameStore((s) => s.undo);
  const canUndoWeek = useGameStore((s) => s.history.length > 0);
  const removeQueuedAction = useGameStore((s) => s.removeQueuedAction);
  const live = useGameStore((s) => s.liveProjection)();
  const [recapOpen, setRecapOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [candOpen, setCandOpen] = useState(false);
  const [statsOpen, setStatsOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [tourNonce, setTourNonce] = useState(0);
  const replayTutorial = () => {
    try {
      window.localStorage.removeItem(COACH_DONE_KEY);
    } catch {
      /* private mode etc. */
    }
    setSettingsOpen(false);
    setTourNonce((n) => n + 1);
  };
  const [timelineOpen, setTimelineOpen] = useState(false);
  const replay = useGameStore((s) => s.replay);

  const isMobile = useIsMobile();
  const selectedStateId = useGameStore((s) => s.selectedStateId);
  const selectState = useGameStore((s) => s.selectState);

  const debateOpen = useGameStore((s) => s.lastDebate) !== null;
  const player = game.playerCandidate;
  const res = game.resources[player];
  const cand = game.candidates[player];
  const plannedActions = game.queuedActions.length;
  const handleUndo = () => {
    if (plannedActions > 0) removeQueuedAction(plannedActions - 1);
    else undo();
  };
  const year = getScenario(game.scenarioId).year;
  const hasPendingEvent = game.pendingEvents.some((p) => p.forCandidate === player);

  // Plays a poll-tick cue when a fresh event modal opens for the player.
  const prevHasPendingEvent = useRef(hasPendingEvent);
  useEffect(() => {
    if (hasPendingEvent && !prevHasPendingEvent.current) sfx.eventPopup();
    prevHasPendingEvent.current = hasPendingEvent;
  }, [hasPendingEvent]);

  // Tracks the player's live electoral-vote count so a turn's outcome can
  // play a small up/down tick.
  const prevEv = useRef(live ? live.ev[player] : null);
  useEffect(() => {
    if (!live) return;
    prevEv.current = live.ev[player];
  }, [live, player]);

  const anyModalOpen = recapOpen || guideOpen || candOpen || statsOpen || settingsOpen || debateOpen || hasPendingEvent;

  const handleEndTurn = () => {
    if (hasPendingEvent) return;
    if (game.queuedActions.length === 0) {
      const ok = window.confirm("You have no actions queued this week. Unspent slots win nothing. End the week anyway?");
      if (!ok) return;
    }
    const before = prevEv.current;
    endTurn();
    sfx.turnAdvance();
    const g = useGameStore.getState().game;
    if (g && g.phase !== "result" && g.lastRecap.length > 0) setRecapOpen(true);
    const after = useGameStore.getState().liveProjection();
    if (before !== null && after) {
      const delta = after.ev[player] - before;
      if (delta > 0) sfx.pollUp();
      else if (delta < 0) sfx.pollDown();
    }
  };

  const closeTopModal = () => {
    if (settingsOpen) return setSettingsOpen(false);
    if (statsOpen) return setStatsOpen(false);
    if (candOpen) return setCandOpen(false);
    if (guideOpen) return setGuideOpen(false);
    if (recapOpen) return setRecapOpen(false);
    if (isMobile && selectedStateId) return selectState(null);
  };

  useHotkeys({
    onEndTurn: !anyModalOpen ? handleEndTurn : undefined,
    onEscape: anyModalOpen ? closeTopModal : undefined,
    onPickAction: !anyModalOpen ? (i) => window.dispatchEvent(new CustomEvent("hotkey-pick-action", { detail: i })) : undefined,
    onHelp: () => setSettingsOpen((v) => !v),
  });

  return (
    <div className="app screen" key="game">
      <div className="topbar">
        <div className="brand-lockup">
          <span className="mark"><Vote size={22} /></span>
          <div>
            <div className="brand-name">{BRAND.nameCaps}</div>
            <div className="brand-yr">CAMPAIGN {year}</div>
          </div>
        </div>
        <div className="turnchip">{turnLabel(game.turn, game.totalTurns)} · playing <strong style={{ color: cand.color }}>{cand.shortName}–{cand.runningMate.split(" ").slice(-1)[0]}</strong></div>
        {live && <EvBar projection={live} />}
        <div className="stat"><span className="v">{money(res.cash)}</span><span className="l">Cash</span></div>
        <div className="stat"><span className="v" style={{ color: plannedActions >= res.maxActions ? "var(--gold)" : undefined }}>{res.maxActions - plannedActions}/{res.maxActions}</span><span className="l">Actions left</span></div>
        <div className="stat" aria-label={`Momentum ${res.nationalMomentum.toFixed(0)}`}><span className="v">{res.nationalMomentum.toFixed(0)}</span><span className="l">Momentum</span></div>
        <SaveControls />
        <button className="ghost small" onClick={onHome}>Home</button>
        <button className="ghost small" onClick={() => setStatsOpen(true)}>Stats</button>
        {/* Read-only replay. Hidden mid-game on the daily so it can't be used to
            scout the shared board; always available once a game is over. */}
        {replay && replay.mode !== "daily" && (
          <button className="ghost small" onClick={() => setTimelineOpen(true)}>Timeline</button>
        )}
        <button className="ghost small" onClick={() => setCandOpen(true)}>Candidates</button>
        <button className="ghost small" onClick={() => setGuideOpen(true)}>Guide</button>
        <button className="ghost small" data-coach="settings" onClick={() => setSettingsOpen(true)} aria-label="Settings"><Settings size={16} /></button>
        <button onClick={handleUndo} disabled={!canUndoWeek && plannedActions === 0}>
          ↶ {plannedActions > 0 ? "Undo action" : "Undo week"}
        </button>
      </div>

      <NewsTicker />

      <div className="main">
        <div className="col">
          <USMap />
          <IntelPanel />
        </div>
        <div className="col">
          {/* On mobile, State Detail is a tap-to-open bottom sheet (below), not a
              persistent card that pushes the map and actions around. */}
          {!isMobile && <StatePanel />}
          <ActionPanel onEndWeek={handleEndTurn} endWeekDisabled={hasPendingEvent} />
        </div>
      </div>

      {isMobile && selectedStateId && (
        <div className="sheet-scrim" onClick={() => selectState(null)}>
          <div className="sheet" onClick={(e) => e.stopPropagation()}>
            <div className="sheet-handle" />
            <button className="sheet-close" onClick={() => selectState(null)} aria-label="Close"><X size={18} /></button>
            <StatePanel />
          </div>
        </div>
      )}

      <Suspense fallback={<ModalFallback />}>
        {recapOpen && <RecapModal onClose={() => setRecapOpen(false)} />}
        {!recapOpen && !debateOpen && hasPendingEvent && <EventModal />}
        {debateOpen && <DebateScorecard />}
        {guideOpen && <GuidePage onClose={() => setGuideOpen(false)} />}
        {candOpen && <CandidateScreen scenarioId={game.scenarioId} onClose={() => setCandOpen(false)} />}
        {statsOpen && <StatsScreen onClose={() => setStatsOpen(false)} />}
        {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} onReplayTutorial={replayTutorial} />}
        {timelineOpen && replay && <TimelineView log={replay} onClose={() => setTimelineOpen(false)} />}
      </Suspense>

      {/* First-run guided tour; self-gating (localStorage, turn 0, US only).
          key={tourNonce} forces a remount so "Replay tutorial" restarts it from step 1. */}
      <OnboardingCoach key={tourNonce} forceOpen={tourNonce > 0} />
    </div>
  );
}

// Where the app currently is: the landing scenario browser, one of the game
// shells (US / UK / a generic country), or the leaderboard. The optional
// initialSeed/initialParty prefill the setup screens (Daily Challenge).
type View =
  | { kind: "landing" }
  | { kind: "us"; scenarioId?: string; initialSeed?: string; initialParty?: string; setup?: boolean }
  | { kind: "uk"; electionId?: string; initialSeed?: string; initialParty?: string; setup?: boolean }
  | { kind: "country"; countryId: string; electionId?: string; initialSeed?: string; initialParty?: string; setup?: boolean }
  | { kind: "leaderboard" }
  | { kind: "legal"; tab?: "privacy" | "terms" };

function historyView(): View {
  const view = (window.history.state as { movView?: View } | null)?.movView;
  if (view && ["landing", "us", "uk", "country", "leaderboard", "legal"].includes(view.kind)) return view;
  return { kind: "landing" };
}

export function App() {
  const game = useGameStore((s) => s.game);
  const ukGame = useUkStore((s) => s.game);
  const countryGame = useCountryStore((s) => s.game);
  const refreshSaves = useGameStore((s) => s.refreshSaves);
  const [view, setView] = useState<View>(historyView);
  const [restoring, setRestoring] = useState(() => {
    const initial = historyView();
    if ("setup" in initial && initial.setup) return false;
    return (initial.kind === "us" && !useGameStore.getState().game)
      || (initial.kind === "uk" && !useUkStore.getState().game)
      || (initial.kind === "country" && !useCountryStore.getState().game);
  });
  useEffect(() => { void refreshSaves(); }, [refreshSaves]);
  useEffect(() => {
    const initial = historyView();
    if ("setup" in initial && initial.setup) {
      void registerSavedCustomScenarios();
      return;
    }
    const restore = async () => {
      await registerSavedCustomScenarios();
      if (initial.kind === "us" && !useGameStore.getState().game) await useGameStore.getState().loadGame("autosave");
      else if (initial.kind === "uk" && !useUkStore.getState().game) useUkStore.getState().tryResumeAutosave();
      else if (initial.kind === "country" && !useCountryStore.getState().game) useCountryStore.getState().tryResumeAutosave(initial.countryId);
      setRestoring(false);
    };
    void restore().catch(() => setRestoring(false));
  }, []);
  useEffect(() => {
    const onPop = () => {
      const next = historyView();
      setView(next);
      if ("setup" in next && next.setup) return;
      if (next.kind === "us" && !useGameStore.getState().game) {
        setRestoring(true);
        void useGameStore.getState().loadGame("autosave").finally(() => setRestoring(false));
      } else if (next.kind === "uk" && !useUkStore.getState().game) {
        useUkStore.getState().tryResumeAutosave();
      } else if (next.kind === "country" && !useCountryStore.getState().game) {
        useCountryStore.getState().tryResumeAutosave(next.countryId);
      }
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);
  useEffect(() => {
    if (!("setup" in view && view.setup)) return;
    const started = view.kind === "us" ? !!game : view.kind === "uk" ? !!ukGame : view.kind === "country" ? !!countryGame : false;
    if (!started) return;
    const active = { ...view, setup: false };
    window.history.replaceState({ ...window.history.state, movView: active }, "");
    setView(active);
  }, [view, game, ukGame, countryGame]);

  const navigate = (next: View) => {
    window.history.pushState({ ...window.history.state, movView: next }, "");
    setView(next);
  };

  // Browsers block audio until a real user gesture; arm the synth on the
  // first pointer press or key press anywhere in the app, then stop listening.
  useEffect(() => {
    const arm = () => { armAudio(); window.removeEventListener("pointerdown", arm); window.removeEventListener("keydown", arm); };
    window.addEventListener("pointerdown", arm, { once: true });
    window.addEventListener("keydown", arm, { once: true });
    return () => { window.removeEventListener("pointerdown", arm); window.removeEventListener("keydown", arm); };
  }, []);

  const go = (dest: LandingDestination) => {
    // The daily destination resolves locally (client and server share the
    // same deterministic assignment) and lands on the right engine's setup
    // with the day's seed + side prefilled.
    if (dest.kind === "daily") {
      const assignment = dailyAssignment(utcDateString());
      const meta = SCENARIOS_BY_ID[assignment.scenarioId];
      if (!meta) return;
      const dailySeed = hashSeed(assignment.seed);
      const us = useGameStore.getState().game;
      const uk = useUkStore.getState().game;
      const country = useCountryStore.getState().game;
      if (meta.engine === "us" && us?.seed === dailySeed && us.scenarioId === meta.nativeId && us.playerCandidate === assignment.role) {
        navigate({ kind: "us" });
        return;
      }
      if (meta.engine === "uk" && uk?.seed === dailySeed && uk.electionId === meta.nativeId && uk.playerParty === assignment.role) {
        navigate({ kind: "uk" });
        return;
      }
      if (meta.engine === "country" && country?.seed === dailySeed && country.countryId === meta.country && country.electionId === meta.nativeId && country.playerParty === assignment.role) {
        navigate({ kind: "country", countryId: meta.country });
        return;
      }
      if (meta.engine === "us" && !us) {
        void useGameStore.getState().loadGame("autosave").then(() => {
          const saved = useGameStore.getState().game;
          if (saved?.seed === dailySeed && saved.scenarioId === meta.nativeId && saved.playerCandidate === assignment.role) {
            navigate({ kind: "us" });
          } else {
            useGameStore.getState().unload();
            navigate({ kind: "us", scenarioId: meta.nativeId, initialSeed: assignment.seed, initialParty: assignment.role, setup: true });
          }
        }).catch(() => {
          useGameStore.getState().unload();
          navigate({ kind: "us", scenarioId: meta.nativeId, initialSeed: assignment.seed, initialParty: assignment.role, setup: true });
        });
        return;
      }
      if (meta.engine === "uk" && !uk && useUkStore.getState().tryResumeAutosave()) {
        const saved = useUkStore.getState().game;
        if (saved?.seed === dailySeed && saved.electionId === meta.nativeId && saved.playerParty === assignment.role) {
          navigate({ kind: "uk" });
          return;
        }
      }
      if (meta.engine === "country" && !country && useCountryStore.getState().tryResumeAutosave(meta.country)) {
        const saved = useCountryStore.getState().game;
        if (saved?.seed === dailySeed && saved.electionId === meta.nativeId && saved.playerParty === assignment.role) {
          navigate({ kind: "country", countryId: meta.country });
          return;
        }
      }
      // Played marker is set on results finish (DailyResultPanel), not on click.
      const prefill = { initialSeed: assignment.seed, initialParty: assignment.role };
      if (meta.engine === "us") { useGameStore.getState().unload(); navigate({ kind: "us", scenarioId: meta.nativeId, ...prefill, setup: true }); }
      else if (meta.engine === "uk") { useUkStore.getState().unload(); navigate({ kind: "uk", electionId: meta.nativeId, ...prefill, setup: true }); }
      else { useCountryStore.getState().unload(); navigate({ kind: "country", countryId: meta.country, electionId: meta.nativeId, ...prefill, setup: true }); }
      return;
    }
    if (dest.kind === "us") useGameStore.getState().unload();
    else if (dest.kind === "uk") useUkStore.getState().unload();
    else if (dest.kind === "country") useCountryStore.getState().unload();
    navigate({ ...dest, setup: dest.kind === "us" || dest.kind === "uk" || dest.kind === "country" } as View);
  };
  const home = () => { navigate({ kind: "landing" }); void refreshSaves(); };
  const resume = async (target: ResumeTarget) => {
    if (target.kind === "us") {
      if (target.saveId !== "autosave" || !useGameStore.getState().game) {
        await useGameStore.getState().loadGame(target.saveId);
      }
      if (useGameStore.getState().game) navigate({ kind: "us" });
    } else if (target.kind === "uk") {
      if (useUkStore.getState().tryResumeAutosave()) navigate({ kind: "uk" });
    } else if (useCountryStore.getState().tryResumeAutosave(target.countryId)) {
      navigate({ kind: "country", countryId: target.countryId });
    }
  };

  // Everything renders above the shared auth/paywall modals.
  const withModals = (node: React.ReactNode) => (
    <>
      {node}
      <Suspense fallback={null}>
        <AuthModals />
      </Suspense>
    </>
  );

  // A live U.S. game (or a resumed autosave) takes over the screen.
  if (restoring) return withModals(<LazyFallback />);
  if (view.kind === "us") {
    if (!game) {
      return withModals(
        <div className="app screen" key="setup">
          <SetupScreen
            initialScenarioId={view.kind === "us" ? view.scenarioId : undefined}
            initialSeed={view.kind === "us" ? view.initialSeed : undefined}
            initialParty={view.kind === "us" ? view.initialParty : undefined}
            onExit={home}
            onLaunch={(target) =>
              target.kind === "uk"
                ? navigate({ kind: "uk", setup: true })
                : navigate({ kind: "country", countryId: target.countryId, setup: true })
            }
          />
        </div>,
      );
    }
    if (game.phase === "result") return withModals(<div className="app screen" key="result"><Suspense fallback={<LazyFallback />}><ResultsScreen /></Suspense></div>);
    return withModals(<GameScreen onHome={home} />);
  }

  if (view.kind === "uk") {
    return withModals(<Suspense fallback={<LazyFallback />}><UkApp onExit={home} initialElection={view.electionId} initialSeed={view.initialSeed} initialParty={view.initialParty} /></Suspense>);
  }

  if (view.kind === "country") {
    return withModals(<Suspense fallback={<LazyFallback />}><CountryApp countryId={view.countryId} onExit={home} initialElection={view.electionId} initialSeed={view.initialSeed} initialParty={view.initialParty} /></Suspense>);
  }

  if (view.kind === "leaderboard") {
    return withModals(<Suspense fallback={<LazyFallback />}><LeaderboardScreen onBack={home} /></Suspense>);
  }

  if (view.kind === "legal") {
    return withModals(<LegalPage initialTab={view.tab} onBack={home} />);
  }

  return withModals(<LandingPage onGo={go} onResume={(target) => { void resume(target); }} />);
}
