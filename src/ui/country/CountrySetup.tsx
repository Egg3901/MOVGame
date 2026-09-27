import { useState } from "react";
import { useCountryStore } from "@store/countryStore";
import { useAuthStore } from "@store/authStore";
import { playablePartiesIn, type CountryBundle } from "@engine/countryGame";
import { ScenarioArt } from "@ui/ScenarioArt";
import type { CountryCode } from "@content/scenarioRegistry";
import { partyColor, partyName, partyShort, partyTextColor } from "./helpers";
import { Avatar } from "@ui/Avatar";
import { Vote, Dices, ChevronLeft, Flag } from "lucide-react";
import type { PartyId } from "@engine/system";
import { BRAND } from "../../brand";
import { DifficultyPicker, type Difficulty } from "@ui/DifficultyPicker";
import { TideBanner, ChallengePartyBanner } from "@ui/TideBanner";
import { DAILY_ROLE_PAIRS } from "@lib/daily";
import { FR_FIRST_ROUND } from "@content/countries/france";

function randomSeed(): string {
  return String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0");
}

export function CountrySetup({ country, onBack, initialElection, initialSeed, initialParty }: {
  country: CountryBundle;
  onBack: () => void;
  initialElection?: string;
  initialSeed?: string;    // prefill only — the player can still edit/reroll
  initialParty?: string;   // party id, e.g. "lpc" / "spd" (Daily Challenge routing)
}) {
  const newGame = useCountryStore((s) => s.newGame);
  const canPlay = useAuthStore((s) => s.canPlay);
  const openModal = useAuthStore((s) => s.openModal);
  const user = useAuthStore((s) => s.user);
  const ids = Object.keys(country.elections).sort((a, b) => Number(b) - Number(a));
  const [election, setElection] = useState(initialElection && country.elections[initialElection] ? initialElection : ids[0]);
  const [party, setParty] = useState<PartyId>(initialParty && country.playable.includes(initialParty) ? initialParty : country.playable[0]);
  const [seed, setSeed] = useState<string>(() => initialSeed ?? randomSeed());
  const [difficulty, setDifficulty] = useState<Difficulty>("normal");
  const data = country.elections[election];
  const playable = playablePartiesIn(country, election);
  const activeParty = playable.includes(party) ? party : playable[0];
  const leader = country.leaders[election]?.[activeParty];

  return (
    <div className="center">
      <div className="setup">
        <div className="setup-eyebrow">
          <span className="mark"><Vote size={18} /></span>{country.flag} {country.label.toUpperCase()} · {data.year}
        </div>
        <div className="title">{BRAND.name}</div>
        <p className="sub">{data.tagline}</p>
        {country.id === "FR" && FR_FIRST_ROUND[election] && (
          <div className="su-summary" style={{ textAlign: "left", marginBottom: 12 }}>
            <strong>Runoff starting point</strong>
            <p className="muted small" style={{ margin: "6px 0" }}>
              The first round is complete. The campaign starts with finalist support and voter transfers already reflected in the regional map.
            </p>
            <div className="small">
              First round: Centre {(FR_FIRST_ROUND[election].ens * 100).toFixed(1)}%, RN {(FR_FIRST_ROUND[election].rn * 100).toFixed(1)}%, left {(FR_FIRST_ROUND[election].lfi * 100).toFixed(1)}%, right {(FR_FIRST_ROUND[election].lr * 100).toFixed(1)}%.
            </div>
          </div>
        )}
        <TideBanner scenarioId={`${country.id.toLowerCase()}-${election}`} />
        {country.id === "FR" && election === "2022" && (
          <div className="su-summary" style={{ marginTop: 12, borderColor: "var(--gold)", textAlign: "left" }}>
            <strong>{activeParty === "ens" ? "Historical favorite" : "Long-shot challenge"}</strong>
            <p className="muted small" style={{ margin: "6px 0 0" }}>
              {activeParty === "ens"
                ? "Macron begins the runoff with a large lead. Defend it while building a stronger finish, or try the closer 2027 race for a tighter contest."
                : "Le Pen begins far behind. Build a national runoff argument to move support across France; regional pushes alone rarely close the gap."}
            </p>
          </div>
        )}
        {(() => {
          const majors = DAILY_ROLE_PAIRS[country.id as keyof typeof DAILY_ROLE_PAIRS] ?? country.playable.slice(0, 2);
          return !majors.includes(activeParty) ? (
            <ChallengePartyBanner partyLabel={partyName(country, activeParty)} />
          ) : null;
        })()}

        <div className="su-panel">
          {ids.length > 1 && (
            <div className="field" style={{ textAlign: "left", margin: "0 0 10px" }}>
              <label>Election</label>
              <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(3, 1fr)" }}>
                {ids.map((id) => {
                  return (
                    <button key={id} type="button"
                      className={`scenario-card has-cover${election === id ? " sel" : ""}`}
                      style={{ alignItems: "stretch", textAlign: "left" }}
                      onClick={() => setElection(id)}>
                      <ScenarioArt
                        scenarioId={`${country.id.toLowerCase()}-${id}`}
                        country={country.id as CountryCode}
                        year={country.elections[id].year}
                      />
                      <span className="scenario-year">{country.elections[id].year}</span>
                      <span className="scenario-match">{country.elections[id].label}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div className="field" style={{ textAlign: "left", margin: "0 0 10px" }}>
            <label>Lead which side? Your leader's traits shape the campaign</label>
            <div className="pick" style={{ gridTemplateColumns: "1fr 1fr" }}>
              {playable.map((p) => {
                const ld = country.leaders[election]?.[p];
                const sel = activeParty === p;
                return (
                  <div key={p} className="candcard" onClick={() => setParty(p)}
                    style={{ cursor: "pointer", borderColor: sel ? partyColor(country, p) : undefined, borderWidth: sel ? 2 : 1, borderStyle: "solid", boxShadow: sel ? "var(--glow-gold)" : undefined }}>
                    <div className="row" style={{ gap: 10 }}>
                      <Avatar name={ld?.name ?? partyName(country, p)} color={partyColor(country, p)} size={40} />
                      <div>
                        <div className="nm" style={{ color: partyTextColor(country, p) }}>{partyName(country, p)}</div>
                        <div className="rm">{ld?.name ?? `${partyShort(country, p)} leader`}</div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
            {leader && (
              <div className="su-summary" style={{ marginTop: 12 }}>
                <div className="su-summary-row"><span className="su-summary-k">Charisma</span><span className="su-summary-v">{leader.charisma}</span></div>
                <div className="su-summary-row"><span className="su-summary-k">Energy</span><span className="su-summary-v">{leader.energy}</span></div>
                <div className="su-summary-row"><span className="su-summary-k">Competence</span><span className="su-summary-v">{leader.competence}</span></div>
                <div className="su-summary-row"><span className="su-summary-k">Machine</span><span className="su-summary-v">{leader.machine}</span></div>
              </div>
            )}
          </div>

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
              <span className="su-summary-v" style={{ color: partyTextColor(country, activeParty) }}>
                {partyName(country, activeParty)}{leader ? ` · ${leader.name}` : ""}
              </span>
            </div>
            <div className="su-summary-row"><span className="su-summary-k">Goal</span><span className="su-summary-v">{data.goalText ?? country.goalText}</span></div>
          </div>
        </div>

        <div className="su-nav">
          <button className="ghost" onClick={onBack}><ChevronLeft size={16} /> All scenarios</button>
          <button
            className="primary su-next"
            onClick={() => {
              const sid = `${country.id.toLowerCase()}-${election}`;
              if (!canPlay(sid)) { openModal(user ? "activate" : "login", sid); return; }
              newGame(country.id, election, activeParty, seed, difficulty);
            }}
          >
            <Flag size={15} /> {canPlay(`${country.id.toLowerCase()}-${election}`)
              ? <>Begin {data.year} as {partyShort(country, activeParty)}</>
              : "🔒 Unlock to play"}
          </button>
        </div>
      </div>
    </div>
  );
}
