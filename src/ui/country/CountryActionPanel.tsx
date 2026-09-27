import { useEffect, useState } from "react";
import { useCountryStore } from "@store/countryStore";
import type { CountryAction, CountryActionType, CountryAdMode } from "@engine/countryGame";
import type { PartyId } from "@engine/system";
import { partyShort } from "./helpers";
import { PlanBonusStrip } from "../PlanBonusStrip";
import {
  Radio, Megaphone, Users, Banknote, Building2, Vote,
  DoorOpen, Search, GraduationCap, BookOpen, ArrowLeftRight, Plus, X,
} from "lucide-react";

type IconComp = React.ComponentType<{ size?: number | string; className?: string }>;

const ICON: Record<CountryActionType, IconComp> = {
  broadcast: Radio,
  rally: Megaphone,
  surrogate: Users,
  fundraise: Banknote,
  ground_game: Building2,
  gotv: Vote,
  canvass: DoorOpen,
  oppo_research: Search,
  debate_prep: GraduationCap,
  policy_prep: BookOpen,
  issue_pivot: ArrowLeftRight,
};

const NEEDS_REGION: CountryActionType[] = ["rally", "surrogate", "ground_game", "gotv", "canvass"];
const OPTIONAL_REGION: CountryActionType[] = ["broadcast", "oppo_research"];
const DAYS = [1, 2, 3, 4, 5, 6, 7];
const MAX_PER_DAY = 3;

