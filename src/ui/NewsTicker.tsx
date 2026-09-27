import { useEffect, useMemo, useState } from "react";
import { useGameStore } from "@store/gameStore";
import { nationalPoll, turnsUntilDebate, debateReadiness, type Projection } from "@engine/index";
import { getScenario } from "@content/scenarios";
import { leanLabel } from "./colors";
import { pct } from "./format";
import type { GameState } from "@engine/index";

// A broadcast-style lower-third news strip. Non-blocking flavor: it rotates
// through headlines built from the live race — opening framing on week 1, then
// the projection, the closest contest, and the week's biggest movers.
function buildHeadlines(game: GameState, live: Projection | null): string[] {
  const dem = game.candidates.dem;
  const rep = game.candidates.rep;
  const scen = getScenario(game.scenarioId);
  const natl = nationalPoll(game);
  const out: string[] = [];

  if (game.turn === 0) {
    out.push(`${dem.name} vs ${rep.name}. The ${scen.year} campaign begins.`);
    out.push(scen.tagline);
  }

  // Debate-prep nudge: when a debate is within two weeks, lead with it so the
  // player knows prep (Debate Prep + Policy Prep) pays off before showtime.
  const toDebate = turnsUntilDebate(game);
  if (toDebate !== null && toDebate >= 1 && toDebate <= 2) {
    const readyish = debateReadiness(game, game.playerCandidate) >= 56;
    const when = toDebate === 1 ? "NEXT WEEK" : "IN 2 WEEKS";
    out.push(
      `⚑ DEBATE ${when}: ${readyish ? "your prep is solid; your choice and opponent still matter." : "prep now (Debate Prep + Policy Prep) to lift your score."}`,
    );
  }

  out.push(`NATIONAL POLL: ${dem.shortName} ${pct(natl)} · ${rep.shortName} ${pct(1 - natl)}`);

  if (live) {
    const tos = live.tossupEv ? ` · ${live.tossupEv} tossup` : "";
    out.push(`PROJECTED: ${dem.shortName} ${live.ev.dem} EV, ${rep.shortName} ${live.ev.rep} EV${tos}`);
    const close = [...live.contests]
      .filter((c) => game.states.find((s) => s.id === c.stateId)?.battleground)
      .sort((a, b) => Math.abs(a.demShare - 0.5) - Math.abs(b.demShare - 0.5))[0];
    if (close) {
      const st = game.states.find((s) => s.id === close.stateId);
      if (st) out.push(`${st.name.toUpperCase()}: ${close.lean === "tossup" ? "a dead heat" : leanLabel(close.demShare)}`);
    }
  }

  if (game.turn > 0) {
    for (const r of game.lastRecap.filter((x) => !/electoral votes/i.test(x.label)).slice(0, 3)) {
      out.push(`${r.label.toUpperCase()}: ${r.detail}`);
    }
  }

  return out;
}

export function NewsTicker() {
  const game = useGameStore((s) => s.game)!;
  const live = useGameStore((s) => s.liveProjection)();
  const headlines = useMemo(() => buildHeadlines(game, live), [game, live]);
  const [i, setI] = useState(0);

  // Reset to the top when the week's headlines change.
  useEffect(() => { setI(0); }, [game.turn]);
  useEffect(() => {
    if (headlines.length <= 1) return;
    const t = setInterval(() => setI((x) => (x + 1) % headlines.length), 4800);
    return () => clearInterval(t);
  }, [headlines.length]);

  if (headlines.length === 0) return null;
  return (
    <div className="ticker" data-coach="ticker" role="status" aria-live="polite">
      <span className="ticker-tag"><span className="ticker-dot" /> LIVE</span>
      <span key={i} className="ticker-line">{headlines[i % headlines.length]}</span>
    </div>
  );
}
