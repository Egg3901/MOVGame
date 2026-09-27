import { useUkStore } from "@store/ukStore";
import { majorityForUk } from "@engine/ukGame";
import { partyColor, partyShort, sortBySeats } from "./parties";
import { partyName } from "./parties";
import { UK_ISSUES_BY_ID } from "@content/uk/issues";
import { MpPollBlock } from "@ui/MpPollBlock";

const GOV_LABEL: Record<string, string> = {
  majority: "Majority government",
  minority: "Minority government",
  coalition: "Coalition",
  confidence_supply: "Confidence & supply",
  hung: "Hung parliament",
};

// National party standings + projected government + issue salience — the UK
// analog of the U.S. National Intel panel.
export function UkStandings() {
  const game = useUkStore((s) => s.game)!;
  const live = useUkStore((s) => s.liveProjection)();
  if (!live) return null;
  const order = sortBySeats(live.seats);
  const maj = majorityForUk(game);
  const issues = Object.keys(game.salience).filter((id) => game.salience[id] >= 0.1).sort((a, b) => game.salience[b] - game.salience[a]);

  return (
    <div className="card scroll">
      <h3>National Standings</h3>
      <div className="kv"><span className="k">Projected government</span><span style={{ color: partyColor(live.largestParty) }}>{GOV_LABEL[live.government.kind]}</span></div>
      <div className="kv"><span className="k">Largest party</span><span style={{ color: partyColor(live.largestParty) }}>{partyName(live.largestParty)}</span></div>
      <div className="kv"><span className="k">Your momentum</span><span>{game.resources[game.playerParty].momentum.toFixed(0)}</span></div>

      <MpPollBlock
        seed={game.seed}
        turn={game.turn}
        regions={game.regions}
        parties={order}
        playerParty={game.playerParty}
        partyLabel={partyShort}
        partyColor={partyColor}
      />

      <h3 style={{ marginTop: 14 }}>Seats</h3>
      {order.map((p) => (
        <div className="bloc" key={p}>
          <span className="name" style={{ color: p === game.playerParty ? partyColor(p) : undefined, fontWeight: p === game.playerParty ? 700 : 500 }}>
            <span style={{ display: "inline-block", width: 9, height: 9, borderRadius: 2, background: partyColor(p), marginRight: 6 }} />
            {partyShort(p)}
          </span>
          <span className="meta">{live.seats[p]} seats · {((live.voteShare[p] ?? 0) * 100).toFixed(1)}%</span>
          <div className="suppbar"><div style={{ width: `${(live.seats[p] / maj.total) * 100 * 2}%`, background: partyColor(p) }} /></div>
        </div>
      ))}

      <h3 style={{ marginTop: 14 }}>Issue Salience</h3>
      {issues.map((id) => (
        <div className="bloc issue-row" key={id} aria-label={`${UK_ISSUES_BY_ID[id as keyof typeof UK_ISSUES_BY_ID]?.name ?? id} salience ${(game.salience[id] * 100).toFixed(0)}%`}>
          <span className="name">{UK_ISSUES_BY_ID[id as keyof typeof UK_ISSUES_BY_ID]?.name ?? id}</span>
          <span className="meta">{(game.salience[id] * 100).toFixed(0)}%</span>
          <div className="suppbar"><div style={{ width: `${game.salience[id] * 100}%`, background: "var(--gold)" }} /></div>
        </div>
      ))}
    </div>
  );
}
