import { useMemo, useState } from "react";
import { useUkStore } from "@store/ukStore";
import { useAuthStore } from "@store/authStore";
import { api } from "@lib/api";
import { computeScoreFromFacts, multipartyScoreFacts } from "@engine/scoring";
import { majorityForUk, playablePartiesIn } from "@engine/ukGame";
import type { Government } from "@engine/types";
import { UK_ELECTIONS } from "@content/uk/elections";
import { UK_NEXT_SCENARIO } from "@content/nextScenario";
import { partyName, partyShort, sortBySeats, partyColor } from "./parties";
import { ElectionNight, hasSeenReveal, revealSupported } from "../electionNight/ElectionNight";
import { ElectionNightShell } from "../electionNight/ElectionNightShell";
import { MultipartySeatPanel } from "../electionNight/MultipartySeatPanel";
import { ukReveal } from "../electionNight/adapters";
import { DailyResultPanel } from "../DailyResultPanel";
import { WhyReport } from "../WhyReport";
import { TimelineView } from "../TimelineView";
import { dailyAssignment, utcDateString } from "@lib/daily";
import { REGION_PATHS, UK_VIEWBOX } from "@content/uk/regionPaths";
import { Lock } from "lucide-react";

function govText(g: Government): string {
  switch (g.kind) {
    case "majority": return `${partyName(g.party)} wins a majority with ${g.seats} seats.`;
    case "minority": return `${partyName(g.party)} forms a minority government on ${g.seats} seats. No overall control.`;
    case "coalition": return `Hung parliament. A ${g.parties.map(partyName).join("-")} coalition reaches ${g.seats} seats.`;
    case "confidence_supply": return `Hung parliament. ${partyName(g.lead)} governs with ${partyName(g.partner)} confidence-and-supply (${g.seats}).`;
    case "hung": return `Hung parliament, no workable majority. ${partyName(g.largest)} is the largest party.`;
  }
}

function UkMiniMap({ seats }: { seats: Record<string, number> }) {
  // Color a handful of regions by the national largest party as a preview cue.
  const largest = sortBySeats(seats)[0];
  const color = largest ? partyColor(largest) : "var(--navy-500)";
  const ids = Object.keys(REGION_PATHS).slice(0, 12);
  return (
    <svg viewBox={UK_VIEWBOX} preserveAspectRatio="xMidYMid meet">
      {ids.map((id) => (
        <path key={id} d={REGION_PATHS[id].d} fill={color} stroke="#0b1120" strokeWidth={0.8} />
      ))}
    </svg>
  );
}

