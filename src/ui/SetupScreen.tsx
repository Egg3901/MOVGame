import { lazy, Suspense, useState } from "react";
import { useGameStore, type Difficulty } from "@store/gameStore";
import { useAuthStore } from "@store/authStore";
import { SCENARIOS, SCENARIO_IDS } from "@content/scenarios";
import { PAYWALL_ENABLED } from "@content/scenarioRegistry";
import { ScenarioArt } from "@ui/ScenarioArt";
import { defaultRunningMate } from "@content/runningMates";
import { STAFF_POOL, MAX_STAFF, STAFF_BY_ID } from "@content/staff";
import { mateBonusChips } from "@ui/labels";
import { Avatar } from "@ui/Avatar";
import { Spinner } from "@ui/Skeleton";

// These only ever mount after a user opens the guide / candidate bios /
// scenario editor from the setup screen — never on first paint — so they
// load on demand and stay off the critical setup chunk.
const GuidePage = lazy(() => import("@ui/GuidePage").then((m) => ({ default: m.GuidePage })));
const EditorScreen = lazy(() => import("@ui/EditorScreen").then((m) => ({ default: m.EditorScreen })));
const CandidateScreen = lazy(() => import("@ui/CandidateScreen").then((m) => ({ default: m.CandidateScreen })));
const SetupModalFallback = () => (
  <div className="overlay"><div className="modal"><p className="sub"><Spinner /></p></div></div>
);
import type { CandidateId, GameState } from "@engine/index";
import { BRAND } from "../brand";
import { TideBanner } from "@ui/TideBanner";
import {
  Vote,
  Dices,
  ChevronLeft,
  ChevronRight,
  Check,
  Flag,
  Swords,
  Crosshair,
  CalendarDays,
  Shuffle,
  Lock,
  Briefcase,
  FlaskConical,
} from "lucide-react";

// A fresh random seed every time the setup screen mounts (and on every reroll),
// so no two campaigns share an RNG stream unless the player deliberately types
// one in. Six digits reads cleanly in the input and as a shareable token.
function randomSeed(): string {
  return String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0");
}

type StepId = 0 | 1 | 2 | 3;
const STEPS: { label: string; hint: string }[] = [
  { label: "The Election", hint: "Choose your year" },
  { label: "The Ticket", hint: "Pick your campaign" },
  { label: "The War Room", hint: "Hire your staff" },
  { label: "The Briefing", hint: "Set the rules" },
];

const DIFFICULTIES: {
  id: Difficulty;
  name: string;
  blurb: string;
  Icon: typeof Flag;
}[] = [
  { id: "easy", name: "Easy", blurb: "Favorable climate, a bigger war chest, and a sloppy opponent. You can win any year.", Icon: Flag },
  { id: "normal", name: "Normal", blurb: "A fair fight on the real map, against a disciplined campaign.", Icon: Swords },
  { id: "hard", name: "Hard", blurb: "The historical map, no help, a ruthless opponent. History is brutal.", Icon: Crosshair },
];

// Campaign length: how many weekly turns the race runs before Election Day.
// Standard (9 weeks) is the historically calibrated length used for scoring
// and the Daily Challenge; Short and Long are casual variants for a quicker
// or longer run.
const CAMPAIGN_LENGTHS: { turns: number; name: string; blurb: string }[] = [
  { turns: 5, name: "Short (5 weeks)", blurb: "A sprint to Election Day. Fewer events, faster games." },
  { turns: 9, name: "Standard (9 weeks)", blurb: "The full historical calendar. Used for the leaderboard." },
  { turns: 14, name: "Long (14 weeks)", blurb: "A drawn out race with room to build momentum slowly." },
];

// The seven battlegrounds offered for the "What If" prior flip, plus two fun ones.
const WHAT_IF_STATES = ["", "TX", "FL", "OH", "PA", "MI", "WI", "GA", "AZ", "NC", "NY"];

export type EditorLaunchTarget = { kind: "uk" } | { kind: "country"; countryId: string };

