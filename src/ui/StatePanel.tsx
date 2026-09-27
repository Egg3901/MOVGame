import { useGameStore } from "@store/gameStore";
import { tallyContest, pollState, pollAverage, liveBlocDemShare } from "@engine/index";
import { BLOCS } from "@content/blocs";
import { shareToColor, leanLabel } from "./colors";
import { pct, votes } from "./format";
import { Sparkline } from "./Sparkline";
import { MapPin } from "lucide-react";

export function StatePanel() {
  const game = useGameStore((s) => s.game)!;
  const selectedId = useGameStore((s) => s.selectedStateId);
  const queueAction = useGameStore((s) => s.queueAction);
  const removeQueuedAction = useGameStore((s) => s.removeQueuedAction);
  const st = selectedId ? game.states.find((s) => s.id === selectedId) : null;

  if (!st) {
    return (
      <div className="card">
        <h3>State Detail</h3>
        <p className="muted small">Select a state on the map to inspect its demographic blocs, polling, and your operation there.</p>
      </div>
    );
  }

  // Aggregate units have no blocs of their own.
  if (st.blocs.length === 0) {
    return (
      <div className="card">
        <h3>{st.name} · {st.electoralVotes} EV</h3>
        <p className="muted small">At-large electors, awarded to the winner of this state's combined district vote.</p>
      </div>
    );
  }

  const tally = tallyContest(st);
  const player = game.playerCandidate;
  const dem = game.candidates.dem.shortName;
  const rep = game.candidates.rep.shortName;
  const polls = pollState(game, st.id);
  const avg = pollAverage(game, st.id);
  const meanMoe = polls.length ? polls.reduce((s, p) => s + p.marginOfError, 0) / polls.length : 0;

  // Per-state two-party trend across the weeks played so far (player's view).
  const playerColor = game.candidates[player].color;
  const trend = (game.timeline ?? [])
    .map((p) => p.demShareByState[st.id])
    .filter((v): v is number => v !== undefined)
    .map((v) => (player === "dem" ? v : 1 - v));
  const trendDelta = trend.length >= 2 ? (trend[trend.length - 1] - trend[0]) * 100 : null;

  // "Travel here" schedules (or cancels) a campaign rally in this state, dropped
  // on the next open day of the 7-day plan. Costs one action from the pool.
  const res = game.resources[player];
  const travelIdx = game.queuedActions.findIndex(
    (a) => a.type === "rally" && a.candidate === player && a.stateId === st.id,
  );
  const traveling = travelIdx >= 0;
  const dayCount = (d: number) => game.queuedActions.filter((a) => (a.day ?? 1) === d).length;
  const nextDay = [1, 2, 3, 4, 5, 6, 7].find((d) => dayCount(d) < 3);
  const canTravel = game.queuedActions.length < res.maxActions && nextDay !== undefined;
  const toggleTravel = () => {
    if (traveling) removeQueuedAction(travelIdx);
    else if (canTravel) queueAction({ type: "rally", candidate: player, stateId: st.id, day: nextDay });
  };

  // Everything you've scheduled in this state this week.
  const plannedHere = game.queuedActions.filter((a) => a.stateId === st.id && a.candidate === player);
  const plannedSummary = Object.entries(
    plannedHere.reduce<Record<string, number>>((m, a) => ({ ...m, [a.type]: (m[a.type] ?? 0) + 1 }), {}),
  ).map(([t, n]) => `${n}× ${t.replace("_", " ")}`).join(" · ");

  return (
    <div className="card scroll">
      <h3>{st.name} · {st.electoralVotes} EV</h3>
      <button
        className={traveling ? "primary" : "secondary"}
        onClick={toggleTravel}
        disabled={!traveling && !canTravel}
        style={{ width: "100%", margin: "2px 0 12px", display: "inline-flex", alignItems: "center", justifyContent: "center", gap: 6 }}
      >
        <MapPin size={14} />
        {traveling ? "Campaign stop planned. Tap to cancel" : canTravel ? "Travel here to hold a rally" : "No actions left this week"}
      </button>
      {plannedSummary && (
        <div className="kv"><span className="k">Planned here</span><span style={{ color: "var(--gold)" }}>{plannedSummary}</span></div>
      )}
      <div className="kv"><span className="k">Lean (true model)</span><span style={{ color: shareToColor(tally.demShare) }}>{leanLabel(tally.demShare)}</span></div>
      <div className="kv"><span className="k">{dem} / {rep}</span><span>{pct(tally.demShare)} / {pct(1 - tally.demShare)}</span></div>
      {avg !== null && <div className="kv"><span className="k">Poll average</span><span>{dem} {pct(avg)} <span className="moe">±{meanMoe.toFixed(1)}</span></span></div>}
      <div className="kv"><span className="k">Your ground game</span><span>{pct(st.groundGame[player], 0)}</span></div>
      <div className="kv"><span className="k">Momentum (D−R)</span><span>{st.momentum >= 0 ? "+" : ""}{st.momentum.toFixed(0)}</span></div>

      {trend.length >= 2 && (
        <div className="trend-row">
          <div className="trend-head">
            <span className="k">Your share: weekly trend</span>
            <span className={`trend-delta ${trendDelta! >= 0 ? "up" : "down"}`}>
              {trendDelta! >= 0 ? "▲" : "▼"} {Math.abs(trendDelta!).toFixed(1)} pts since Wk 1
            </span>
          </div>
          <Sparkline values={trend} color={playerColor} width={240} height={40} />
        </div>
      )}

      <h3 style={{ marginTop: 14 }}>Demographic Blocs</h3>
      {st.blocs.map((b) => {
        const arche = BLOCS[b.blocId];
        // Live campaign-adjusted share (baseline + ads/rallies/momentum), so the
        // bloc bars stay consistent with the state result above.
        const d = liveBlocDemShare(st, b);
        return (
          <div className="bloc" key={b.blocId}>
            <span className="name">{arche.name}</span>
            <span className="meta">{votes(b.size * b.turnoutPropensity * b.enthusiasm)} modeled votes · turnout {pct(b.turnoutPropensity, 0)}</span>
            <div className="suppbar">
              <div className="d" style={{ width: `${d * 100}%` }} />
              <div className="r" style={{ width: `${(1 - d) * 100}%` }} />
            </div>
            <span className="meta" style={{ gridColumn: "1 / -1" }}>
              {dem} {pct(d)} · {rep} {pct(1 - d)}{b.enthusiasm !== 1 ? ` · enthusiasm ${b.enthusiasm.toFixed(2)}×` : ""}
            </span>
          </div>
        );
      })}

      <h3 style={{ marginTop: 14 }}>Recent Polls</h3>
      {polls.map((p) => (
        <div className="pollrow" key={p.pollster}>
          <span>{p.pollster}</span>
          <span>{dem} {pct(p.demShare)} <span className="moe">±{p.marginOfError}</span></span>
        </div>
      ))}
    </div>
  );
}
