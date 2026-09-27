import { useEffect, useState } from "react";
import { useGameStore } from "@store/gameStore";
import type { ActionType, AdMode, CampaignAction, IssueId } from "@engine/index";
import { ISSUES, ISSUE_IDS } from "@content/issues";
import { money } from "./format";
import { PlanBonusStrip } from "./PlanBonusStrip";
import { actionCashCost, canQueueAction, plannedCashCost } from "@engine/actionBudget";
import { estimatedFundraise } from "@engine/actions";
import {
  Tv,
  Megaphone,
  Handshake,
  DollarSign,
  Building2,
  Vote,
  Search,
  GraduationCap,
  BookOpen,
  ArrowLeftRight,
  Plus,
  X,
} from "lucide-react";

type IconComp = React.ComponentType<{ size?: number | string; className?: string }>;

const ICON: Record<ActionType, IconComp> = {
  advertise: Tv,
  rally: Megaphone,
  surrogate: Handshake,
  fundraise: DollarSign,
  ground_game: Building2,
  gotv: Vote,
  oppo_research: Search,
  debate_prep: GraduationCap,
  policy_prep: BookOpen,
  issue_pivot: ArrowLeftRight,
};

const ACTIONS: { type: ActionType; label: string; cost: string }[] = [
  { type: "advertise", label: "Advertising", cost: "$ + market" },
  { type: "rally", label: "Rally / Stop", cost: "candidate" },
  { type: "surrogate", label: "Surrogate", cost: "$250K" },
  { type: "fundraise", label: "Fundraise", cost: "+$ (big states)" },
  { type: "ground_game", label: "Field Offices", cost: "$1.5M" },
  { type: "gotv", label: "GOTV", cost: "$1M" },
  { type: "oppo_research", label: "Oppo Research", cost: "$2M" },
  { type: "debate_prep", label: "Debate Prep", cost: "prep" },
  { type: "policy_prep", label: "Policy Prep", cost: "prep" },
  { type: "issue_pivot", label: "Issue Pivot", cost: "free" },
];

const NEEDS_STATE: ActionType[] = ["advertise", "rally", "surrogate", "ground_game", "gotv", "fundraise"];
const DAYS = [1, 2, 3, 4, 5, 6, 7];
const MAX_PER_DAY = 3;

function shortDesc(a: CampaignAction): string {
  switch (a.type) {
    case "advertise": return `${a.stateId} ads`;
    case "rally": return `${a.stateId} rally`;
    case "surrogate": return `${a.stateId} surrogate`;
    case "ground_game": return `${a.stateId} field`;
    case "gotv": return `${a.stateId} GOTV`;
    case "fundraise": return "Fundraise";
    case "oppo_research": return "Oppo research";
    case "debate_prep": return "Debate prep";
    case "policy_prep": return "Policy prep";
    case "issue_pivot": return `Pivot ${a.issueId}`;
    default: return a.type;
  }
}

