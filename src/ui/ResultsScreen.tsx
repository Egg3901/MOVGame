import { useEffect, useMemo, useState } from "react";
import { useGameStore } from "@store/gameStore";
import { useAuthStore } from "@store/authStore";
import { GRID, SPLIT_UNITS, GRID_COLS, GRID_ROWS } from "@content/mapLayout";
import { STATE_PATHS } from "@content/statePaths";
import { isCustomScenarioId } from "@content/customScenario";
import { SCENARIOS } from "@content/scenarios";
import { US_NEXT_SCENARIO } from "@content/nextScenario";
import type { Projection } from "@engine/index";
import { computeScoreFromFacts, usOpponentEvMargin, usScoreFacts } from "@engine/scoring";
import { checkAchievements, recordLocalAchievements } from "@engine/achievements";
import { api } from "@lib/api";
import { sfx } from "@lib/sfx";
import { EvBar } from "./EvBar";
import { StatsScreen } from "./StatsScreen";
import { WhyReport } from "./WhyReport";
import { TimelineView } from "./TimelineView";
import { pct, votes } from "./format";
import { Trophy, Lock } from "lucide-react";
import { ElectionNight, hasSeenReveal, revealSupported } from "./electionNight/ElectionNight";
import { ElectionNightShell } from "./electionNight/ElectionNightShell";
import { StateCallGrid } from "./electionNight/StateCallGrid";
import { usReveal } from "./electionNight/adapters";
import { DailyResultPanel } from "./DailyResultPanel";
import { dailyAssignment, utcDateString } from "@lib/daily";
import type { CandidateId } from "@engine/types";

