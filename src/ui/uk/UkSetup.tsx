import { useState } from "react";
import { useUkStore } from "@store/ukStore";
import { useAuthStore } from "@store/authStore";
import { DifficultyPicker, type Difficulty } from "@ui/DifficultyPicker";
import { UK_ELECTIONS, UK_ELECTION_IDS } from "@content/uk/elections";
import { ScenarioArt } from "@ui/ScenarioArt";
import { UK_PLAYABLE, playablePartiesIn } from "@engine/ukGame";
import { leaderFor } from "@content/uk/leaders";
import { partyName, partyColor, partyShort } from "./parties";
import { Avatar } from "@ui/Avatar";
import { Vote, Dices, ChevronLeft, ChevronRight, Check, Flag } from "lucide-react";
import type { PartyId } from "@engine/system";
import { BRAND } from "../../brand";
import { TideBanner, ChallengePartyBanner } from "@ui/TideBanner";

function randomSeed(): string {
  return String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0");
}

// Paywall-aware Begin: every UK election is part of the UK pack — locked
// elections route to login/activation instead of starting a game.
function UkBeginButton({ election, party, seed, year, onBegin }: {
  election: string; party: PartyId; seed: string; year: number; onBegin: () => void;
}) {
  void seed;
  const canPlay = useAuthStore((s) => s.canPlay);
  const openModal = useAuthStore((s) => s.openModal);
  const unlocked = canPlay(`uk-${election}`);
  return (
    <button
      className="primary su-next"
      onClick={() => (unlocked ? onBegin() : openModal("activate", `uk-${election}`))}
    >
      <Flag size={15} /> {unlocked ? `Begin ${year} as ${partyShort(party)}` : "🔒 Unlock to play"}
    </button>
  );
}

type StepId = 0 | 1 | 2;
const STEPS: { label: string; hint: string }[] = [
  { label: "The Election", hint: "Choose your year" },
  { label: "The Party", hint: "Pick who you lead" },
  { label: "The Briefing", hint: "Set the rules" },
];

// The two front-runners of an election, for the contenders preview.
function contenders(electionId: string): { party: PartyId; leader: string }[] {
  const data = UK_ELECTIONS[electionId];
  const totals: Record<string, number> = {};
  for (const r of Object.values(data.regions))
    for (const [p, s] of Object.entries(r.s)) totals[p] = (totals[p] ?? 0) + (s as number);
  return Object.keys(totals)
    .filter((p) => UK_PLAYABLE.includes(p))
    .sort((a, b) => totals[b] - totals[a])
    .slice(0, 4)
    .map((p) => ({ party: p as PartyId, leader: leaderFor(electionId, p, partyName(p)).name }));
}

