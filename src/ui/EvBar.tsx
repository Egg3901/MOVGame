import type { Projection } from "@engine/index";

// The You/Tossup/Them tally bar, always Dem-left / GOP-right out of 538.
export function EvBar({ projection }: { projection: Projection }) {
  const dem = projection.ev.dem;
  const gop = projection.ev.rep;
  const tossup = projection.tossupEv;
  const total = 538;
  const w = (n: number) => `${(n / total) * 100}%`;
  return (
    <div className="evbar">
      <div className={`endlabel dem`}>{dem}</div>
      <div className="evbar-track-wrap">
        <div className="track">
          {dem > 0 && <div className="seg dem" style={{ width: w(dem) }}>{dem >= 30 ? "DEM" : ""}</div>}
          {tossup > 0 && <div className="seg tossup" style={{ width: w(tossup) }} title={`${tossup} tossup EV`} />}
          {gop > 0 && <div className="seg gop" style={{ width: w(gop) }}>{gop >= 30 ? "GOP" : ""}</div>}
        </div>
        <span className="evbar-tossup-label">{tossup} TOSSUP EV</span>
      </div>
      <div className={`endlabel gop`}>{gop}</div>
    </div>
  );
}
