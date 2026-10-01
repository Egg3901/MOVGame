import { useEffect, useRef, useState } from "react";
import { useGameStore } from "@store/gameStore";
import { useUkStore } from "@store/ukStore";
import { useCountryStore } from "@store/countryStore";
import { ISSUES } from "@content/issues";
import { BLOCS } from "@content/blocs";
import { UK_ELECTIONS } from "@content/uk/elections";
import { COUNTRIES } from "@content/countries";
import type { BlocId, IssueId } from "@engine/types";
import {
  makeDefaultCustomScenario,
  makeDefaultMultiparty,
  multipartyPartiesFor,
  validateCustomScenario,
  registerCustomScenario,
  serializeCustomScenario,
  parseCustomScenario,
  newCustomId,
  buildUkCustomGame,
  buildCountryCustomGame,
  baseScenarioId,
  baseScenarioIdForCustom,
  TRAIT_KEYS,
  MP_TRAIT_KEYS,
  type CustomEngine,
  type CustomScenario,
  type CustomTicketInput,
  type CustomPartyInput,
} from "@content/customScenario";
import { packForScenario } from "@content/packs";
import { useAuthStore } from "@store/authStore";
import type { EditorLaunchTarget } from "@ui/SetupScreen";
import { customScenarioStore } from "@persistence/local";
import { X, Dices, Trash2, Play, Download, Upload, Save, Lock } from "lucide-react";

const MP_TRAIT_LABELS: Record<string, string> = {
  charisma: "Charisma",
  energy: "Energy",
  competence: "Competence",
  machine: "Party machine",
};

// Base elections offered per engine, newest first.
function ukElectionOptions(): { id: string; label: string }[] {
  return Object.keys(UK_ELECTIONS)
    .sort((a, b) => UK_ELECTIONS[b].year - UK_ELECTIONS[a].year)
    .map((id) => ({ id, label: `${UK_ELECTIONS[id].year} · ${UK_ELECTIONS[id].label}` }));
}
function countryElectionOptions(countryId: string): { id: string; label: string }[] {
  const c = COUNTRIES[countryId];
  if (!c) return [];
  return Object.keys(c.elections)
    .sort((a, b) => c.elections[b].year - c.elections[a].year)
    .map((id) => ({ id, label: `${c.elections[id].year} · ${c.elections[id].label}` }));
}

const TRAIT_LABELS: Record<string, string> = {
  charisma: "Charisma",
  energy: "Energy",
  debatePrep: "Debate prep",
  intelligence: "Intelligence",
  policyKnowledge: "Policy knowledge",
  debatingSkill: "Debating skill",
  fundraisingProwess: "Fundraising",
};

function num(e: React.ChangeEvent<HTMLInputElement>): number {
  return Number(e.target.value);
}

