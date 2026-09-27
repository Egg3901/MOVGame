import { useGameStore } from "@store/gameStore";
import { nationalPoll } from "@engine/index";
import { ISSUES, ISSUE_IDS } from "@content/issues";
import { pct } from "./format";

export function IntelPanel() {
  const game = useGameStore((s) => s.game)!;
  const natl = nationalPoll(game);
  // Issues sorted by current national salience.
  // Only issues that are actually live this cycle (covid is ~0 before 2020).
  const issues = [...ISSUE_IDS].filter((id) => game.salience[id] >= 0.08).sort((a, b) => game.salience[b] - game.salience[a]);

  const opp = game.playerCandidate === "dem" ? "rep" : "dem";
  const oppLocId = game.locations?.[opp];
  const oppLoc = oppLocId ? game.states.find((s) => s.id === oppLocId) : null;

  return (
    <div className="card scroll">
      <h3>National Intel</h3>
      <div className="kv"><span className="k">National poll (2-party)</span><span>{game.candidates.dem.shortName} {pct(natl)} · {game.candidates.rep.shortName} {pct(1 - natl)}</span></div>
      <div className="kv" title="Short-term campaign energy. Runs from -100 to +100 and fades each week."><span className="k">Your momentum</span><span>{game.resources[game.playerCandidate].nationalMomentum.toFixed(0)}</span></div>
      <div className="kv" title="How favorable the current coverage is to your campaign, from -100 to +100."><span className="k">Media narrative</span><span>{game.resources[game.playerCandidate].mediaNarrative.toFixed(0)}</span></div>
      <div className="kv"><span className="k">{game.candidates[opp].shortName}'s last stop</span><span style={{ color: game.candidates[opp].color }}>{oppLoc ? oppLoc.name : "·"}</span></div>

      <h3 style={{ marginTop: 14 }} title="The share of voter attention on each issue, not support for either ticket.">Issue Salience</h3>
      {issues.map((id) => (
        <div className="bloc issue-row" key={id} aria-label={`${ISSUES[id].name} salience ${pct(game.salience[id], 0)}`}>
          <span className="name">{ISSUES[id].name}</span>
          <span className="meta">{pct(game.salience[id], 0)}</span>
          <div className="suppbar"><div className="d" style={{ width: `${game.salience[id] * 100}%`, background: "var(--gold)" }} /></div>
        </div>
      ))}
    </div>
  );
}
