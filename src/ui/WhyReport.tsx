import { useMemo } from "react";
import { deriveReport, type ReplayLog } from "@lib/replay";
import { Sparkline } from "./Sparkline";
import { money } from "./format";

// Post-game "why you won / lost" breakdown, built entirely from the replay
// log. Engine-agnostic: it renders for US, UK, and country games alike and
// simply omits any block whose data does not apply.

const pts = (v: number) => `${v >= 0 ? "+" : ""}${v.toFixed(1)}`;

export function WhyReport({ log, won }: { log: ReplayLog | null; won: boolean | null }) {
  const r = useMemo(() => deriveReport(log), [log]);
  if (!r.hasData) return null;

  const rivalName = r.topRivalName ?? "the field";
  const flipsToPlayer = r.flips.filter((f) => f.toPlayer).length;
  const flipsAway = r.flips.length - flipsToPlayer;

  // One plain-English read of the arc.
  const verdict =
    won === null
      ? `You finished ${pts(r.finalMarginPts)} points against ${rivalName}.`
      : won
        ? `You closed ${pts(r.finalMarginPts)} points ahead of ${rivalName} and took the lead in ${r.unitLabel}.`
        : `You finished ${pts(r.finalMarginPts)} points against ${rivalName} and fell short in ${r.unitLabel}.`;

  const debates = r.events.filter((e) => e.text.startsWith("Debate:"));
  const otherEvents = r.events.filter((e) => !e.text.startsWith("Debate:"));
  const currency = log?.engine === "uk" ? "£" : log?.engine === "country" && /^(fr|de|france|germany)/i.test(log.scenarioId) ? "€" : "$";

  return (
    <div className="card">
      <h3>Why You {won === null ? "Finished Here" : won ? "Won" : "Lost"}</h3>
      <p className="muted small">{verdict}</p>

      {/* Headline numbers */}
      <div className="report-grid">
        <div className="report-cell">
          <div className="rv">{pts(r.finalMarginPts)}</div>
          <div className="rl">Final margin (pts)</div>
        </div>
        <div className="report-cell">
          <div className="rv">{pts(r.swingPts)}</div>
          <div className="rl">Swing since start</div>
        </div>
        <div className="report-cell">
          <div className="rv">{r.peakUnits} / {r.floorUnits}</div>
          <div className="rl">Peak / floor {r.unitLabel}</div>
        </div>
        {r.cashSpent !== null && (
          <div className="report-cell">
            <div className="rv">{money(r.cashSpent).replace("$", currency)}</div>
            <div className="rl">Net cash used, including fundraising</div>
          </div>
        )}
        <div className="report-cell">
          <div className="rv">{r.flips.length}</div>
          <div className="rl">Lead changes</div>
        </div>
      </div>

      {/* Polling trend */}
      {r.marginTrend.length >= 2 && (
        <div style={{ marginBottom: 12 }}>
          <div className="rl" style={{ marginBottom: 4 }}>Your national margin, start to finish</div>
          <Sparkline
            values={r.marginTrend.map((p) => p.value)}
            color="var(--gold)"
            mid={0}
            width={420}
            height={54}
          />
          {r.flips.length > 0 && (
            <p className="muted small" style={{ marginTop: 4 }}>
              The {r.unitLabel} lead changed hands {r.flips.length} time{r.flips.length === 1 ? "" : "s"}
              {flipsToPlayer > 0 || flipsAway > 0
                ? ` (${flipsToPlayer} in your favor, ${flipsAway} against).`
                : "."}
            </p>
          )}
        </div>
      )}

      {/* Decisive contests */}
      {r.decisiveContests.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <div className="rl" style={{ marginBottom: 4 }}>Decisive contests (closest finishes)</div>
          {r.decisiveContests.map((c) => (
            <div className="report-row" key={c.id}>
              <span>{c.name}</span>
              <span className={c.won ? "up" : "down"}>
                {c.won ? "Won" : "Lost"} by {Math.abs(c.marginPts).toFixed(1)} pts
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Spending / action summary */}
      {r.actionTally.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <div className="rl" style={{ marginBottom: 4 }}>What you spent the campaign doing</div>
          {r.actionTally.slice(0, 8).map((a) => (
            <div className="report-row" key={a.label}>
              <span>{a.label}</span>
              <span className="muted">{a.count}x</span>
            </div>
          ))}
        </div>
      )}

      {/* Debate outcomes */}
      {debates.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <div className="rl" style={{ marginBottom: 4 }}>Debate nights</div>
          {debates.map((d, i) => (
            <div className="report-row" key={i}>
              <span>Week {d.turn}</span>
              <span>{d.text.replace(/^Debate:\s*/, "")}</span>
            </div>
          ))}
        </div>
      )}

      {/* Event impact */}
      {otherEvents.length > 0 && (
        <div>
          <div className="rl" style={{ marginBottom: 4 }}>Events that hit the campaign</div>
          {otherEvents.slice(0, 10).map((e, i) => (
            <div className="report-row" key={i}>
              <span>Week {e.turn}</span>
              <span className="muted">{e.text}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