function TicketForm({
  side,
  ticket,
  onChange,
}: {
  side: "dem" | "rep";
  ticket: CustomTicketInput;
  onChange: (t: CustomTicketInput) => void;
}) {
  const set = (patch: Partial<CustomTicketInput>) => onChange({ ...ticket, ...patch });
  const setTrait = (k: string, v: number) => set({ traits: { ...ticket.traits, [k]: v } });
  const setIssue = (id: IssueId, v: number) => set({ issuePositions: { ...ticket.issuePositions, [id]: v } });
  const setFav = (id: BlocId, v: number) => {
    const next = { ...ticket.baseFavorability };
    if (v === 0) delete next[id];
    else next[id] = v;
    set({ baseFavorability: next });
  };

  return (
    <div className="field" style={{ textAlign: "left" }}>
      <label style={{ color: ticket.color }}>
        {side === "dem" ? "Your candidate" : "The opponent"}
      </label>

      <div className="ed-grid2">
        <div>
          <span className="muted small">Full name</span>
          <input type="text" value={ticket.name} maxLength={40} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <span className="muted small">Short name</span>
          <input type="text" value={ticket.shortName} maxLength={16} onChange={(e) => set({ shortName: e.target.value })} />
        </div>
        <div>
          <span className="muted small">Party</span>
          <select value={ticket.party} onChange={(e) => set({ party: e.target.value as CustomTicketInput["party"] })} style={{ width: "100%" }}>
            <option value="Democratic">Democratic</option>
            <option value="Republican">Republican</option>
          </select>
        </div>
        <div>
          <span className="muted small">Color</span>
          <input type="color" value={ticket.color} onChange={(e) => set({ color: e.target.value })} style={{ width: "100%", height: 34, padding: 2 }} />
        </div>
      </div>

      <div style={{ marginTop: 10 }}>
        <span className="muted small">Traits (0 to 100)</span>
        {TRAIT_KEYS.map((k) => (
          <div key={k} className="ed-slider">
            <span className="ed-slider-k">{TRAIT_LABELS[k]}</span>
            <input type="range" min={0} max={100} step={1} value={ticket.traits[k]} onChange={(e) => setTrait(k, num(e))} />
            <span className="ed-slider-v">{ticket.traits[k]}</span>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 10 }}>
        <span className="muted small">Issue positions (left -1 to right +1)</span>
        {(Object.keys(ISSUES) as IssueId[]).map((id) => (
          <div key={id} className="ed-slider">
            <span className="ed-slider-k">{ISSUES[id].name}</span>
            <input type="range" min={-1} max={1} step={0.05} value={ticket.issuePositions[id]} onChange={(e) => setIssue(id, num(e))} />
            <span className="ed-slider-v">{ticket.issuePositions[id].toFixed(2)}</span>
          </div>
        ))}
      </div>

      <details style={{ marginTop: 10 }}>
        <summary className="muted small" style={{ cursor: "pointer" }}>Bloc favorability (optional, -1 to +1)</summary>
        {(Object.keys(BLOCS) as BlocId[]).map((id) => (
          <div key={id} className="ed-slider">
            <span className="ed-slider-k">{BLOCS[id].name}</span>
            <input type="range" min={-1} max={1} step={0.02} value={ticket.baseFavorability[id] ?? 0} onChange={(e) => setFav(id, num(e))} />
            <span className="ed-slider-v">{(ticket.baseFavorability[id] ?? 0).toFixed(2)}</span>
          </div>
        ))}
      </details>
    </div>
  );
}

// One editable party card for the UK/country multiparty editor.
function PartyRow({
  party,
  onChange,
}: {
  party: CustomPartyInput;
  onChange: (p: CustomPartyInput) => void;
}) {
  const set = (patch: Partial<CustomPartyInput>) => onChange({ ...party, ...patch });
  return (
    <div className="field" style={{ textAlign: "left", borderLeft: `4px solid ${party.color}`, paddingLeft: 10 }}>
      <div className="ed-grid2">
        <div>
          <span className="muted small">Party name</span>
          <input type="text" value={party.name} maxLength={48} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <span className="muted small">Short name</span>
          <input type="text" value={party.shortName} maxLength={16} onChange={(e) => set({ shortName: e.target.value })} />
        </div>
        <div>
          <span className="muted small">Leader</span>
          <input type="text" value={party.leaderName} maxLength={48} onChange={(e) => set({ leaderName: e.target.value })} />
        </div>
        <div>
          <span className="muted small">Color</span>
          <input type="color" value={party.color} onChange={(e) => set({ color: e.target.value })} style={{ width: "100%", height: 34, padding: 2 }} />
        </div>
      </div>

      <div style={{ marginTop: 10 }}>
        <span className="muted small">Leader traits (0 to 100)</span>
        {MP_TRAIT_KEYS.map((k) => (
          <div key={k} className="ed-slider">
            <span className="ed-slider-k">{MP_TRAIT_LABELS[k]}</span>
            <input type="range" min={0} max={100} step={1} value={party[k]} onChange={(e) => set({ [k]: num(e) } as Partial<CustomPartyInput>)} />
            <span className="ed-slider-v">{party[k]}</span>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 10 }}>
        <span className="muted small">Base support (weaker -1 to +1 stronger)</span>
        <div className="ed-slider">
          <span className="ed-slider-k">Starting strength</span>
          <input type="range" min={-1} max={1} step={0.05} value={party.baseSupport} onChange={(e) => set({ baseSupport: num(e) })} />
          <span className="ed-slider-v">{party.baseSupport.toFixed(2)}</span>
        </div>
      </div>
    </div>
  );
}

function MultipartyForm({
  draft,
  setDraft,
  canPlay,
  onUnlock,
}: {
  draft: CustomScenario;
  setDraft: (cs: CustomScenario) => void;
  canPlay: (scenarioId: string) => boolean;
  onUnlock: (scenarioId: string) => void;
}) {
  const mp = draft.mp;
  if (!mp) return null;
  const isCountry = draft.engine === "country";

  const elections = isCountry ? countryElectionOptions(mp.countryId ?? "") : ukElectionOptions();

  // Entitlement of the base election a player builds on. A custom race inherits
  // the pack the underlying real election needs; free bases stay open to all.
  const lockFor = (electionId: string) => {
    const id = baseScenarioId(draft.engine, mp.countryId, electionId);
    if (!id || canPlay(id)) return null;
    const pack = packForScenario(id);
    return pack ? { scenarioId: id, packName: pack.name } : null;
  };
  const currentLock = lockFor(mp.baseElection);

  const setCountry = (countryId: string) => {
    const opts = countryElectionOptions(countryId);
    const baseElection = opts[0]?.id ?? mp.baseElection;
    const c = COUNTRIES[countryId];
    setDraft({
      ...draft,
      year: c?.elections[baseElection]?.year ?? draft.year,
      mp: { countryId, baseElection, parties: multipartyPartiesFor("country", countryId, baseElection) },
    });
  };

  const setElection = (baseElection: string) => {
    const engine = isCountry ? "country" : "uk";
    const year = isCountry
      ? COUNTRIES[mp.countryId ?? ""]?.elections[baseElection]?.year
      : UK_ELECTIONS[baseElection]?.year;
    setDraft({
      ...draft,
      year: year ?? draft.year,
      mp: { ...mp, baseElection, parties: multipartyPartiesFor(engine, mp.countryId, baseElection) },
    });
  };

  const setParty = (i: number, p: CustomPartyInput) => {
    const parties = mp.parties.map((old, idx) => (idx === i ? p : old));
    setDraft({ ...draft, mp: { ...mp, parties } });
  };

  return (
    <>
      <div className="field" style={{ textAlign: "left" }}>
        <label>Race details</label>
        <div className="ed-grid2">
          <div>
            <span className="muted small">Title</span>
            <input type="text" value={draft.label} maxLength={60} onChange={(e) => setDraft({ ...draft, label: e.target.value })} />
          </div>
          {isCountry && (
            <div>
              <span className="muted small">Country</span>
              <select value={mp.countryId} onChange={(e) => setCountry(e.target.value)} style={{ width: "100%" }}>
                {Object.values(COUNTRIES).map((c) => (
                  <option key={c.id} value={c.id}>{c.flag} {c.label}</option>
                ))}
              </select>
            </div>
          )}
          <div>
            <span className="muted small">Base election</span>
            <select value={mp.baseElection} onChange={(e) => setElection(e.target.value)} style={{ width: "100%" }}>
              {elections.map((o) => {
                const lock = lockFor(o.id);
                return (
                  <option key={o.id} value={o.id}>
                    {o.label}{lock ? ` · Locked (${lock.packName})` : ""}
                  </option>
                );
              })}
            </select>
          </div>
        </div>
        {currentLock && (
          <div className="ed-errors" style={{ marginTop: 8 }}>
            <Lock size={12} style={{ verticalAlign: "-1px" }} /> This election needs the {currentLock.packName} pack. Get it from the Lakeside store, then come back and play.{" "}
            <button className="ghost small" onClick={() => onUnlock(currentLock.scenarioId)}>Unlock</button>
          </div>
        )}
        <div style={{ marginTop: 8 }}>
          <span className="muted small">Tagline</span>
          <input type="text" value={draft.tagline} maxLength={200} onChange={(e) => setDraft({ ...draft, tagline: e.target.value })} />
        </div>
        <p className="muted small" style={{ marginTop: 8 }}>
          You reuse this election's map and voter blocs. Customize the parties, leaders, and their starting strength below.
          {!isCountry && " In play the UK shell keeps the historic party labels."}
        </p>
      </div>

      {mp.parties.map((p, i) => (
        <PartyRow key={p.partyId} party={p} onChange={(np) => setParty(i, np)} />
      ))}
    </>
  );
}

export function EditorScreen({ onClose, onLaunch }: { onClose: () => void; onLaunch?: (t: EditorLaunchTarget) => void }) {
  const newGame = useGameStore((s) => s.newGame);
  const startUk = useUkStore((s) => s.startCustom);
  const startCountry = useCountryStore((s) => s.startCustom);
  const canPlay = useAuthStore((s) => s.canPlay);
  const user = useAuthStore((s) => s.user);
  const openModal = useAuthStore((s) => s.openModal);
  const [draft, setDraft] = useState<CustomScenario>(() => makeDefaultCustomScenario());
  const [saved, setSaved] = useState<CustomScenario[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [toast, setToast] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const refresh = () => { void customScenarioStore.list().then(setSaved); };
  useEffect(() => { refresh(); }, []);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2400); };

  const validated = () => {
    const result = validateCustomScenario({ ...draft, updatedAt: Date.now() });
    if (!result.ok) { setErrors(result.errors); return null; }
    setErrors([]);
    return result.value;
  };

  const save = async (): Promise<CustomScenario | null> => {
    const value = validated();
    if (!value) return null;
    await customScenarioStore.save(value);
    setDraft(value);
    refresh();
    flash("Custom campaign saved");
    return value;
  };

  // A custom race inherits the entitlement of the real base election it reuses.
  // US customs (generic map) and free bases return null; paid bases return the
  // pack the player still needs. Checked at launch so an imported JSON built on
  // a paid base cannot bypass the gate.
  const baseLock = (cs: CustomScenario): { scenarioId: string; packName: string } | null => {
    const id = baseScenarioIdForCustom(cs);
    if (!id || canPlay(id)) return null;
    const pack = packForScenario(id);
    return pack ? { scenarioId: id, packName: pack.name } : null;
  };

  const unlock = (scenarioId: string) => openModal(user ? "activate" : "login", scenarioId);

  const play = (cs: CustomScenario) => {
    // Free path stays open (US customs, free bases). A paid base needs its pack.
    const lock = baseLock(cs);
    if (lock) {
      setErrors([`This campaign is built on a locked election. Needs the ${lock.packName} pack. Get it from the Lakeside store, then redeem your code here.`]);
      unlock(lock.scenarioId);
      return;
    }
    if (cs.engine === "uk") {
      startUk(buildUkCustomGame(cs));
      onLaunch?.({ kind: "uk" });
      onClose();
      return;
    }
    if (cs.engine === "country") {
      const { country, game } = buildCountryCustomGame(cs);
      startCountry(country, game);
      onLaunch?.({ kind: "country", countryId: cs.mp?.countryId ?? country.id });
      onClose();
      return;
    }
    registerCustomScenario(cs);
    newGame({ scenario: cs.id, customScenario: JSON.stringify(cs), playerCandidate: "dem", difficulty: "normal", eventMode: cs.eventMode ?? "historical" });
  };

  const saveAndPlay = async () => {
    const value = await save();
    if (value) play(value);
  };

  const exportOne = (cs: CustomScenario) => {
    const blob = new Blob([serializeCustomScenario(cs)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `custom-campaign-${cs.id.slice(0, 14)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    flash("Scenario exported");
  };

  const doImport = (file: File) => {
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        const cs = parseCustomScenario(String(reader.result));
        // Re-key on import so an imported file never overwrites an existing save.
        const copy: CustomScenario = { ...cs, id: newCustomId(), updatedAt: Date.now() };
        await customScenarioStore.save(copy);
        refresh();
        flash("Scenario imported");
      } catch (err) {
        flash("Import failed");
        setErrors([err instanceof Error ? err.message : "Invalid file"]);
      }
    };
    reader.readAsText(file);
  };

  const remove = async (id: string) => {
    await customScenarioStore.remove(id);
    refresh();
  };

  const startNew = () => { setDraft(makeDefaultCustomScenario()); setErrors([]); };
  const edit = (cs: CustomScenario) => { setDraft(cs); setErrors([]); };

  // Switching engine resets the draft to a fresh default for that shell.
  const setEngine = (engine: CustomEngine) => {
    setErrors([]);
    if (engine === "us") setDraft(makeDefaultCustomScenario());
    else if (engine === "uk") setDraft(makeDefaultMultiparty("uk"));
    else setDraft(makeDefaultMultiparty("country", Object.values(COUNTRIES)[0]?.id));
  };

  const ENGINE_OPTS: { id: CustomEngine; name: string; blurb: string }[] = [
    { id: "us", name: "United States", blurb: "Two tickets, the Electoral College." },
    { id: "uk", name: "United Kingdom", blurb: "Multiparty, first past the post." },
    { id: "country", name: "Other country", blurb: "Canada, Germany, France, Australia." },
  ];

  const subtitle = (cs: CustomScenario) =>
    cs.engine === "us"
      ? `${cs.dem.shortName} v. ${cs.rep.shortName}`
      : `${cs.engine === "country" ? (COUNTRIES[cs.mp?.countryId ?? ""]?.label ?? "Country") : "UK"} · ${cs.mp?.parties.length ?? 0} parties`;

  return (
    <div className="overlay" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 760 }} onClick={(e) => e.stopPropagation()}>
        <div className="head">
          <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <div>
              <div className="tag">CAMPAIGN EDITOR</div>
              <h2>Build your own race</h2>
            </div>
            <button className="ghost" onClick={onClose}><X size={16} /> Close</button>
          </div>
        </div>
        <div className="body">
        <p className="sub" style={{ margin: "0 0 14px" }}>Pick an election, build the parties and leaders, save them, and play. Custom campaigns are casual only.</p>

        {saved.length > 0 && (
          <div className="field" style={{ textAlign: "left" }}>
            <label>Your saved campaigns</label>
            <div className="scenario-grid" style={{ gridTemplateColumns: "1fr" }}>
              {saved.map((cs) => {
                const lock = baseLock(cs);
                return (
                <div key={cs.id} className="scenario-card" style={{ textAlign: "left", alignItems: "stretch", cursor: "default" }}>
                  <span className="scenario-year" style={{ fontSize: 14 }}>{cs.year} · {cs.label}</span>
                  <span className="scenario-match">{subtitle(cs)}</span>
                  {lock && (
                    <span className="muted small" style={{ marginTop: 4 }}>
                      <Lock size={11} style={{ verticalAlign: "-1px" }} /> Locked. Needs the {lock.packName} pack.
                    </span>
                  )}
                  <div className="row" style={{ gap: 6, marginTop: 8, flexWrap: "wrap" }}>
                    <button className="primary small" onClick={() => play(cs)}>
                      {lock ? <><Lock size={13} /> Unlock</> : <><Play size={13} /> Play</>}
                    </button>
                    <button className="ghost small" onClick={() => edit(cs)}>Edit</button>
                    <button className="ghost small" onClick={() => exportOne(cs)}><Download size={13} /> Export</button>
                    <button className="ghost small" onClick={() => remove(cs.id)}><Trash2 size={13} /> Delete</button>
                  </div>
                </div>
                );
              })}
            </div>
          </div>
        )}

        <div className="row" style={{ gap: 6, margin: "6px 0 12px", flexWrap: "wrap" }}>
          <button className="ghost small" onClick={startNew}><Dices size={13} /> New campaign</button>
          <button className="ghost small" onClick={() => fileRef.current?.click()}><Upload size={13} /> Import file</button>
          <input ref={fileRef} type="file" accept="application/json" style={{ display: "none" }}
            onChange={(e) => { const f = e.target.files?.[0]; if (f) doImport(f); e.target.value = ""; }} />
        </div>

        {/* ── Engine picker ───────────────────────────────────────────── */}
        <div className="field" style={{ textAlign: "left" }}>
          <label>Which election</label>
          <div className="su-optgrid">
            {ENGINE_OPTS.map((o) => (
              <button key={o.id} type="button" className={`su-opt${draft.engine === o.id ? " sel" : ""}`} onClick={() => setEngine(o.id)}>
                <span className="su-opt-name">{o.name}</span>
                <span className="su-opt-blurb">{o.blurb}</span>
              </button>
            ))}
          </div>
        </div>

        {draft.engine === "us" ? (
        <>
        {/* ── Scenario params ─────────────────────────────────────────── */}
        <div className="field" style={{ textAlign: "left" }}>
          <label>Race details</label>
          <div className="ed-grid2">
            <div>
              <span className="muted small">Title</span>
              <input type="text" value={draft.label} maxLength={60} onChange={(e) => setDraft({ ...draft, label: e.target.value })} />
            </div>
            <div>
              <span className="muted small">Year</span>
              <input type="number" value={draft.year} min={1788} max={2100} onChange={(e) => setDraft({ ...draft, year: Number(e.target.value) })} />
            </div>
          </div>
          <div style={{ marginTop: 8 }}>
            <span className="muted small">Tagline</span>
            <input type="text" value={draft.tagline} maxLength={200} onChange={(e) => setDraft({ ...draft, tagline: e.target.value })} />
          </div>
          <div style={{ marginTop: 8 }}>
            <span className="muted small">Events</span>
            <div className="su-optgrid">
              <button type="button" className={`su-opt${(draft.eventMode ?? "historical") === "historical" ? " sel" : ""}`} onClick={() => setDraft({ ...draft, eventMode: "historical" })}>
                <span className="su-opt-name">Historical</span>
                <span className="su-opt-blurb">The scripted beats for the year.</span>
              </button>
              <button type="button" className={`su-opt${draft.eventMode === "plausible" ? " sel" : ""}`} onClick={() => setDraft({ ...draft, eventMode: "plausible" })}>
                <span className="su-opt-name">Random / plausible</span>
                <span className="su-opt-blurb">A shuffled deck, different every run.</span>
              </button>
            </div>
          </div>
        </div>

        <TicketForm side="dem" ticket={draft.dem} onChange={(t) => setDraft({ ...draft, dem: t })} />
        <TicketForm side="rep" ticket={draft.rep} onChange={(t) => setDraft({ ...draft, rep: t })} />
        </>
        ) : (
          <MultipartyForm draft={draft} setDraft={setDraft} canPlay={canPlay} onUnlock={unlock} />
        )}

        {errors.length > 0 && (
          <div className="field" style={{ textAlign: "left" }}>
            <div className="ed-errors">
              <strong>Fix these before saving:</strong>
              <ul>{errors.map((e, i) => <li key={i}>{e}</li>)}</ul>
            </div>
          </div>
        )}

        <div className="su-nav" style={{ marginTop: 12 }}>
          <button className="ghost" onClick={onClose}>Close</button>
          <div className="row" style={{ gap: 8 }}>
            <button className="ghost" onClick={() => void save()}><Save size={15} /> Save</button>
            <button className="primary" onClick={() => void saveAndPlay()}><Play size={15} /> Save and play</button>
          </div>
        </div>

        {toast && <div className="toast">{toast}</div>}
        </div>
      </div>
    </div>
  );
}
