import { useMemo, useState } from "react";
import { useCountryStore } from "@store/countryStore";
import { useAuthStore } from "@store/authStore";
import { api } from "@lib/api";
import { computeScoreFromFacts, multipartyScoreFacts } from "@engine/scoring";
import { majorityFor, playablePartiesIn } from "@engine/countryGame";
import type { Government } from "@engine/types";
import { countryNextScenario } from "@content/nextScenario";
import { partyColor, partyName, partyShort, sortBySeats } from "./helpers";
import { Trophy, Lock } from "lucide-react";
import { ElectionNight, hasSeenReveal, revealSupported } from "../electionNight/ElectionNight";
import { ElectionNightShell } from "../electionNight/ElectionNightShell";
import { MultipartySeatPanel } from "../electionNight/MultipartySeatPanel";
import { countryReveal } from "../electionNight/adapters";
import { DailyResultPanel } from "../DailyResultPanel";
import { WhyReport } from "../WhyReport";
import { TimelineView } from "../TimelineView";
import { dailyAssignment, utcDateString } from "@lib/daily";

function defaultGovText(g: Government, name: (p: string) => string): string {
  switch (g.kind) {
    case "majority": return `${name(g.party)} wins a majority with ${g.seats} seats.`;
    case "minority": return `${name(g.party)} forms a minority government on ${g.seats} seats.`;
    case "coalition": return `A ${g.parties.map(name).join("-")} coalition reaches ${g.seats} seats.`;
    case "confidence_supply": return `${name(g.lead)} governs with ${name(g.partner)} confidence-and-supply (${g.seats}).`;
    case "hung": return `No workable majority. ${name(g.largest)} is the largest party.`;
  }
}

