import { useState } from "react";
import { useCountryStore } from "@store/countryStore";
import { tallyRegion, allocateRegionSeats } from "@engine/multiparty";
import type { StateContest } from "@engine/types";
import { majorityFor, type CountryBundle, type CountryResult } from "@engine/countryGame";
import { partyColor, partyShort, partyName, sortBySeats, byDisplayOrder } from "./helpers";
import { MpPollBlock } from "@ui/MpPollBlock";
import { MapPin } from "lucide-react";

function regionWinner(region: StateContest): { winner: string; seats: Record<string, number>; lead: number } {
  const { shareByParty } = tallyRegion(region);
  const seats = allocateRegionSeats(region, shareByParty);
  let winner = "";
  let best = -1;
  let total = 0;
  for (const p of Object.keys(seats)) { total += seats[p]; if (seats[p] > best) { best = seats[p]; winner = p; } }
  const lead = total > 0 ? best / total : 0.5; // dominance → fill opacity
  return { winner, seats, lead };
}

// ── Seat bar (majority marker at the country's threshold) ──────────────────
export function CountrySeatBar({ country, result }: { country: CountryBundle; result: CountryResult }) {
  const game = useCountryStore((s) => s.game);
  const order = sortBySeats(country, result.seats);
  const { total, threshold } = game ? majorityFor(game, country) : country.system.majority;
  const w = (n: number) => `${(n / total) * 100}%`;
  const largest = order[0];

  return (
    <div className="evbar" title={`${country.unitNamePlural} won: ${threshold} for the win`}>
      <div className="endlabel dem" style={{ color: largest ? partyColor(country, largest) : undefined }}>
        {largest ? result.seats[largest] : 0}
      </div>
      <div className="track" style={{ position: "relative" }}>
        {order.map((p) => (
          <div key={p} className="seg" style={{ width: w(result.seats[p]), background: partyColor(country, p), color: "#0c0d10" }}>
            {result.seats[p] / total >= 0.07 ? partyShort(country, p) : ""}
          </div>
        ))}
        <div style={{ position: "absolute", left: `${(threshold / total) * 100}%`, top: -3, bottom: -3, width: 2, background: "#fff", opacity: 0.9 }} />
      </div>
      <div className="endlabel gop">{threshold}</div>
    </div>
  );
}