export function ActionPanel({ onEndWeek, endWeekDisabled }: { onEndWeek: () => void; endWeekDisabled: boolean }) {
  const game = useGameStore((s) => s.game)!;
  const queueAction = useGameStore((s) => s.queueAction);
  const removeQueued = useGameStore((s) => s.removeQueuedAction);
  const preview = useGameStore((s) => s.previewProjection)();
  const live = useGameStore((s) => s.liveProjection)();

  const player = game.playerCandidate;
  const res = game.resources[player];
  const states = game.states.filter((s) => s.blocs.length > 0);
  const selectedStateId = useGameStore((s) => s.selectedStateId);
  const selectState = useGameStore((s) => s.selectState);

  const [type, setType] = useState<ActionType>("advertise");
  const [stateId, setStateId] = useState<string>("PA");

  // Selecting a state on the map targets it here — so the next action you add
  // (or the day "+") defaults to the state you're looking at.
  useEffect(() => {
    if (selectedStateId && states.some((s) => s.id === selectedStateId)) setStateId(selectedStateId);
  }, [selectedStateId]); // eslint-disable-line react-hooks/exhaustive-deps

  // The 1-9 hotkeys pick an action type by its position in ACTIONS below.
  useEffect(() => {
    const onPick = (e: Event) => {
      const idx = (e as CustomEvent<number>).detail;
      const picked = ACTIONS[idx];
      if (picked) setType(picked.type);
    };
    window.addEventListener("hotkey-pick-action", onPick);
    return () => window.removeEventListener("hotkey-pick-action", onPick);
  }, []);
  const [adMode, setAdMode] = useState<AdMode>("positive");
  const [issueId, setIssueId] = useState<IssueId>("economy");
  const [spendM, setSpendM] = useState<number>(8);
  const [pivotPos, setPivotPos] = useState<number>(game.candidates[player].issuePositions.economy);

  const plan = game.queuedActions;
  const committedCash = plannedCashCost(game);
  const used = plan.length;
  const pool = res.maxActions;
  const dayItems = (d: number) => plan.map((a, i) => ({ a, i })).filter((x) => (x.a.day ?? 1) === d);

  const buildAction = (): CampaignAction => {
    const action: CampaignAction = { type, candidate: player };
    if (NEEDS_STATE.includes(type)) action.stateId = stateId;
    if (type === "advertise") {
      action.adMode = adMode;
      action.spend = spendM * 1_000_000;
      if (adMode === "issue") action.issueId = issueId;
    }
    if (type === "issue_pivot") {
      action.issueId = issueId;
      action.newPosition = pivotPos;
    }
    return action;
  };

  const addToDay = (d: number) => {
    const action = { ...buildAction(), day: d };
    if (!canQueueAction(game, action)) return;
    queueAction(action);
  };

  const evPreview = preview?.ev.dem ?? 0;
  const configuredCost = actionCashCost(buildAction());
  const evLive = live?.ev.dem ?? 0;
  const evDelta = evPreview - evLive;
  const myDelta = player === "dem" ? evDelta : -evDelta;

  const TypeIcon = ICON[type];

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <h3 style={{ margin: 0 }}>Week Plan</h3>
        <span className="muted small">{used}/{pool} actions · max {MAX_PER_DAY}/day</span>
      </div>
      <div className="row" style={{ gap: 16, margin: "6px 0 10px" }}>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Cash</span><span className="v">{money(res.cash)}</span></div>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Planned spend</span><span className="v">{money(committedCash)}</span></div>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Available</span><span className="v">{money(res.cash - committedCash)}</span></div>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Actions</span><span className="v">{res.actions}/{res.maxActions}</span></div>
        <div className="stat" style={{ alignItems: "flex-start" }}><span className="l">Staff</span><span className="v">{res.staffCapacity}</span></div>
      </div>

      {/* Action builder — configure one action, then drop it on a day below. */}
      <div className="actiongrid">
        {ACTIONS.map((a) => {
          const Ico = ICON[a.type];
          return (
            <button key={a.type} className={`actionbtn${type === a.type ? " sel" : ""}`} onClick={() => setType(a.type)}>
              <Ico size={18} />
              <span className="t">{a.label}</span>
              <span className="c">{a.type === "fundraise" ? `≈+${money(estimatedFundraise(game, player, stateId))}` : a.cost}</span>
            </button>
          );
        })}
      </div>

      <div style={{ marginTop: 10 }}>
        {NEEDS_STATE.includes(type) && (
          <div className="field">
            <label>Target state</label>
            <select value={stateId} onChange={(e) => { setStateId(e.target.value); selectState(e.target.value); }}>
              {states.map((s) => (
                <option key={s.id} value={s.id}>{s.name} ({s.electoralVotes} EV{s.battleground ? " ★" : ""})</option>
              ))}
            </select>
          </div>
        )}

        {type === "advertise" && (
          <>
            <div className="field">
              <label>Ad mode</label>
              <select value={adMode} onChange={(e) => setAdMode(e.target.value as AdMode)}>
                <option value="positive">Positive (raise your appeal)</option>
                <option value="contrast">Contrast (attack the opponent)</option>
                <option value="issue">Issue (raise issue salience)</option>
              </select>
            </div>
            {adMode === "issue" && (
              <div className="field">
                <label>Issue</label>
                <select value={issueId} onChange={(e) => setIssueId(e.target.value as IssueId)}>
                  {ISSUE_IDS.map((id) => <option key={id} value={id}>{ISSUES[id].name}</option>)}
                </select>
              </div>
            )}
            <div className="field">
              <label>Spend: {money(spendM * 1_000_000)}</label>
              <input type="range" min={1} max={30} value={spendM} onChange={(e) => setSpendM(+e.target.value)} />
            </div>
          </>
        )}

        {type === "issue_pivot" && (
          <>
            <div className="field">
              <label>Issue</label>
              <select value={issueId} onChange={(e) => { setIssueId(e.target.value as IssueId); setPivotPos(game.candidates[player].issuePositions[e.target.value as IssueId]); }}>
                {ISSUE_IDS.map((id) => <option key={id} value={id}>{ISSUES[id].name}</option>)}
              </select>
            </div>
            <div className="field">
              <label>Position: {pivotPos.toFixed(2)} (−1 left ↔ +1 right)</label>
              <input type="range" min={-1} max={1} step={0.05} value={pivotPos} onChange={(e) => setPivotPos(+e.target.value)} />
            </div>
          </>
        )}
      </div>

      <PlanBonusStrip plan={plan} />
      {configuredCost > res.cash - committedCash && (
        <p className="muted small" role="status">
          This action costs {money(configuredCost)}. You have {money(res.cash - committedCash)} uncommitted.
        </p>
      )}

      {/* 7-day week. Each day holds up to 3 actions; "+" drops the configured
          action above onto that day. The pool runs out before all 21 slots. */}
      <div className="weekplan">
        {DAYS.map((d) => {
          const items = dayItems(d);
          const full = items.length >= MAX_PER_DAY;
          const canAdd = canQueueAction(game, { ...buildAction(), day: d });
          return (
            <div className={`day${full ? " full" : ""}`} key={d}>
              <div className="day-head">
                <span className="day-name">Day {d}</span>
                <span className="day-slots">
                  {Array.from({ length: MAX_PER_DAY }).map((_, k) => (
                    <span key={k} className={k < items.length ? "dot on" : "dot"} />
                  ))}
                </span>
                <button className="day-add" disabled={!canAdd} onClick={() => addToDay(d)} title={canAdd ? `Add ${type} to Day ${d}` : full ? "Day is full" : used >= pool ? "Action pool is full" : "Not enough available cash"}>
                  <Plus size={13} />
                </button>
              </div>
              <div className="day-actions">
                {items.length === 0 && <span className="day-empty">no events</span>}
                {items.map(({ a, i }) => {
                  const Ico = ICON[a.type];
                  return (
                    <span className="plan-chip" key={i} title={a.type === "advertise" ? `${a.adMode} ads · ${money(a.spend ?? 0)}` : a.type}>
                      <Ico size={12} />
                      {shortDesc(a)}
                      <span className="x" onClick={() => removeQueued(i)}><X size={11} /></span>
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
          disabled={!DAYS.some((d) => canQueueAction(game, { ...buildAction(), day: d }))}
          onClick={() => { for (const d of DAYS) { if (dayItems(d).length < MAX_PER_DAY) { addToDay(d); break; } } }}>
          <TypeIcon size={14} /> Add to next open day
        </button>
        <button className="primary" data-coach="endweek" onClick={onEndWeek} disabled={endWeekDisabled}>
          {endWeekDisabled ? "Resolve event first" : "End Week →"}
        </button>
      </div>

      {preview && (
        <div className="preview">
          Projected with this plan: {game.candidates.dem.shortName} {evPreview} EV
          {myDelta !== 0 && <strong>{` (${myDelta > 0 ? "+" : ""}${myDelta} for you)`}</strong>}
        </div>
      )}
    </div>
  );
}
