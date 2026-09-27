import { useGameStore } from "@store/gameStore";
import { turnLabel } from "./format";
import { signed } from "./format";

// "Week in review" — shown after each turn resolves. Every line is a cause.
export function RecapModal({ onClose }: { onClose: () => void }) {
  const game = useGameStore((s) => s.game)!;
  const recap = game.lastRecap;
  if (!recap || recap.length === 0) return null;

  return (
    <div className="overlay">
      <div className="modal">
        <div className="head">
          <div className="tag">Week in Review</div>
          <h2>{turnLabel(game.turn - 1, game.totalTurns)} → {turnLabel(game.turn, game.totalTurns)}</h2>
        </div>
        <div className="body">
          <p className="muted small">Positive values favor your ticket. Only the first line is an EV change; action values show model impact.</p>
          {recap.map((item, i) => (
            <div className="recapitem" key={i}>
              <div>
                <div>{item.label}</div>
                <div className="muted small">{item.detail}</div>
              </div>
              {item.marginDelta !== undefined && (
                <span className={`delta ${item.marginDelta > 0.001 ? "up" : item.marginDelta < -0.001 ? "down" : "flat"}`}>
                  {i === 0 ? signed(item.marginDelta, 0) + " EV" : signed(item.marginDelta, 2)}
                </span>
              )}
            </div>
          ))}
          <button className="primary" style={{ width: "100%", marginTop: 14 }} onClick={onClose}>
            Continue →
          </button>
        </div>
      </div>
    </div>
  );
}
