import { useState } from "react";
import { useRequiredModalFocus } from "./useRequiredModalFocus";

export interface MpEventChoiceView {
  id: string;
  text: string;
  resultText: string;
  appeal?: number;
  momentum?: number;
  rivalAppeal?: number;
}

export interface MpEventView {
  title: string;
  prompt: string;
  choices: MpEventChoiceView[];
}

// Player-choice event modal for UK / country engines (US EventModal parity).
export function MultipartyEventModal({
  event,
  onResolve,
}: {
  event: MpEventView;
  onResolve: (choiceId: string) => void;
}) {
  const [chosenId, setChosenId] = useState<string | null>(null);
  const dialogRef = useRequiredModalFocus<HTMLDivElement>();
  const chosen = chosenId ? event.choices.find((c) => c.id === chosenId) : null;

  return (
    <div className="overlay">
      <div className="modal" ref={dialogRef} role="dialog" aria-modal="true" aria-label={event.title} tabIndex={-1}>
        <div className="head">
          <div className="tag">Breaking: Decision Required</div>
          <h2>{event.title}</h2>
        </div>
        <div className="body">
          <p className="prompt">{event.prompt}</p>

          {!chosen && event.choices.map((choice) => (
            <button key={choice.id} className="choice" onClick={() => setChosenId(choice.id)}>
              <span className="ct">{choice.text}</span>
              <span className="deltas">
                {choice.appeal !== undefined && (
                  <span className={`chip ${choice.appeal >= 0 ? "up" : "down"}`}>
                    appeal {choice.appeal >= 0 ? "▲" : "▼"}
                  </span>
                )}
                {choice.momentum !== undefined && (
                  <span className={`chip ${choice.momentum >= 0 ? "up" : "down"}`}>
                    momentum {choice.momentum >= 0 ? "▲" : "▼"}
                  </span>
                )}
                {choice.rivalAppeal !== undefined && (
                  <span className="chip down">rival ▼</span>
                )}
              </span>
            </button>
          ))}

          {chosen && (
            <>
              <div className="choice" style={{ cursor: "default" }}>
                <span className="ct">{chosen.text}</span>
              </div>
              <p className="resulttext">{chosen.resultText}</p>
              <button className="primary" style={{ width: "100%" }} onClick={() => onResolve(chosen.id)}>
                Continue →
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