export function UkSetup({ onBack, initialElection, initialSeed, initialParty }: {
  onBack: () => void;
  initialElection?: string;
  initialSeed?: string;    // prefill only — the player can still edit/reroll
  initialParty?: string;   // party id, e.g. "lab" / "con" (Daily Challenge routing)
}) {
  const newGame = useUkStore((s) => s.newGame);
  const ids = [...UK_ELECTION_IDS].sort((a, b) => Number(b) - Number(a));
  const [step, setStep] = useState<StepId>(0);
  const [election, setElection] = useState(initialElection && UK_ELECTIONS[initialElection] ? initialElection : ids[0]);
  const [party, setParty] = useState<PartyId>(initialParty && UK_PLAYABLE.includes(initialParty) ? initialParty : "lab");
  const [seed, setSeed] = useState<string>(() => initialSeed ?? randomSeed());
  const [difficulty, setDifficulty] = useState<Difficulty>("normal");
  const data = UK_ELECTIONS[election];
  const field = contenders(election);
  const playable = playablePartiesIn(election);
  // Keep the chosen party valid for the selected year (no Reform in 1979).
  const activeParty = playable.includes(party) ? party : playable[0];
  const leader = leaderFor(election, activeParty, partyName(activeParty));

  const chooseElection = (id: string) => {
    setElection(id);
    const next = playablePartiesIn(id);
    if (!next.includes(party)) setParty(next[0]);
  };

  return (
    <div className="center">
      <div className="setup">
        <div className="setup-eyebrow">
          <span className="mark"><Vote size={18} /></span>UNITED KINGDOM · GENERAL ELECTION
        </div>
        <div className="title">{BRAND.name}</div>
        <p className="sub">{data.tagline}</p>
        <TideBanner scenarioId={`uk-${election}`} />
        {activeParty !== "lab" && activeParty !== "con" && (
          <ChallengePartyBanner partyLabel={partyName(activeParty)} />
        )}

        <div className="su-stepper" role="tablist">
          {STEPS.map((s, i) => {
            const state = i === step ? "cur" : i < step ? "done" : "todo";
            return (
              <button key={s.label} type="button" className={`su-step ${state}`}
                onClick={() => i < step && setStep(i as StepId)} disabled={i > step}>
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
          {step === 0 && (
            <div className="field" style={{ textAlign: "left", margin: 0 }}>
              <label>Election year: each ships its real regional results & leaders</label>
              <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(3, 1fr)" }}>
                {ids.map((id) => {
                  return (
                    <button key={id} type="button"
                      className={`scenario-card has-cover${election === id ? " sel" : ""}`}
                      style={{ alignItems: "stretch", textAlign: "left" }}
                      onClick={() => chooseElection(id)}>
                      <ScenarioArt
                        scenarioId={`uk-${id}`}
                        country="UK"
                        year={UK_ELECTIONS[id].year}
                      />
                      <span className="scenario-year">{UK_ELECTIONS[id].year}</span>
                      <span className="scenario-match">{UK_ELECTIONS[id].label}</span>
                    </button>
                  );
                })}
              </div>
              <div className="su-summary" style={{ marginTop: 14 }}>
                <div className="su-summary-row"><span className="su-summary-k">Contenders</span></div>
                <div className="vp-roster">
                  {field.map(({ party: p, leader: nm }) => (
                    <span key={p} className="vp-card" style={{ cursor: "default" }}>
                      <Avatar name={nm} color={partyColor(p)} size={20} />
                      <span className="vp-name">{nm} <span className="muted">({partyShort(p)})</span></span>
                    </span>
                  ))}
                </div>
              </div>
            </div>
          )}

          {step === 1 && (
            <div className="field" style={{ textAlign: "left", margin: 0 }}>
              <label>Lead which party? Your leader's traits shape your campaign</label>
              <div className="pick" style={{ gridTemplateColumns: "1fr 1fr" }}>
                {playable.map((p) => {
                  const ld = leaderFor(election, p, partyName(p));
                  const sel = activeParty === p;
                  return (
                    <div key={p} className="candcard" onClick={() => setParty(p)}
                      style={{ cursor: "pointer", borderColor: sel ? partyColor(p) : undefined, borderWidth: sel ? 2 : 1, borderStyle: "solid", boxShadow: sel ? "var(--glow-gold)" : undefined }}>
                      <div className="row" style={{ gap: 10 }}>
                        <Avatar name={ld.name} color={partyColor(p)} size={40} />
                        <div>
                          <div className="nm" style={{ color: partyColor(p) }}>{partyName(p)}</div>
                          <div className="rm">{ld.name}</div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
              <div className="su-summary" style={{ marginTop: 12 }}>
                <div className="su-summary-row"><span className="su-summary-k">Charisma</span><span className="su-summary-v">{leader.charisma}</span></div>
                <div className="su-summary-row"><span className="su-summary-k">Energy</span><span className="su-summary-v">{leader.energy}</span></div>
                <div className="su-summary-row"><span className="su-summary-k">Competence</span><span className="su-summary-v">{leader.competence}</span></div>
                <div className="su-summary-row"><span className="su-summary-k">Party machine</span><span className="su-summary-v">{leader.machine}</span></div>
              </div>
            </div>
          )}

          {step === 2 && (
            <>
              <DifficultyPicker value={difficulty} onChange={setDifficulty} />
              <div className="field" style={{ textAlign: "left" }}>
                <label>Seed: same seed replays the same events & RNG</label>
                <div className="su-seed">
                  <input type="text" value={seed} onChange={(e) => setSeed(e.target.value)} />
                  <button type="button" className="ghost su-reroll" title="Reroll seed" onClick={() => setSeed(randomSeed())}>
                    <Dices size={16} /> Reroll
                  </button>
                </div>
              </div>
              <div className="su-summary">
                <div className="su-summary-row">
                  <span className="su-summary-k">Leading</span>
                  <span className="su-summary-v" style={{ color: partyColor(activeParty) }}>{partyName(activeParty)} · {leader.name}</span>
                </div>
                <div className="su-summary-row">
                  <span className="su-summary-k">Election</span>
                  <span className="su-summary-v">{data.label}</span>
                </div>
                <div className="su-summary-row">
                  <span className="su-summary-k">Goal</span>
                  <span className="su-summary-v">{data.goalText ?? `${data.majority?.threshold ?? 326} of ${data.majority?.total ?? 650} seats`}</span>
                </div>
              </div>
            </>
          )}
        </div>

        <div className="su-nav">
          {step > 0 ? (
            <button className="ghost" onClick={() => setStep((step - 1) as StepId)}><ChevronLeft size={16} /> Back</button>
          ) : (
            <button className="ghost" onClick={onBack}><ChevronLeft size={16} /> Battleground</button>
          )}
          {step < 2 ? (
            <button className="primary su-next" onClick={() => setStep((step + 1) as StepId)}>Next <ChevronRight size={16} /></button>
          ) : (
            <UkBeginButton election={election} party={activeParty} seed={seed} year={data.year}
              onBegin={() => newGame(election, activeParty, seed, difficulty)} />
          )}
        </div>
      </div>
    </div>
  );
}