// ── Country map: real geography when the bundle ships SVG shapes, tile
// cartogram otherwise (and always available as the Square view). ──
export function CountryMap() {
  const country = useCountryStore((s) => s.country)!;
  const game = useCountryStore((s) => s.game)!;
  const selected = useCountryStore((s) => s.selectedRegionId);
  const select = useCountryStore((s) => s.selectRegion);
  const hasGeo = !!country.map;
  const [mode, setMode] = useState<"geo" | "square">(hasGeo ? "geo" : "square");

  const metaById = Object.fromEntries(country.regions.map((r) => [r.id, r]));
  const rows = Math.max(...country.regions.map((r) => r.tile.row)) + 1;
  const cols = Math.max(...country.regions.map((r) => r.tile.col)) + 1;

  const header = (
    <div className="mapcontrols" style={{ justifyContent: "space-between" }}>
      <h3 style={{ margin: 0, fontSize: 13, textTransform: "uppercase", letterSpacing: 0.8, color: "var(--muted)" }}>
        {country.flag} {country.label} · {majorityFor(game, country).total} {country.unitNamePlural}
      </h3>
      {hasGeo && (
        <div className="row" style={{ gap: 4 }}>
          <button className={`ghost small${mode === "geo" ? " active" : ""}`} onClick={() => setMode("geo")}>Geo</button>
          <button className={`ghost small${mode === "square" ? " active" : ""}`} onClick={() => setMode("square")}>Square</button>
        </div>
      )}
    </div>
  );

  const legend = (
    <div className="legend" style={{ flexWrap: "wrap", gap: 8 }}>
      <span className="muted small">Labels show leader {country.unitNamePlural} / region total.</span>
      {game.parties.filter((p) => country.playable.includes(p)).map((p) => (
        <span key={p} className="row" style={{ gap: 4, alignItems: "center" }}>
          <span style={{ width: 10, height: 10, borderRadius: 2, background: partyColor(country, p) }} />
          {partyShort(country, p)}
        </span>
      ))}
    </div>
  );

  if (mode === "geo" && country.map) {
    const { viewBox, shapes } = country.map;
    return (
      <div className="card mapwrap sheen">
        {header}
        <svg viewBox={viewBox} className="geo-map" preserveAspectRatio="xMidYMid meet">
          {game.regions.map((region) => {
            const shape = shapes[region.id];
            if (!shape) return null;
            const { winner, seats, lead } = regionWinner(region);
            const isSel = selected === region.id;
            return (
              <g key={region.id} style={{ cursor: "pointer" }} onClick={() => select(isSel ? null : region.id)}>
                <path
                  d={shape.d}
                  fill={partyColor(country, winner)}
                  fillOpacity={0.45 + lead * 0.55}
                  stroke={isSel ? "var(--gold)" : "#0c1322"}
                  strokeWidth={isSel ? 3 : 1}
                />
                <text x={shape.label[0]} y={shape.label[1]} textAnchor="middle"
                  style={{ fontSize: 17, fontWeight: 800, fill: "#fff", pointerEvents: "none", paintOrder: "stroke", stroke: "rgba(0,0,0,0.45)", strokeWidth: 3 }}>
                  {region.abbr}
                </text>
                <text x={shape.label[0]} y={shape.label[1] + 18} textAnchor="middle"
                  style={{ fontSize: 13, fontWeight: 700, fill: "#fff", pointerEvents: "none", paintOrder: "stroke", stroke: "rgba(0,0,0,0.45)", strokeWidth: 3 }}>
                  {winner ? `${partyShort(country, winner)} ${seats[winner]}/${region.seats}` : ""}
                </text>
              </g>
            );
          })}
        </svg>
        {legend}
      </div>
    );
  }

  return (
    <div className="card mapwrap sheen">
      {header}
      <div className="tilegrid" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)`, gridTemplateRows: `repeat(${rows}, 1fr)`, aspectRatio: `${cols} / ${rows}` }}>
        {game.regions.map((region) => {
          const meta = metaById[region.id];
          const { winner, seats } = regionWinner(region);
          const isSel = selected === region.id;
          return (
            <div key={region.id} style={{ gridColumn: (meta?.tile.col ?? 0) + 1, gridRow: (meta?.tile.row ?? 0) + 1 }}>
              <div
                className={`tile${isSel ? " selected" : ""}`}
                style={{ background: partyColor(country, winner), width: "100%", outline: isSel ? "2px solid var(--gold)" : undefined }}
                onClick={() => select(isSel ? null : region.id)}
                title={`${region.name}: ${region.seats} ${country.unitNamePlural}`}
              >
                <span>{region.abbr}</span>
                <span className="ev">{winner ? `${partyShort(country, winner)} ${seats[winner]}/${region.seats}` : region.seats}</span>
              </div>
            </div>
          );
        })}
      </div>
      {legend}
    </div>
  );
}

// ── National standings (projected outcome + salience) ──────────────────────
const GOV_LABEL: Record<string, string> = {
  majority: "Majority",
  minority: "Minority government",
  coalition: "Coalition",
  confidence_supply: "Confidence & supply",
  hung: "No majority",
};

export function CountryStandings() {
  const country = useCountryStore((s) => s.country)!;
  const game = useCountryStore((s) => s.game)!;
  const live = useCountryStore((s) => s.liveProjection)();
  if (!live) return null;
  const order = sortBySeats(country, live.seats);
  const total = majorityFor(game, country).total;
  const issues = Object.keys(game.salience).filter((id) => game.salience[id] >= 0.1).sort((a, b) => game.salience[b] - game.salience[a]);
  const issueName = (id: string) => country.issues.find((i) => i.id === id)?.name ?? id;

  return (
    <div className="card scroll">
      <h3>National Standings</h3>
      <div className="kv"><span className="k">Projected outcome</span><span style={{ color: partyColor(country, live.largestParty) }}>{country.id === "FR" && live.government.kind === "majority" ? "Wins presidency" : GOV_LABEL[live.government.kind]}</span></div>
      <div className="kv"><span className="k">Leading</span><span style={{ color: partyColor(country, live.largestParty) }}>{partyName(country, live.largestParty)}</span></div>
      <div className="kv"><span className="k">Your momentum</span><span>{game.resources[game.playerParty].momentum.toFixed(0)}</span></div>

      <MpPollBlock
        seed={game.seed}
        turn={game.turn}
        regions={game.regions}
        parties={order}
        playerParty={game.playerParty}
        partyLabel={(p) => partyShort(country, p)}
        partyColor={(p) => partyColor(country, p)}
      />

      <h3 style={{ marginTop: 14 }}>{country.unitNamePlural[0].toUpperCase() + country.unitNamePlural.slice(1)}</h3>
      {order.map((p) => (
        <div className="bloc" key={p}>
          <span className="name" style={{ color: p === game.playerParty ? partyColor(country, p) : undefined, fontWeight: p === game.playerParty ? 700 : 500 }}>
            <span style={{ display: "inline-block", width: 9, height: 9, borderRadius: 2, background: partyColor(country, p), marginRight: 6 }} />
            {partyShort(country, p)}
          </span>
          <span className="meta" title="Seats won and national vote share. Votes for parties below a district threshold may yield few or no seats.">{live.seats[p]} {country.unitNamePlural} · {((live.voteShare[p] ?? 0) * 100).toFixed(1)}% votes</span>
          <div className="suppbar"><div style={{ width: `${Math.min(100, (live.seats[p] / total) * 100 * 2)}%`, background: partyColor(country, p) }} /></div>
        </div>
      ))}

      <h3 style={{ marginTop: 14 }}>Issue Salience</h3>
      {issues.map((id) => (
        <div className="bloc" key={id}>
          <span className="name">{issueName(id)}</span>
          <span className="meta">{(game.salience[id] * 100).toFixed(0)}%</span>
          <div className="suppbar"><div style={{ width: `${game.salience[id] * 100}%`, background: "var(--gold)" }} /></div>
        </div>
      ))}
    </div>
  );
}

// ── Region detail panel ─────────────────────────────────────────────────────
export function CountryRegionPanel() {
  const country = useCountryStore((s) => s.country)!;
  const game = useCountryStore((s) => s.game)!;
  const selectedId = useCountryStore((s) => s.selectedRegionId);
  const queueAction = useCountryStore((s) => s.queueAction);
  const player = game.playerParty;
  const region = game.regions.find((r) => r.id === selectedId);

  if (!region) {
    return (
      <div className="card">
        <h3>Region Detail</h3>
        <p className="muted small">Select a region on the map to inspect its vote shares, projection, and campaign there.</p>
      </div>
    );
  }

  const { shareByParty } = tallyRegion(region);
  const seats = allocateRegionSeats(region, shareByParty);
  const parties = Object.keys(shareByParty).sort((a, b) => byDisplayOrder(country, a, b));
  const playerStands = region.baselineShare?.[player] !== undefined;
  const topOpp = parties
    .filter((p) => p !== player && (shareByParty[p] ?? 0) > 0.03)
    .sort((a, b) => (shareByParty[b] ?? 0) - (shareByParty[a] ?? 0))[0];
  const res = game.resources[player];
  const canAct = game.queuedActions.length < res.maxActions;

  return (
    <div className="card scroll">
      <h3>{region.name} · {region.seats} {country.unitNamePlural}</h3>

      {playerStands ? (
        <div className="row" style={{ gap: 6, margin: "2px 0 12px", flexWrap: "wrap" }}>
          <button className="secondary" disabled={!canAct} onClick={() => queueAction({ type: "canvass", party: player, regionId: region.id })}
            style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <MapPin size={14} /> Canvass
          </button>
          <button className="secondary" disabled={!canAct} onClick={() => queueAction({ type: "rally", party: player, regionId: region.id })}>Rally</button>
          {topOpp && (
            <button className="secondary" disabled={!canAct} onClick={() => queueAction({ type: "oppo_research", party: player, regionId: region.id, targetParty: topOpp })}>
              Target {partyShort(country, topOpp)}
            </button>
          )}
        </div>
      ) : (
        <p className="muted small">Your party doesn't contest {region.name}.</p>
      )}

      <h3 style={{ marginTop: 8 }}>Parties</h3>
      {parties.map((p) => (
        <div className="bloc" key={p}>
          <span className="name" style={{ color: p === player ? partyColor(country, p) : undefined, fontWeight: p === player ? 700 : 500 }}>
            <span style={{ display: "inline-block", width: 9, height: 9, borderRadius: 2, background: partyColor(country, p), marginRight: 6 }} />
            {partyShort(country, p)}
          </span>
          <span className="meta">{((shareByParty[p] ?? 0) * 100).toFixed(1)}% · {seats[p] ?? 0} {country.unitNamePlural}</span>
          <div className="suppbar">
            <div style={{ width: `${(shareByParty[p] ?? 0) * 100}%`, background: partyColor(country, p) }} />
            <div style={{ width: `${(1 - (shareByParty[p] ?? 0)) * 100}%`, background: "#222833" }} />
          </div>
        </div>
      ))}
    </div>
  );
}