export function CountryActionPanel() {
  const country = useCountryStore((s) => s.country)!;
  const game = useCountryStore((s) => s.game)!;
  const queueAction = useCountryStore((s) => s.queueAction);
  const removeAction = useCountryStore((s) => s.removeAction);
  const clearActions = useCountryStore((s) => s.clearActions);
  const preview = useCountryStore((s) => s.previewProjection)();
  const live = useCountryStore((s) => s.liveProjection)();
  const selectedRegionId = useCountryStore((s) => s.selectedRegionId);

  const player = game.playerParty;
  const res = game.resources[player];
  const regions = game.regions.filter((r) => r.baselineShare?.[player] !== undefined);
  const cur = country.currency;

  const ACTIONS: { type: CountryActionType; label: string; cost: string }[] = [
    { type: "broadcast", label: "Broadcast", cost: `${cur} + air war` },
    { type: "rally", label: "Rally", cost: "leader" },
    { type: "surrogate", label: "Surrogate", cost: `${cur}0.25M` },
    { type: "fundraise", label: "Fundraise", cost: `+${cur} war chest` },
    { type: "ground_game", label: "Field Offices", cost: `${cur}1.5M` },
    { type: "gotv", label: "GOTV", cost: `${cur}1M` },
    { type: "oppo_research", label: "Oppo Research", cost: `${cur}2M` },
    { type: "debate_prep", label: "Debate Prep", cost: "prep" },
    { type: "policy_prep", label: "Platform", cost: "prep" },
    { type: "issue_pivot", label: "Issue Pitch", cost: "free" },
  ];

  const [type, setType] = useState<CountryActionType>("broadcast");
  const [regionId, setRegionId] = useState<string>(regions[0]?.id ?? "");
  const [mode, setMode] = useState<CountryAdMode>("positive");
  const [issueId, setIssueId] = useState<string>(country.issues[0].id);
  const [spendM, setSpendM] = useState<number>(2);

  useEffect(() => {
    if (selectedRegionId && regions.some((r) => r.id === selectedRegionId)) setRegionId(selectedRegionId);
  }, [selectedRegionId]); // eslint-disable-line react-hooks/exhaustive-deps

  const plan = game.queuedActions;
  const used = plan.length;
  const pool = res.maxActions;
  const dayItems = (d: number) => plan.map((a, i) => ({ a, i })).filter((x) => (x.a.day ?? 1) === d);
  const regionAbbr = (id?: string) => game.regions.find((r) => r.id === id)?.abbr ?? "";

  const usesRegion = NEEDS_REGION.includes(type) || OPTIONAL_REGION.includes(type);
  const regionRequired = NEEDS_REGION.includes(type);

  const shortDesc = (a: CountryAction): string => {
    const where = a.regionId ? ` ${regionAbbr(a.regionId)}` : "";
    switch (a.type) {
      case "broadcast": return `${a.mode === "contrast" ? "Attack" : a.mode === "issue" ? "Issue" : "Air"} ${a.regionId ? regionAbbr(a.regionId) : "natl"}`;
      case "rally": return `Rally${where}`;
      case "surrogate": return `Surrogate${where}`;
      case "ground_game": return `Field${where}`;
      case "gotv": return `GOTV${where}`;
      case "canvass": return `Canvass${where}`;
      case "oppo_research": return `Oppo${a.regionId ? where : ""}`;
      case "debate_prep": return "Debate prep";
      case "policy_prep": return "Platform";
      case "issue_pivot": return `Pitch ${a.issueId ?? ""}`;
      case "fundraise": return "Fundraise";
      default: return a.type;
    }
  };

  const buildAction = (): CountryAction => {
    const a: CountryAction = { type, party: player as PartyId };
    if (usesRegion && regionId && regionId !== "__national") a.regionId = regionId;
    if (type === "broadcast") {
      a.mode = mode;
      a.spend = spendM;
      if (mode === "issue") a.issueId = issueId;
    }
    if (type === "issue_pivot") a.issueId = issueId;
    return a;
  };

  const addToDay = (d: number) => {
    if (used >= pool || dayItems(d).length >= MAX_PER_DAY) return;
    if (regionRequired && !regionId) return;
    queueAction({ ...buildAction(), day: d });
  };

  const playerSeats = (r: typeof live) => r?.seats[player] ?? 0;
  const seatDelta = playerSeats(preview) - playerSeats(live);
  const TypeIcon = ICON[type];

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <h3 style={{ margin: 0 }}>Week Plan</h3>
        <span className="muted small">{used}/{pool} actions · max {MAX_PER_DAY}/day</span>
      </div>
      <div className="row" style={{ gap: 16, margin: "6px 0 10px" }}>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Funds</span><span className="v">{cur}{res.funds.toFixed(1)}M</span></div>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Actions</span><span className="v">{res.actions}/{res.maxActions}</span></div>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Momentum</span><span className="v">{res.momentum >= 0 ? "+" : ""}{res.momentum.toFixed(0)}</span></div>
      </div>

      <div className="actiongrid">
        {ACTIONS.map((a) => {
          const Ico = ICON[a.type];
          return (
            <button key={a.type} className={`actionbtn${type === a.type ? " sel" : ""}`} onClick={() => setType(a.type)}>
              <Ico size={18} />
              <span className="t">{a.label}</span>
              <span className="c">{a.type === "fundraise" ? `≈+${cur}${(1.5 + game.leaders[player].machine / 50).toFixed(1)}M` : a.cost}</span>
            </button>
          );
        })}
      </div>

      <div style={{ marginTop: 10 }}>
        {usesRegion && (
          <div className="field">
            <label>Target {regionRequired ? "region" : "region (or national)"}</label>
            <select value={regionId} onChange={(e) => setRegionId(e.target.value)}>
              {!regionRequired && <option value="__national">National: every region you contest</option>}
              {regions.map((r) => (
                <option key={r.id} value={r.id}>{r.name} ({r.seats} {country.unitNamePlural})</option>
              ))}
            </select>
          </div>
        )}

        {type === "broadcast" && (
          <>
            <div className="field">
              <label>Broadcast mode</label>
              <select value={mode} onChange={(e) => setMode(e.target.value as CountryAdMode)}>
                <option value="positive">Positive (raise your support)</option>
                <option value="contrast">Contrast (attack the front-runner)</option>
                <option value="issue">Issue (raise issue salience)</option>
              </select>
            </div>
            {mode === "issue" && (
              <div className="field">
                <label>Issue</label>
                <select value={issueId} onChange={(e) => setIssueId(e.target.value)}>
                  {country.issues.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                </select>
              </div>
            )}
            <div className="field">
              <label>Spend: {cur}{spendM.toFixed(1)}M</label>
              <input type="range" min={0.5} max={8} step={0.5} value={spendM} onChange={(e) => setSpendM(+e.target.value)} />
            </div>
          </>
        )}

        {type === "issue_pivot" && (
          <div className="field">
            <label>Reframe campaign onto</label>
            <select value={issueId} onChange={(e) => setIssueId(e.target.value)}>
              {country.issues.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
            </select>
            <p className="muted small" style={{ margin: "4px 0 0" }}>{country.issues.find((i) => i.id === issueId)?.blurb}</p>
          </div>
        )}
      </div>

      <PlanBonusStrip plan={plan} />

      <div className="weekplan">
        {DAYS.map((d) => {
          const items = dayItems(d);
          const full = items.length >= MAX_PER_DAY;
          const canAdd = used < pool && !full && !(regionRequired && !regionId);
          return (
            <div className={`day${full ? " full" : ""}`} key={d}>
              <div className="day-head">
                <span className="day-name">Day {d}</span>
                <span className="day-slots">
                  {Array.from({ length: MAX_PER_DAY }).map((_, k) => (
                    <span key={k} className={k < items.length ? "dot on" : "dot"} />
                  ))}
                </span>
                <button className="day-add" disabled={!canAdd} onClick={() => addToDay(d)} title={canAdd ? `Add ${type} to Day ${d}` : "No room"}>
                  <Plus size={13} />
                </button>
              </div>
              <div className="day-actions">
                {items.length === 0 && <span className="day-empty">no events</span>}
                {items.map(({ a, i }) => {
                  const Ico = ICON[a.type];
                  return (
                    <span className="plan-chip" key={i} title={a.type}>
                      <Ico size={12} />
                      {shortDesc(a)}
                      <span className="x" onClick={() => removeAction(i)}><X size={11} /></span>
                    </span>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>

      <div className="row" style={{ gap: 8, marginTop: 6 }}>
        <button className="primary" style={{ flex: 1, display: "inline-flex", alignItems: "center", justifyContent: "center", gap: 6 }}
          disabled={used >= pool || (regionRequired && !regionId)}
          onClick={() => { for (const d of DAYS) { if (dayItems(d).length < MAX_PER_DAY) { addToDay(d); break; } } }}>
          <TypeIcon size={14} /> Add to next open day
        </button>
        {used > 0 && <button className="ghost small" onClick={clearActions}>Clear</button>}
      </div>

      {preview && used > 0 && (
        <div className="preview">
          Projected with this plan: <strong>{partyShort(country, player)} {playerSeats(preview)} {country.unitNamePlural}</strong>
          {seatDelta !== 0 && <strong>{` (${seatDelta > 0 ? "+" : ""}${seatDelta} for you)`}</strong>}
        </div>
      )}
    </div>
  );
}