export function CountryResults({ onExit }: { onExit: () => void }) {
  const country = useCountryStore((s) => s.country)!;
  const game = useCountryStore((s) => s.game)!;
  const reset = useCountryStore((s) => s.reset);
  const newGame = useCountryStore((s) => s.newGame);
  const replay = useCountryStore((s) => s.replay);
  const [timelineOpen, setTimelineOpen] = useState(false);
  const user = useAuthStore((s) => s.user);
  const serverDown = useAuthStore((s) => s.serverDown);
  const openModal = useAuthStore((s) => s.openModal);
  const canPlay = useAuthStore((s) => s.canPlay);
  const r = game.result!;

  const scenarioId = `${country.id.toLowerCase()}-${game.electionId}`;
  const majority = majorityFor(game, country);
  const difficulty = game.difficulty ?? "normal";

  const facts = useMemo(
    () => multipartyScoreFacts(r, game.playerParty, majority.threshold, majority.total, difficulty),
    [r, game.playerParty, majority, difficulty],
  );
  const score = computeScoreFromFacts(facts);

  const [postState, setPostState] = useState<"idle" | "busy" | "done" | "error">("idle");
  const [postNote, setPostNote] = useState("");

  const postScore = async () => {
    setPostState("busy");
    try {
      const out = await api.postScore({
        scenarioId,
        difficulty,
        score,
        facts,
        seats: r.seats,
        voteShare: r.voteShare,
        playerSide: game.playerParty,
        evMargin: Math.round(facts.unitMargin),
        popularVoteMargin: facts.popularMargin,
        turnsPlayed: game.turn,
      });
      setPostState("done");
      setPostNote(out.posted ? `Posted. Rank #${out.rank}.` : `Kept your personal best (${out.personalBest}). Rank #${out.rank}.`);
    } catch (e) {
      setPostState("error");
      setPostNote(e instanceof Error ? e.message : "Could not reach the server");
    }
  };

  const order = sortBySeats(country, r.seats);
  const playerSeats = r.seats[game.playerParty] ?? 0;
  const won = r.government.kind === "majority" && r.government.party === game.playerParty;
  const inGov = ("party" in r.government && r.government.party === game.playerParty) ||
    ("lead" in r.government && r.government.lead === game.playerParty) ||
    (r.government.kind === "coalition" && r.government.parties.includes(game.playerParty));

  const name = (p: string) => partyName(country, p);
  const govText = country.governmentText?.(r.government, name) ?? defaultGovText(r.government, name);

  const reveal = useMemo(() => countryReveal(country, game), [country, game]);
  const [nightDone, setNightDone] = useState(() => !revealSupported() || hasSeenReveal(reveal.storageKey ?? ""));
  if (!nightDone) {
    return <ElectionNight {...reveal} onDone={() => setNightDone(true)} />;
  }

  const next = countryNextScenario(country.id, game.electionId);
  const nextData = next ? country.elections[next.id] : null;
  const packId = next ? `${country.id.toLowerCase()}-${next.id}` : "";
  const unlocked = next ? canPlay(packId) : false;
  const playableNext = next ? playablePartiesIn(country, next.id) : [];
  const nextParty = playableNext.includes(game.playerParty) ? game.playerParty : playableNext[0];

  const largest = order[0];
  const seatMargin = playerSeats - majority.threshold;
  const popShare = (r.voteShare[game.playerParty] ?? 0) * 100;
  const leftParty = largest ?? game.playerParty;
  const rightParty = order.find((p) => p !== leftParty) ?? game.playerParty;

  const segments = order.map((p) => ({
    id: p,
    seats: r.seats[p],
    short: partyShort(country, p),
    color: partyColor(country, p),
  }));

  const eyebrow =
    r.government.kind === "majority"
      ? "PROJECTED WINNER"
      : r.government.kind === "hung"
        ? "HUNG PARLIAMENT"
        : "NO OVERALL MAJORITY";

  const headline =
    r.government.kind === "majority"
      ? name(r.government.party)
      : r.government.kind === "hung"
        ? name(r.government.largest)
        : inGov
          ? name(game.playerParty)
          : largest
            ? name(largest)
            : "No majority";

  const replayDaily = () => {
    const today = dailyAssignment(utcDateString());
    if (today.scenarioId !== scenarioId) return;
    newGame(country.id, game.electionId, today.role, today.seed, difficulty);
  };

  const mapPreview = country.map ? (
    <svg viewBox={country.map.viewBox} preserveAspectRatio="xMidYMid meet">
      {Object.entries(country.map.shapes).slice(0, 14).map(([id, shape]) => (
        <path
          key={id}
          d={shape.d}
          fill={partyColor(country, game.playerParty)}
          stroke="#0b1120"
          strokeWidth={0.8}
        />
      ))}
    </svg>
  ) : undefined;

  return (
    <div className="center" style={{ padding: "24px 16px" }}>
      <ElectionNightShell
        eyebrow={eyebrow}
        headline={headline}
        headlineColor={
          r.government.kind === "majority"
            ? partyColor(country, r.government.party)
            : partyColor(country, game.playerParty)
        }
        subhead={
          won
            ? `You won ${country.label}.`
            : inGov
              ? "You're in government."
              : "You came up short."
        }
        subheadTone={won ? "win" : inGov ? "neutral" : "loss"}
        counterValue={playerSeats}
        counterLabel={`Your ${country.unitNamePlural}`}
        counterColor={partyColor(country, game.playerParty)}
        secondaryCounter={{
          value: popShare,
          label: "Vote share",
          decimals: 1,
          suffix: "%",
        }}
        swing={{
          value: seatMargin,
          maxAbs: Math.max(40, Math.abs(seatMargin) * 1.4),
          leftLabel: partyShort(country, leftParty),
          rightLabel: partyShort(country, rightParty),
          leftColor: partyColor(country, leftParty),
          rightColor: partyColor(country, rightParty),
          unitLabel: "vs maj.",
          caption: `${country.unitNamePlural} vs majority`,
        }}
        nextScenario={
          next && nextData
            ? {
                title: nextData.label,
                blurb: next.blurb,
                unlocked,
                ctaLabel: `Play ${nextData.year} →`,
                lockLabel: `🔒 Unlock ${nextData.year}`,
                onPlay: () => newGame(country.id, next.id, nextParty, String(Date.now()), difficulty),
                onUnlock: () => openModal(user ? "activate" : "login", packId),
                mapPreview,
              }
            : null
        }
        footer={
          <>
            <DailyResultPanel
              gameSeed={game.seed}
              scenarioId={scenarioId}
              engine="country"
              won={won}
              unitLine={`${playerSeats} ${country.unitNamePlural}`}
              score={score}
              facts={facts}
              evMargin={Math.round(facts.unitMargin)}
              popularVoteMargin={facts.popularMargin}
              onReplay={replayDaily}
            />
            <div className="card" style={{ background: "var(--navy-600)" }}>
              <div className="row" style={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10 }}>
                <div>
                  <div className="tag muted small" style={{ color: "var(--gold)" }}>
                    <Trophy size={12} style={{ verticalAlign: "-2px" }} /> CAMPAIGN SCORE
                  </div>
                  <div style={{ fontSize: 34, fontWeight: 800 }}>
                    {score}<span className="muted" style={{ fontSize: 15, fontWeight: 600 }}> / 1000</span>
                  </div>
                  <div className="muted small">
                    {country.unitName} margin {facts.unitMargin >= 0 ? "+" : ""}{Math.round(facts.unitMargin)} · popular{" "}
                    {facts.popularMargin >= 0 ? "+" : ""}{facts.popularMargin.toFixed(1)} pts
                  </div>
                </div>
                <div style={{ textAlign: "right" }}>
                  {game.custom ? (
                    <span className="muted small">Custom campaigns are casual only and don't post to the leaderboard</span>
                  ) : user ? (
                    <button className="primary" disabled={postState === "busy" || postState === "done" || serverDown} onClick={postScore}>
                      {postState === "done" ? "Posted ✓" : postState === "busy" ? "Posting…" : serverDown ? "Offline" : "Post to Leaderboard"}
                    </button>
                  ) : serverDown ? (
                    <span className="muted small">Play offline</span>
                  ) : (
                    <button className="primary" onClick={() => openModal("login")}>
                      <Lock size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />Log in to post
                    </button>
                  )}
                  {postNote && <div className="muted small" style={{ marginTop: 6 }}>{postNote}</div>}
                </div>
              </div>
            </div>

            <WhyReport log={replay} won={inGov} />

            {replay && (
              <button className="ghost" style={{ width: "100%", padding: 10 }} onClick={() => setTimelineOpen(true)}>
                View campaign timeline
              </button>
            )}

            {r.postMortem.length > 0 && (
              <div className="card">
                <h3>Biggest swings you caused</h3>
                {r.postMortem.map((c, i) => (
                  <div className="kv" key={i}>
                    <span className="k">{c.cause}</span>
                    <span className={c.marginDelta >= 0 ? "chip up" : "chip down"}>
                      {c.marginDelta > 0 ? "+" : ""}{(c.marginDelta * 100).toFixed(1)}
                    </span>
                  </div>
                ))}
              </div>
            )}

            <div className="row" style={{ gap: 8 }}>
              <button className="primary" style={{ flex: 1, padding: 12 }} onClick={reset}>New campaign</button>
              <button className="ghost" onClick={() => { reset(); onExit(); }}>All scenarios</button>
            </div>
          </>
        }
      >
        <p className="muted" style={{ textAlign: "center", margin: 0 }}>{govText}</p>

        <MultipartySeatPanel
          segments={segments}
          total={majority.total}
          threshold={majority.threshold}
          government={r.government}
          nameOf={name}
          unitPlural={country.unitNamePlural}
          mathLabel={country.id === "FR" ? "RUNOFF RESULT" : undefined}
        />

        <div className="card">
          <h3>Final standings</h3>
          {order.map((p) => (
            <div className="bloc" key={p}>
              <span className="name" style={{ color: p === game.playerParty ? partyColor(country, p) : undefined }}>
                <span style={{ display: "inline-block", width: 10, height: 10, background: partyColor(country, p), borderRadius: 2, marginRight: 6 }} />
                {partyShort(country, p)}
              </span>
              <span className="meta">{r.seats[p]} {country.unitNamePlural} · {((r.voteShare[p] ?? 0) * 100).toFixed(1)}%</span>
              <div className="suppbar"><div style={{ width: `${(r.seats[p] / majority.total) * 100 * 1.6}%`, background: partyColor(country, p) }} /></div>
            </div>
          ))}
        </div>
      </ElectionNightShell>
      {timelineOpen && replay && <TimelineView log={replay} onClose={() => setTimelineOpen(false)} />}
    </div>
  );
}