export function ResultsScreen() {
  const game = useGameStore((s) => s.game)!;
  const newGame = useGameStore((s) => s.newGame);
  const difficulty = useGameStore((s) => s.difficulty);
  const result = game.result!;
  const cands = game.candidates;
  const dem = cands.dem.shortName;
  const rep = cands.rep.shortName;

  const byState = useMemo(() => new Map(result.stateResults.map((s) => [s.stateId, s])), [result]);
  const evOf = (id: string) => game.states.find((s) => s.id === id)?.electoralVotes ?? 0;

  // Call order: safe states first, the closest battlegrounds last — for drama.
  const calls = useMemo(
    () => [...result.stateResults].sort((a, b) => Math.abs(b.demShare - 0.5) - Math.abs(a.demShare - 0.5)),
    [result],
  );

  // The staged Election Night reveal (below) plays first, so the map itself
  // mounts fully called — no second reveal pass.
  const [revealed, setRevealed] = useState(calls.length);
  const [mapMode, setMapMode] = useState<"geo" | "square">("geo");
  const [statsOpen, setStatsOpen] = useState(false);
  const [timelineOpen, setTimelineOpen] = useState(false);
  const replay = useGameStore((s) => s.replay);
  const done = revealed >= calls.length;

  useEffect(() => {
    if (done) return;
    // Slow down for the final, decisive calls.
    const delay = revealed > calls.length - 8 ? 280 : 70;
    const t = setTimeout(() => setRevealed((r) => Math.min(calls.length, r + 1)), delay);
    return () => clearTimeout(t);
  }, [revealed, calls.length, done]);

  const calledIds = useMemo(() => new Set(calls.slice(0, revealed).map((c) => c.stateId)), [calls, revealed]);
  let demEV = 0;
  let repEV = 0;
  for (const c of calls.slice(0, revealed)) {
    if (c.winner === "dem") demEV += evOf(c.stateId);
    else repEV += evOf(c.stateId);
  }
  const tossupEV = Math.max(0, 538 - demEV - repEV);
  const lastCalled = revealed > 0 ? game.states.find((s) => s.id === calls[revealed - 1].stateId) : null;

  const winnerName = result.winner === "tie" ? "No one: 269-269" : cands[result.winner].name;
  const playerWon = result.winner === game.playerCandidate;

  // The win/lose sting plays once the state-by-state calls have all landed.
  useEffect(() => {
    if (!done) return;
    if (result.winner === "tie") return;
    if (playerWon) sfx.win(); else sfx.lose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [done]);
  const winnerColor =
    result.winner === "tie" ? "var(--gold)" : cands[result.winner].color;

  // Margins are always Dem − Rep so the swingometer's left/right labels match.
  const popMarginPts = (result.popularShare.dem - result.popularShare.rep) * 100;

  const canPlay = useAuthStore((s) => s.canPlay);
  const openModal = useAuthStore((s) => s.openModal);

  // ── Election Night reveal — plays instead of the results until done ──
  const reveal = useMemo(() => usReveal(game), [game]);
  const [nightDone, setNightDone] = useState(() => !revealSupported() || hasSeenReveal(reveal.storageKey ?? ""));

  const tile = (id: string, size?: number) => {
    const sr = byState.get(id);
    const st = game.states.find((s) => s.id === id);
    if (!sr || !st) return null;
    const called = calledIds.has(id);
    return (
      <div
        className={`tile${called ? " called" : ""}`}
        key={id}
        style={{ background: called ? cands[sr.winner].color : "var(--navy-600)", opacity: called ? 1 : 0.45, width: size }}
        title={`${st.name}: ${called ? (sr.winner === "dem" ? dem : rep) + " +" + sr.margin.toFixed(1) : "·"}`}
      >
        <span>{st.abbr}</span>
        <span className="ev">{st.electoralVotes}</span>
      </div>
    );
  };

  if (!nightDone) {
    return <ElectionNight {...reveal} onDone={() => setNightDone(true)} />;
  }

  const next = US_NEXT_SCENARIO[game.scenarioId ?? "2020"];
  const nextMeta = next ? SCENARIOS[next.id] : null;
  const unlocked = next ? canPlay(`us-${next.id}`) : false;
  const winnerEv =
    result.winner === "tie" ? 269 : result.electoralVotes[result.winner];
  const winnerPopShare =
    result.winner === "tie"
      ? result.popularShare[game.playerCandidate]
      : result.popularShare[result.winner];

  return (
    <div className="center">
      <div className="results scroll">
        <ElectionNightShell
          eyebrow={
            !done
              ? lastCalled
                ? `CALLING ${lastCalled.name.toUpperCase()}…`
                : "POLLS CLOSING…"
              : result.winner === "tie"
                ? "CONTINGENT ELECTION"
                : "PROJECTED WINNER"
          }
          headline={!done ? `${demEV}-${repEV}` : winnerName}
          headlineColor={!done ? "var(--text-strong)" : winnerColor}
          subhead={
            !done
              ? `270 to win · ${tossupEV} EV still out`
              : result.winner === "tie"
                ? "Neither ticket reached 270. The House decides."
                : playerWon
                  ? "You won the campaign."
                  : "You came up short."
          }
          subheadTone={!done ? "neutral" : result.winner === "tie" ? "neutral" : playerWon ? "win" : "loss"}
          counterValue={done ? winnerEv : Math.max(demEV, repEV)}
          counterLabel={done ? (result.winner === "tie" ? "EV apiece" : "Electoral votes") : "Leading EV"}
          counterColor={done && result.winner !== "tie" ? cands[result.winner].color : undefined}
          secondaryCounter={
            done
              ? {
                  value: winnerPopShare * 100,
                  label: "Popular vote",
                  decimals: 1,
                  suffix: "%",
                }
              : undefined
          }
          swing={{
            value: done ? popMarginPts : demEV - repEV,
            maxAbs: done ? Math.max(8, Math.abs(popMarginPts) * 1.4) : 100,
            leftLabel: dem,
            rightLabel: rep,
            leftColor: cands.dem.color,
            rightColor: cands.rep.color,
            unitLabel: done ? "pts" : "EV",
            caption: done ? "Final popular-vote margin" : "Running EV margin",
          }}
          nextScenario={
            done && next && nextMeta
              ? {
                  title: nextMeta.label,
                  blurb: `${next.blurb}${!unlocked ? " · part of the United States bundle" : ""}`,
                  unlocked,
                  ctaLabel: `Play ${nextMeta.year} →`,
                  lockLabel: `🔒 Unlock ${nextMeta.year}`,
                  onPlay: () =>
                    newGame({
                      seed: String(Date.now()),
                      playerCandidate: game.playerCandidate,
                      scenario: next.id,
                      eventMode: game.eventMode,
                      difficulty,
                    }),
                  onUnlock: () => openModal("activate", `us-${next.id}`),
                  mapPreview: <UsMiniMapPreview byState={byState} cands={cands} />,
                }
              : null
          }
          footer={
            done ? (
              <>
                <ScoreAndAchievements />
                <WhyReport log={replay} won={playerWon} />
                <div className="card">
                  <h3>Post-Mortem: What Moved the Needle</h3>
                  <p className="muted small">Your biggest self-caused swings across the campaign.</p>
                  {result.postMortem.length === 0 && <p className="muted small">A hands-off campaign. History took its course.</p>}
                  {result.postMortem.map((c, i) => (
                    <div className="recapitem" key={i}>
                      <div>
                        <div>{c.cause}</div>
                        <div className="muted small">Week {c.turn + 1}{c.stateId ? ` · ${c.stateId}` : ""}</div>
                      </div>
                      <span className={`delta ${c.marginDelta >= 0 ? "up" : "down"}`}>
                        {c.marginDelta >= 0 ? "+" : ""}{c.marginDelta.toFixed(3)}
                      </span>
                    </div>
                  ))}
                </div>
                <div className="row" style={{ gap: 8, marginTop: 4 }}>
                  <button className="ghost" style={{ flex: "0 0 auto", padding: "12px 18px" }} onClick={() => setStatsOpen(true)}>
                    📊 Race Stats
                  </button>
                  {replay && (
                    <button className="ghost" style={{ flex: "0 0 auto", padding: "12px 18px" }} onClick={() => setTimelineOpen(true)}>
                      Timeline
                    </button>
                  )}
                  <button
                    className="primary"
                    style={{ flex: 1, padding: 12 }}
                    onClick={() =>
                      newGame({
                        seed: String(Date.now()),
                        playerCandidate: game.playerCandidate,
                        scenario: game.scenarioId,
                        customScenario: game.customScenario,
                        eventMode: game.eventMode,
                        difficulty,
                      })
                    }
                  >
                    Run it back: New Campaign →
                  </button>
                </div>
              </>
            ) : null
          }
        >
          <div style={{ marginBottom: 4 }}>
            <EvBar projection={{ ev: { dem: demEV, rep: repEV }, tossupEv: tossupEV, contests: [] } as unknown as Projection} />
          </div>

          {done && (
            <p className="muted small" style={{ textAlign: "center", margin: "0 0 4px" }}>
              {dem} {result.electoralVotes.dem} · {rep} {result.electoralVotes.rep} · Popular vote:{" "}
              {dem} {pct(result.popularShare.dem)} ({votes(result.popularVote.dem)}) · {rep}{" "}
              {pct(result.popularShare.rep)} ({votes(result.popularVote.rep)})
            </p>
          )}

          <div className="card">
            <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
              <h3 style={{ margin: 0 }}>{done ? "Final Map" : "Election Night"}</h3>
              <div className="row" style={{ gap: 6 }}>
                <button className={`ghost small${mapMode === "geo" ? " active" : ""}`} onClick={() => setMapMode("geo")}>Geo</button>
                <button className={`ghost small${mapMode === "square" ? " active" : ""}`} onClick={() => setMapMode("square")}>Square</button>
                {!done && <button className="ghost small" onClick={() => setRevealed(calls.length)}>Skip ⏭</button>}
              </div>
            </div>

            {mapMode === "geo" ? (
              <svg viewBox="0 0 1000 650" className="geo-map" preserveAspectRatio="xMidYMid meet" style={{ maxWidth: 640, margin: "10px auto 0" }}>
                {[...GRID.map((t) => t.id), ...SPLIT_UNITS.map((g) => g.ids.find((id) => id.endsWith("-AL"))).filter(Boolean) as string[]].map((id) => {
                  const sr = byState.get(id);
                  const path = STATE_PATHS[id] ?? STATE_PATHS[id.split("-")[0]];
                  if (!sr || !path) return null;
                  const called = calledIds.has(id);
                  const st = game.states.find((s) => s.id === id);
                  return (
                    <path key={id} d={path}
                      fill={called ? cands[sr.winner].color : "#1b2740"}
                      stroke="#0b1120" strokeWidth={0.6}
                      style={{ transition: "fill 0.35s var(--ease)" }}>
                      <title>{st?.name}{called ? `: ${sr.winner === "dem" ? dem : rep} +${sr.margin.toFixed(1)}` : ""}</title>
                    </path>
                  );
                })}
              </svg>
            ) : (
              <>
                <div className="tilegrid" style={{ gridTemplateColumns: `repeat(${GRID_COLS}, 1fr)`, gridTemplateRows: `repeat(${GRID_ROWS}, 1fr)`, aspectRatio: `${GRID_COLS} / ${GRID_ROWS}`, maxWidth: 560, margin: "10px auto 0" }}>
                  {GRID.map((t) => (
                    <div key={t.id} style={{ gridColumn: t.col + 1, gridRow: t.row + 1 }}>{tile(t.id)}</div>
                  ))}
                </div>
                <div className="splitstrip" style={{ justifyContent: "center", marginTop: 10 }}>
                  {SPLIT_UNITS.map((g) => (
                    <div className="splitgroup" key={g.label}>
                      <span className="lab">{g.label}</span>
                      <div className="row">{g.ids.map((id) => tile(id, 34))}</div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>

          {done && <StateCallGrid game={game} />}
        </ElectionNightShell>
      </div>
      {statsOpen && <StatsScreen onClose={() => setStatsOpen(false)} />}
      {timelineOpen && replay && <TimelineView log={replay} onClose={() => setTimelineOpen(false)} />}
    </div>
  );
}

// Tiny cartogram for the "next scenario" card — reuses the final map colors.
function UsMiniMapPreview({
  byState,
  cands,
}: {
  byState: Map<string, { winner: "dem" | "rep" }>;
  cands: { dem: { color: string }; rep: { color: string } };
}) {
  const ids = ["CA", "TX", "FL", "NY", "PA", "IL", "OH", "GA", "NC", "MI"];
  return (
    <svg viewBox="0 0 1000 650" preserveAspectRatio="xMidYMid meet">
      {ids.map((id) => {
        const path = STATE_PATHS[id];
        const sr = byState.get(id);
        if (!path || !sr) return null;
        return <path key={id} d={path} fill={cands[sr.winner].color} stroke="#0b1120" strokeWidth={1} />;
      })}
    </svg>
  );
}

// ── Campaign score + achievements + leaderboard post ────────────────────────
function ScoreAndAchievements() {
  const game = useGameStore((s) => s.game)!;
  const newGame = useGameStore((s) => s.newGame);
  const difficulty = useGameStore((s) => s.difficulty);
  const user = useAuthStore((s) => s.user);
  const serverDown = useAuthStore((s) => s.serverDown);
  const openModal = useAuthStore((s) => s.openModal);
  const result = game.result!;
  const player = game.playerCandidate;
  const custom = isCustomScenarioId(game.scenarioId);
  const scenarioId = `us-${SCENARIOS[game.scenarioId ?? "2020"]?.year ?? game.scenarioId}`;

  const facts = useMemo(() => usScoreFacts(result, player, difficulty), [result, player, difficulty]);
  const evMargin = usOpponentEvMargin(result, player);
  const score = computeScoreFromFacts(facts);
  const earned = useMemo(
    () => custom ? [] : checkAchievements({ result, game, player, difficulty }),
    [result, game, player, difficulty, custom],
  );

  const [postState, setPostState] = useState<"idle" | "busy" | "done" | "error">("idle");
  const [postNote, setPostNote] = useState("");
  // Only the Standard (9-week) campaign length is comparable across players,
  // so Short/Long games are casual only and can't be posted to the leaderboard.
  const standardLength = game.totalTurns === 9 && !custom;

  // Persist achievements once per mount: server for accounts, local for guests.
  useEffect(() => {
    const ids = earned.map((a) => a.id);
    if (ids.length === 0) return;
    recordLocalAchievements(scenarioId, ids);
    if (user) void api.syncAchievements(scenarioId, ids).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const post = async () => {
    if (!standardLength) return;
    setPostState("busy");
    try {
      const out = await api.postScore({
        scenarioId,
        difficulty,
        score,
        facts,
        electoralVotes: result.electoralVotes,
        popularShare: result.popularShare,
        playerSide: player,
        evMargin,
        popularVoteMargin: facts.popularMargin,
        turnsPlayed: game.turn,
      });
      setPostState("done");
      setPostNote(out.posted ? `Posted. You're rank #${out.rank}.` : `Kept your personal best (${out.personalBest}). Rank #${out.rank}.`);
    } catch (e) {
      setPostState("error");
      setPostNote(e instanceof Error ? e.message : "Could not reach the server");
    }
  };

  const replayDaily = () => {
    const today = dailyAssignment(utcDateString());
    if (today.scenarioId !== scenarioId) return;
    const nativeId = SCENARIOS[game.scenarioId ?? "2020"] ? (game.scenarioId ?? "2020") : today.scenarioId.replace(/^us-/, "");
    newGame({
      seed: today.seed,
      playerCandidate: today.role as CandidateId,
      scenario: nativeId,
      eventMode: game.eventMode,
      difficulty,
    });
  };

  return (
    <>
      <DailyResultPanel
        gameSeed={game.seed}
        scenarioId={scenarioId}
        engine="us"
        won={result.winner === player}
        unitLine={`${result.electoralVotes[player]} EV`}
        score={score}
        facts={facts}
        evMargin={evMargin}
        popularVoteMargin={facts.popularMargin}
        onReplay={replayDaily}
      />
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10 }}>
          <div>
            <div className="tag muted small" style={{ color: "var(--gold)", letterSpacing: "2px" }}>
              <Trophy size={12} style={{ verticalAlign: "-2px" }} /> CAMPAIGN SCORE
            </div>
            <div style={{ fontSize: 38, fontWeight: 800, lineHeight: 1.1 }}>
              {score}<span className="muted" style={{ fontSize: 16, fontWeight: 600 }}> / 1000</span>
            </div>
            <div className="muted small">
              EV margin {evMargin >= 0 ? "+" : ""}{evMargin} · popular{" "}
              {facts.popularMargin >= 0 ? "+" : ""}{facts.popularMargin.toFixed(1)} pts · {difficulty} ×
              {difficulty === "easy" ? "0.7" : difficulty === "hard" ? "1.5" : "1.0"}
            </div>
          </div>
          <div style={{ textAlign: "right" }}>
            {!standardLength ? (
              <span className="muted small">{custom ? "Custom scenarios are casual only and do not post scores or earn ranked achievements" : "Short and Long games are casual only and do not post to the leaderboard"}</span>
            ) : user ? (
              <button className="primary" disabled={postState === "busy" || postState === "done" || serverDown} onClick={post}>
                {postState === "done" ? "Posted ✓" : postState === "busy" ? "Posting…" : serverDown ? "Offline" : "Post to Leaderboard"}
              </button>
            ) : serverDown ? (
              <span className="muted small">Play offline. Scores sync when the server is back</span>
            ) : (
              <button className="primary" onClick={() => openModal("login")}>
                <Lock size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />Log in to post
              </button>
            )}
            {postNote && <div className="muted small" style={{ marginTop: 6 }}>{postNote}</div>}
          </div>
        </div>
      </div>

      <div className="card">
        <h3>🏆 Achievements: {earned.length} earned</h3>
        {earned.length === 0 ? (
          <p className="muted small">None this run. Landslides, comebacks, and debate sweeps all leave trophies.</p>
        ) : (
          <div className="row" style={{ flexWrap: "wrap", gap: 8 }}>
            {earned.map((a) => (
              <span key={a.id} className="chip up" title={a.description} style={{ fontSize: 12, padding: "6px 10px" }}>
                {a.icon} {a.name}
              </span>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