export function SetupScreen({ initialScenarioId, initialSeed, initialParty, onExit, onLaunch }: {
  initialScenarioId?: string;
  initialSeed?: string;    // prefill only — the player can still edit/reroll
  initialParty?: string;   // "dem" | "rep" (Daily Challenge routing)
  onExit?: () => void;
  // Editor launched a UK/country custom game: App switches to that engine's shell.
  onLaunch?: (target: EditorLaunchTarget) => void;
}) {
  const newGame = useGameStore((s) => s.newGame);
  const canPlay = useAuthStore((s) => s.canPlay);
  const openModal = useAuthStore((s) => s.openModal);
  const [step, setStep] = useState<StepId>(0);
  const [scenarioId, setScenarioId] = useState<string>(initialScenarioId ?? "2024");
  const [pick, setPick] = useState<CandidateId>(initialParty === "rep" ? "rep" : "dem");
  const scenario = SCENARIOS[scenarioId];
  const ticket = pick === "dem" ? scenario.dem : scenario.rep;
  const [mate, setMate] = useState<string>(() => defaultRunningMate((initialParty === "rep" ? scenario.rep : scenario.dem).runningMates).id);
  const [difficulty, setDifficulty] = useState<Difficulty>("normal");
  // The Daily Challenge is a shared, scored race, so its length is pinned to
  // Standard regardless of what's picked here (gameStore enforces this too).
  const isDaily = typeof initialSeed === "string" && initialSeed.startsWith("daily");
  const [totalTurns, setTotalTurns] = useState<number>(9);
  const [eventMode, setEventMode] = useState<"historical" | "plausible">("historical");
  const [staff, setStaff] = useState<string[]>([]);
  const [whatIfState, setWhatIfState] = useState<string>("");
  const [mirrorMatch, setMirrorMatch] = useState(false);
  const [pandemic, setPandemic] = useState(false);
  const [seed, setSeed] = useState<string>(() => initialSeed ?? randomSeed());
  const [guideOpen, setGuideOpen] = useState(false);
  const [candOpen, setCandOpen] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);

  const globalId = (id: string) => `us-${id}`;
  const unlocked = canPlay(globalId(scenarioId));

  const chooseScenario = (id: string) => {
    if (!canPlay(globalId(id))) {
      openModal("activate", globalId(id));
      return;
    }
    setScenarioId(id);
    setPick("dem");
    setMate(defaultRunningMate(SCENARIOS[id].dem.runningMates).id);
  };
  const choosePick = (id: CandidateId) => {
    setPick(id);
    const t = id === "dem" ? scenario.dem : scenario.rep;
    setMate(defaultRunningMate(t.runningMates).id);
  };
  const roster = ticket.runningMates;
  const selectedMate = roster.find((m) => m.id === mate) ?? roster[0];

  const opponent = pick === "dem" ? scenario.rep : scenario.dem;

  const toggleStaff = (id: string) => {
    setStaff((cur) =>
      cur.includes(id) ? cur.filter((x) => x !== id) : cur.length >= MAX_STAFF ? cur : [...cur, id],
    );
  };
  const weeklyPayroll = staff.reduce((s, id) => s + (STAFF_BY_ID[id]?.salaryPerWeek ?? 0), 0);

  const modifiers: GameState["modifiers"] = {
    ...(whatIfState ? { whatIfState } : {}),
    ...(mirrorMatch ? { mirrorMatch: true } : {}),
    ...(pandemic ? { pandemic: true } : {}),
  };

  const begin = () => {
    if (!unlocked) {
      openModal("activate", globalId(scenarioId));
      return;
    }
    newGame({
      seed, scenario: scenarioId, playerCandidate: pick, difficulty, runningMate: mate, eventMode,
      staff, modifiers: Object.keys(modifiers ?? {}).length > 0 ? modifiers : undefined,
      totalTurns: isDaily ? 9 : totalTurns,
    });
  };

  return (
    <div className="center">
      <div className="setup">
        <div className="setup-eyebrow">
          <span className="mark"><Vote size={18} /></span>PRESIDENTIAL CAMPAIGN
        </div>
        <div className="title">{BRAND.name}</div>
        <p className="sub">{scenario.tagline}</p>
        <TideBanner scenarioId={`us-${scenarioId}`} />

        {/* ── Stepper ──────────────────────────────────────────────── */}
        <div className="su-stepper" role="tablist">
          {STEPS.map((s, i) => {
            const state = i === step ? "cur" : i < step ? "done" : "todo";
            return (
              <button
                key={s.label}
                type="button"
                className={`su-step ${state}`}
                onClick={() => i < step && setStep(i as StepId)}
                disabled={i > step}
              >
                <span className="su-step-dot">{i < step ? <Check size={14} /> : i + 1}</span>
                <span className="su-step-text">
                  <span className="su-step-label">{s.label}</span>
                  <span className="su-step-hint">{s.hint}</span>
                </span>
              </button>
            );
          })}
        </div>

        <div className="su-panel" key={step}>
          {/* ── Step 1: Election ───────────────────────────────────── */}
          {step === 0 && (
            <div className="field" style={{ textAlign: "left", margin: 0 }}>
              <label>Election year: each cycle ships its own map, decks & tickets</label>
              <div className="scenario-grid">
                {SCENARIO_IDS.map((id) => {
                  const s = SCENARIOS[id];
                  const open = canPlay(globalId(id));
                  return (
                    <button
                      key={id}
                      type="button"
                      className={`scenario-card has-cover${scenarioId === id ? " sel" : ""}`}
                      style={{ ...(open ? undefined : { opacity: 0.6 }), alignItems: "stretch", textAlign: "left" }}
                      onClick={() => chooseScenario(id)}
                    >
                      <ScenarioArt
                        scenarioId={globalId(id)}
                        country="US"
                        year={s.year}
                      />
                      <span className="scenario-year" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                        {s.year}{!open && <Lock size={13} style={{ color: "var(--gold)" }} />}
                      </span>
                      <span className="scenario-match">{s.dem.shortName} v. {s.rep.shortName}</span>
                    </button>
                  );
                })}
              </div>
              {PAYWALL_ENABLED && (
                <p className="muted small" style={{ marginTop: 8 }}>
                  🔒 Locked years are part of the <strong>United States</strong> bundle. Pick one to buy it or redeem a code.
                </p>
              )}
            </div>
          )}

          {/* ── Step 2: Ticket ─────────────────────────────────────── */}
          {step === 1 && (
            <>
              <div className="field" style={{ textAlign: "left", margin: "0 0 4px" }}>
                <label>Your candidate: the campaign you'll run</label>
              </div>
              <div className="pick">
                {(["dem", "rep"] as CandidateId[]).map((id) => {
                  const c = id === "dem" ? scenario.dem : scenario.rep;
                  const selClass = pick === id ? (id === "dem" ? " sel-d" : " sel-r") : "";
                  return (
                    <div key={id} className={`candcard${selClass}`} onClick={() => choosePick(id)} style={{ cursor: "pointer" }}>
                      <div className="row" style={{ gap: 10 }}>
                        <Avatar name={c.name} color={c.color} size={44} />
                        <div>
                          <div className="nm" style={{ color: c.color }}>{c.name}</div>
                          <div className="rm">{c.party}</div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>

              <div className="field" style={{ textAlign: "left" }}>
                <label>Running mate: shapes your coalition</label>
                <div className="vp-roster">
                  {roster.map((m) => (
                    <button
                      key={m.id}
                      type="button"
                      className={`vp-card${mate === m.id ? " sel" : ""}`}
                      onClick={() => setMate(m.id)}
                    >
                      <Avatar name={m.name} color={ticket.color} size={20} />
                      <span className="vp-name">{m.name}</span>
                      {m.historical && <span className="vp-star" title="Historical pick">★</span>}
                    </button>
                  ))}
                </div>
                <p className="vp-blurb">{selectedMate.blurb}</p>
                <div className="vp-bonuses">
                  {mateBonusChips(selectedMate).map((label, i) => (
                    <span key={i} className="chip up">{label}</span>
                  ))}
                </div>
              </div>
            </>
          )}

          {/* ── Step 3: War Room (staff) ───────────────────────────── */}
          {step === 2 && (
            <div className="field" style={{ textAlign: "left", margin: 0 }}>
              <label>
                <Briefcase size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
                Hire up to {MAX_STAFF} staffers: passive bonuses, weekly salaries, real loyalty
              </label>
              <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(2, 1fr)" }}>
                {STAFF_POOL.map((s) => {
                  const hired = staff.includes(s.id);
                  const full = !hired && staff.length >= MAX_STAFF;
                  return (
                    <button
                      key={s.id}
                      type="button"
                      className={`scenario-card${hired ? " sel" : ""}`}
                      style={{ textAlign: "left", alignItems: "flex-start", opacity: full ? 0.5 : 1 }}
                      onClick={() => toggleStaff(s.id)}
                      disabled={full}
                    >
                      <span className="scenario-year" style={{ fontSize: 14 }}>
                        {s.emoji} {s.name} {hired && "✓"}
                      </span>
                      <span className="scenario-match">{s.role} · ${(s.salaryPerWeek / 1000).toFixed(0)}k/week</span>
                      <span className="muted small" style={{ fontSize: 11, lineHeight: 1.4 }}>{s.blurb}</span>
                    </button>
                  );
                })}
              </div>
              <div className="su-summary" style={{ marginTop: 10 }}>
                <div className="su-summary-row">
                  <span className="su-summary-k">Weekly payroll</span>
                  <span className="su-summary-v">{weeklyPayroll > 0 ? `$${(weeklyPayroll / 1000).toFixed(0)}k / week` : "None (running lean)"}</span>
                </div>
                <div className="su-summary-row">
                  <span className="su-summary-k">Loyalty</span>
                  <span className="su-summary-v muted">Fall far behind in the projection and the mercenaries may walk.</span>
                </div>
              </div>
            </div>
          )}

          {/* ── Step 4: Briefing ───────────────────────────────────── */}
          {step === 3 && (
            <>
              <div className="field" style={{ textAlign: "left", margin: "0 0 10px" }}>
                <label>Events</label>
                <div className="su-optgrid">
                  <button type="button" className={`su-opt${eventMode === "historical" ? " sel" : ""}`} onClick={() => setEventMode("historical")}>
                    <CalendarDays size={16} className="su-opt-icon" />
                    <span className="su-opt-name">Historical</span>
                    <span className="su-opt-blurb">The real beats of {scenario.year}, in order.</span>
                  </button>
                  <button type="button" className={`su-opt${eventMode === "plausible" ? " sel" : ""}`} onClick={() => setEventMode("plausible")}>
                    <Shuffle size={16} className="su-opt-icon" />
                    <span className="su-opt-name">Random / plausible</span>
                    <span className="su-opt-blurb">A shuffled deck, different every run.</span>
                  </button>
                </div>
              </div>

              <div className="field" style={{ textAlign: "left", margin: "0 0 10px" }}>
                <label>Difficulty: AI opponent strength</label>
                <div className="su-optgrid su-optgrid-3">
                  {DIFFICULTIES.map(({ id, name, blurb, Icon }) => (
                    <button
                      key={id}
                      type="button"
                      className={`su-opt${difficulty === id ? " sel" : ""}`}
                      onClick={() => setDifficulty(id)}
                    >
                      <Icon size={16} className="su-opt-icon" />
                      <span className="su-opt-name">{name}</span>
                      <span className="su-opt-blurb">{blurb}</span>
                    </button>
                  ))}
                </div>
              </div>

              {!isDaily && (
                <div className="field" style={{ textAlign: "left", margin: "0 0 10px" }}>
                  <label>
                    <CalendarDays size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
                    Campaign length: how many weeks until Election Day
                  </label>
                  <div className="su-optgrid su-optgrid-3">
                    {CAMPAIGN_LENGTHS.map(({ turns, name, blurb }) => (
                      <button
                        key={turns}
                        type="button"
                        className={`su-opt${totalTurns === turns ? " sel" : ""}`}
                        onClick={() => setTotalTurns(turns)}
                      >
                        <CalendarDays size={16} className="su-opt-icon" />
                        <span className="su-opt-name">{name}</span>
                        <span className="su-opt-blurb">{blurb}</span>
                      </button>
                    ))}
                  </div>
                  {totalTurns !== 9 && (
                    <p className="muted small" style={{ marginTop: 6 }}>
                      Short and Long games are for fun and do not count toward the leaderboard. Play Standard for a scored run.
                    </p>
                  )}
                </div>
              )}

              <div className="field" style={{ textAlign: "left", margin: "0 0 10px" }}>
                <label>
                  <FlaskConical size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
                  Scenario modifiers: free ways to remix the race
                </label>
                <div className="su-optgrid su-optgrid-3">
                  <div className="su-opt" style={{ cursor: "default" }}>
                    <span className="su-opt-name">What If…</span>
                    <span className="su-opt-blurb">Turn one state into a pure tossup.</span>
                    <select value={whatIfState} onChange={(e) => setWhatIfState(e.target.value)} style={{ marginTop: 6, width: "100%" }}>
                      {WHAT_IF_STATES.map((s) => <option key={s} value={s}>{s === "" ? "Off" : `${s} becomes a swing state`}</option>)}
                    </select>
                  </div>
                  <button type="button" className={`su-opt${mirrorMatch ? " sel" : ""}`} onClick={() => setMirrorMatch((v) => !v)}>
                    <span className="su-opt-name">Mirror Match</span>
                    <span className="su-opt-blurb">Underdog boost: +$40M and +2 weekly actions. Rewrite history.</span>
                  </button>
                  <button type="button" className={`su-opt${pandemic ? " sel" : ""}`} onClick={() => setPandemic((v) => !v)}>
                    <span className="su-opt-name">Pandemic Mode</span>
                    <span className="su-opt-blurb">COVID-era dynamics in any year: crisis management on the ballot.</span>
                  </button>
                </div>
              </div>

              <div className="field" style={{ textAlign: "left" }}>
                <label>Seed: same seed replays the same events & RNG</label>
                <div className="su-seed">
                  <input type="text" value={seed} onChange={(e) => setSeed(e.target.value)} />
                  <button type="button" className="ghost su-reroll" title="Reroll seed" onClick={() => setSeed(randomSeed())}>
                    <Dices size={16} /> Reroll
                  </button>
                </div>
              </div>

              {/* ── Review summary ───────────────────────────────────── */}
              <div className="su-summary">
                <div className="su-summary-row">
                  <span className="su-summary-k">Running as</span>
                  <span className="su-summary-v" style={{ color: ticket.color }}>{ticket.name} / {selectedMate.name}</span>
                </div>
                <div className="su-summary-row">
                  <span className="su-summary-k">Against</span>
                  <span className="su-summary-v" style={{ color: opponent.color }}>{opponent.name}</span>
                </div>
                <div className="su-summary-row">
                  <span className="su-summary-k">War room</span>
                  <span className="su-summary-v">{staff.length > 0 ? staff.map((id) => STAFF_BY_ID[id]?.emoji).join(" ") : "No hires"}</span>
                </div>
                <div className="su-summary-row">
                  <span className="su-summary-k">Rules</span>
                  <span className="su-summary-v">
                    {scenario.year} · {eventMode === "historical" ? "Historical" : "Plausible"} · {DIFFICULTIES.find((d) => d.id === difficulty)!.name}
                    {!isDaily && totalTurns !== 9 && ` · ${CAMPAIGN_LENGTHS.find((c) => c.turns === totalTurns)!.name}`}
                    {whatIfState && ` · What-if ${whatIfState}`}{mirrorMatch && " · Mirror"}{pandemic && " · Pandemic"}
                  </span>
                </div>
              </div>
            </>
          )}
        </div>

        {/* ── Navigation ─────────────────────────────────────────────── */}
        <div className="su-nav">
          {step > 0 ? (
            <button className="ghost" onClick={() => setStep((step - 1) as StepId)}>
              <ChevronLeft size={16} /> Back
            </button>
          ) : onExit ? (
            <button className="ghost" onClick={onExit}>
              <ChevronLeft size={16} /> All scenarios
            </button>
          ) : (
            <span className="su-nav-spacer" />
          )}
          {step < 3 ? (
            <button className="primary su-next" onClick={() => setStep((step + 1) as StepId)}>
              Next <ChevronRight size={16} />
            </button>
          ) : (
            <button className="primary su-next" onClick={begin}>
              {unlocked ? `Begin ${scenario.year} as ${ticket.shortName} →` : "🔒 Unlock to play"}
            </button>
          )}
        </div>

        <div className="su-links">
          <button className="su-link" onClick={() => setGuideOpen(true)}>How to Play</button>
          <span className="su-link-sep">·</span>
          <button className="su-link" onClick={() => setCandOpen(true)}>Candidates</button>
          <span className="su-link-sep">·</span>
          <button className="su-link" onClick={() => setEditorOpen(true)}>Create custom</button>
        </div>
      </div>
      <Suspense fallback={<SetupModalFallback />}>
        {editorOpen && <EditorScreen onClose={() => setEditorOpen(false)} onLaunch={onLaunch} />}
        {guideOpen && <GuidePage onClose={() => setGuideOpen(false)} />}
        {candOpen && <CandidateScreen scenarioId={scenarioId} onClose={() => setCandOpen(false)} />}
      </Suspense>
    </div>
  );
}