export function UkResults() {
  const game = useUkStore((s) => s.game)!;
  const reset = useUkStore((s) => s.reset);
  const newGame = useUkStore((s) => s.newGame);
  const replay = useUkStore((s) => s.replay);
  const [timelineOpen, setTimelineOpen] = useState(false);
  const r = game.result!;
  const order = sortBySeats(r.seats);
  const maj = majorityForUk(game);
  const playerSeats = r.seats[game.playerParty] ?? 0;
  const won = r.government.kind === "majority" && r.government.party === game.playerParty;
  const inGov = ("party" in r.government && r.government.party === game.playerParty) ||
    ("lead" in r.government && r.government.lead === game.playerParty) ||
    (r.government.kind === "coalition" && r.government.parties.includes(game.playerParty));

  const user = useAuthStore((s) => s.user);
  const serverDown = useAuthStore((s) => s.serverDown);
  const openModal = useAuthStore((s) => s.openModal);
  const canPlay = useAuthStore((s) => s.canPlay);
  const scenarioId = `uk-${game.electionId}`;
  const difficulty = game.difficulty ?? "normal";
  const facts = useMemo(
    () => multipartyScoreFacts(r, game.playerParty, maj.threshold, maj.total, difficulty),
    [r, game.playerParty, maj, difficulty],
  );
  const score = computeScoreFromFacts(facts);
  const [postState, setPostState] = useState<"idle" | "busy" | "done" | "error">("idle");
  const [postNote, setPostNote] = useState("");
  const post = async () => {
    setPostState("busy");
    try {
      const out = await api.postScore({
        scenarioId, difficulty, score, facts,
        seats: r.seats, voteShare: r.voteShare, playerSide: game.playerParty,
        evMargin: Math.round(facts.unitMargin), popularVoteMargin: facts.popularMargin, turnsPlayed: game.turn,
      });
      setPostState("done");
      setPostNote(out.posted ? `Posted. Rank #${out.rank}.` : `Kept your best (${out.personalBest}). Rank #${out.rank}.`);
    } catch (e) {
      setPostState("error");
      setPostNote(e instanceof Error ? e.message : "Server unreachable");
    }
  };

  const reveal = useMemo(() => ukReveal(game), [game]);
  const [nightDone, setNightDone] = useState(() => !revealSupported() || hasSeenReveal(reveal.storageKey ?? ""));
  if (!nightDone) {
    return <ElectionNight {...reveal} onDone={() => setNightDone(true)} />;
  }

  const next = UK_NEXT_SCENARIO[game.electionId];
  const nextData = next ? UK_ELECTIONS[next.id] : null;
  const unlocked = next ? canPlay(`uk-${next.id}`) : false;
  const playableNext = next ? playablePartiesIn(next.id) : [];
  const nextParty = playableNext.includes(game.playerParty) ? game.playerParty : playableNext[0];

  const largest = order[0];
  const seatMargin = playerSeats - maj.threshold;
  const popShare = (r.voteShare[game.playerParty] ?? 0) * 100;
  // Swingometer: player seats relative to majority (positive = above the line).
  const leftParty = largest ?? game.playerParty;
  const rightParty = order.find((p) => p !== leftParty) ?? game.playerParty;

  const segments = order.map((p) => ({
    id: p,
    seats: r.seats[p],
    short: partyShort(p),
    color: partyColor(p),
  }));

  const eyebrow =
    r.government.kind === "majority"
      ? "PROJECTED WINNER"
      : r.government.kind === "hung"
        ? "HUNG PARLIAMENT"
        : "NO OVERALL MAJORITY";

  const headline =
    r.government.kind === "majority"
      ? partyName(r.government.party)
      : r.government.kind === "hung"
        ? partyName(r.government.largest)
        : inGov
          ? partyName(game.playerParty)
          : largest
            ? partyName(largest)
            : "Hung parliament";

  const replayDaily = () => {
    const today = dailyAssignment(utcDateString());
    if (today.scenarioId !== scenarioId) return;
    newGame(game.electionId, today.role, today.seed, difficulty);
  };

  return (
    <div className="center" style={{ padding: "24px 16px" }}>
      <ElectionNightShell
        eyebrow={eyebrow}
        headline={headline}
        headlineColor={
          r.government.kind === "majority"
            ? partyColor(r.government.party)
            : partyColor(game.playerParty)
        }
        subhead={
          won
            ? "You won a majority."
            : inGov
              ? "You're in government."
              : "You're in opposition."
        }
        subheadTone={won ? "win" : inGov ? "neutral" : "loss"}
        counterValue={playerSeats}
        counterLabel="Your seats"
        counterColor={partyColor(game.playerParty)}
        secondaryCounter={{
          value: popShare,
          label: "Vote share",
          decimals: 1,
          suffix: "%",
        }}
        swing={{
          value: seatMargin,
          maxAbs: Math.max(40, Math.abs(seatMargin) * 1.4),
          leftLabel: partyShort(leftParty),
          rightLabel: partyShort(rightParty),
          leftColor: partyColor(leftParty),
          rightColor: partyColor(rightParty),
          unitLabel: "vs maj.",
          caption: "Seats vs majority threshold",
        }}
        nextScenario={
          next && nextData
            ? {
                title: nextData.label,
                blurb: next.blurb,
                unlocked,
                ctaLabel: `Play ${nextData.year} →`,
                lockLabel: `🔒 Unlock ${nextData.year}`,
                onPlay: () => newGame(next.id, nextParty, String(Date.now()), difficulty),
                onUnlock: () => openModal("activate", `uk-${next.id}`),
                mapPreview: <UkMiniMap seats={r.seats} />,
              }
            : null
        }
        footer={
          <>
            <DailyResultPanel
              gameSeed={game.seed}
              scenarioId={scenarioId}
              engine="uk"
              won={won}
              unitLine={`${playerSeats} seats`}
              score={score}
              facts={facts}
              evMargin={Math.round(facts.unitMargin)}
              popularVoteMargin={facts.popularMargin}
              onReplay={replayDaily}
            />
            <div className="card">
              <div className="row" style={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10 }}>
                <div>
                  <div className="tag muted small" style={{ color: "var(--gold)", letterSpacing: "2px" }}>CAMPAIGN SCORE</div>
                  <div style={{ fontSize: 34, fontWeight: 800 }}>
                    {score}<span className="muted" style={{ fontSize: 15, fontWeight: 600 }}> / 1000</span>
                  </div>
                  <div className="muted small">
                    Seat margin {facts.unitMargin >= 0 ? "+" : ""}{Math.round(facts.unitMargin)} vs {maj.threshold}
                  </div>
                </div>
                <div style={{ textAlign: "right" }}>
                  {game.custom ? (
                    <span className="muted small">Custom campaigns are casual only and don't post to the leaderboard</span>
                  ) : user ? (
                    <button className="primary" disabled={postState === "busy" || postState === "done" || serverDown} onClick={post}>
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

            <div className="card">
              <h3>Biggest swings you caused</h3>
              {r.postMortem.length === 0 && <p className="muted small">No persuasive actions recorded. Other campaigns and events shaped the result.</p>}
              {r.postMortem.map((c, i) => (
                <div className="kv" key={i}>
                  <span className="k">{c.cause}</span>
                  <span className={c.marginDelta >= 0 ? "chip up" : "chip down"}>
                    {c.marginDelta > 0 ? "+" : ""}{(c.marginDelta * 100).toFixed(1)}
                  </span>
                </div>
              ))}
            </div>

            <button className="primary" style={{ width: "100%", padding: 12 }} onClick={reset}>
              New campaign
            </button>
          </>
        }
      >
        <p className="muted" style={{ textAlign: "center", margin: 0 }}>{govText(r.government)}</p>

        <MultipartySeatPanel
          segments={segments}
          total={maj.total}
          threshold={maj.threshold}
          government={r.government}
          nameOf={partyName}
          unitPlural="seats"
        />

        <div className="card">
          <h3>Final standings</h3>
          {order.map((p) => (
            <div className="bloc" key={p}>
              <span className="name" style={{ color: p === game.playerParty ? partyColor(p) : undefined }}>
                <span style={{ display: "inline-block", width: 10, height: 10, background: partyColor(p), borderRadius: 2, marginRight: 6 }} />
                {partyShort(p)}
              </span>
              <span className="meta">{r.seats[p]} seats · {((r.voteShare[p] ?? 0) * 100).toFixed(1)}%</span>
              <div className="suppbar"><div style={{ width: `${(r.seats[p] / maj.total) * 100 * 1.6}%`, background: partyColor(p) }} /></div>
            </div>
          ))}
        </div>
      </ElectionNightShell>
      {timelineOpen && replay && <TimelineView log={replay} onClose={() => setTimelineOpen(false)} />}
    </div>
  );
}
