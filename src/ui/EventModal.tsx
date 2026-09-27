import { useState } from "react";
import { useGameStore } from "@store/gameStore";
import { choiceAvailable, debateReadiness } from "@engine/index";
import { EVENTS_BY_ID } from "@content/events";
import { BLOCS } from "@content/blocs";
import { useRequiredModalFocus } from "./useRequiredModalFocus";

// Maps a debate-readiness score (0..100) to a label + how the night will play.
function readinessTier(r: number): { label: string; tone: "up" | "down" | "flat"; note: string } {
  if (r >= 72) return { label: "Well prepared", tone: "up", note: "Prep improves your odds; the opponent and your choice still matter." };
  if (r >= 56) return { label: "Ready", tone: "flat", note: "Your preparation is adequate, but a win is not assured." };
  if (r >= 44) return { label: "Average", tone: "flat", note: "No edge from the podium. It plays as written." };
  return { label: "Underprepared", tone: "down", note: "Shaky command. The upside shrinks and mistakes cost more." };
}

// Shows the first pending event for the player. Two steps: pick a choice, see
// the narrated outcome (the authored resultText), then continue. Effects are
// applied on Continue so the reveal and the state change stay in sync.
export function EventModal() {
  const game = useGameStore((s) => s.game)!;
  const resolve = useGameStore((s) => s.resolvePlayerEvent);
  const [chosenId, setChosenId] = useState<string | null>(null);
  const dialogRef = useRequiredModalFocus<HTMLDivElement>();

  const pending = game.pendingEvents.find((p) => p.forCandidate === game.playerCandidate);
  if (!pending) return null;
  const event = EVENTS_BY_ID[pending.eventId];
  if (!event) return null;

  // Only this ticket's options — the opponent faces a different set entirely.
  const myChoices = event.choices.filter((c) => !c.side || c.side === game.playerCandidate);
  const chosen = chosenId ? myChoices.find((c) => c.id === chosenId) : null;

  const commit = () => {
    if (!chosen) return;
    resolve(event.id, chosen.id);
    setChosenId(null);
  };

  return (
    <div className="overlay">
      <div className="modal" ref={dialogRef} role="dialog" aria-modal="true" aria-label={event.title} tabIndex={-1}>
        <div className="head">
          <div className="tag">{event.isDebate ? "Debate Night" : "Breaking: Decision Required"}</div>
          <h2>{event.title}</h2>
        </div>
        <div className="body">
          <p className="prompt">{event.prompt}</p>

          {event.isDebate && (() => {
            const r = debateReadiness(game, game.playerCandidate);
            const tier = readinessTier(r);
            return (
              <div className="readiness">
                <span className="readiness-k">Debate readiness</span>
                <span className={`readiness-v ${tier.tone}`}>{tier.label}</span>
                <span className="readiness-note">{tier.note}</span>
              </div>
            );
          })()}

          {!chosen && myChoices.map((choice) => {
            const available = choiceAvailable(game, game.playerCandidate, choice);
            return (
              <button
                key={choice.id}
                className="choice"
                disabled={!available}
                onClick={() => (event.isDebate ? resolve(event.id, choice.id) : setChosenId(choice.id))}
              >
                <span className="ct">{choice.text}</span>
                <span className="deltas">
                  {!available && choice.requires && (
                    <span className="chip down">needs {choice.requires.trait} ≥ {choice.requires.min}</span>
                  )}
                  {(choice.effects.blocDeltas ?? []).filter((d) => d.margin).map((d) => (
                    <span key={d.blocId} className={`chip ${(d.margin ?? 0) >= 0 ? "up" : "down"}`}>
                      {BLOCS[d.blocId].name.split(" ")[0]} {(d.margin ?? 0) >= 0 ? "▲" : "▼"}
                    </span>
                  ))}
                  {choice.effects.momentum ? <span className={`chip ${choice.effects.momentum >= 0 ? "up" : "down"}`}>momentum {choice.effects.momentum >= 0 ? "▲" : "▼"}</span> : null}
                  {choice.effects.cash ? <span className="chip up">+cash</span> : null}
                </span>
              </button>
            );
          })}

          {chosen && (
            <>
              <div className="choice" style={{ cursor: "default" }}>
                <span className="ct">{chosen.text}</span>
              </div>
              <p className="resulttext">{chosen.resultText}</p>
              <button className="primary" style={{ width: "100%" }} onClick={commit}>
                Continue →
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
