// Daily Challenge compact result — score, percentile vs today's board,
// one-click replay. Shown only when the finished game is today's daily.
import { useEffect, useMemo, useState } from "react";
import {
  dailyAssignment,
  utcDateString,
  roleShortName,
  markDailyPlayed,
  recordDailyBest,
  dailyBest,
  dailyStreak,
} from "@lib/daily";
import { SCENARIOS_BY_ID } from "@content/scenarioRegistry";
import { estimatePercentile } from "@lib/dailyStats";
export { estimatePercentile } from "@lib/dailyStats";
import { buildShareText, copyShare } from "@lib/shareCard";
import { api, type DailyBoardEntry } from "@lib/api";
import { useAuthStore } from "@store/authStore";
import { hashSeed } from "@engine/rng";
import { hashStr } from "@engine/setup";
import type { ScoreFacts } from "@engine/scoring";
import { CalendarDays, Share2, RotateCcw } from "lucide-react";
import "./shell.css";

export function DailyRevealPanel({
  gameSeed,
  scenarioId,
  engine,
  won,
  unitLine,
  score,
  facts,
  evMargin,
  popularVoteMargin,
  onReplay,
}: {
  gameSeed: number;
  scenarioId: string;
  engine: "us" | "uk" | "country";
  won: boolean;
  unitLine: string;
  score: number;
  facts: ScoreFacts;
  evMargin: number;
  popularVoteMargin: number;
  /** One-click replay of today's daily with the same seed/role. */
  onReplay?: () => void;
}) {
  const user = useAuthStore((s) => s.user);
  const serverDown = useAuthStore((s) => s.serverDown);
  const openModal = useAuthStore((s) => s.openModal);
  const today = useMemo(() => dailyAssignment(utcDateString()), []);
  const isDaily =
    today.scenarioId === scenarioId &&
    gameSeed === (engine === "us" ? hashStr(today.seed) : hashSeed(today.seed));

  const [copied, setCopied] = useState(false);
  const [postState, setPostState] = useState<"idle" | "busy" | "done" | "error">("idle");
  const [note, setNote] = useState("");
  const [best, setBest] = useState<number | null>(null);
  const [streak, setStreak] = useState(0);
  const [board, setBoard] = useState<DailyBoardEntry[] | null>(null);
  const [myRank, setMyRank] = useState<number | null>(null);

  useEffect(() => {
    if (!isDaily) return;
    markDailyPlayed(today.date);
    setBest(recordDailyBest(today.date, score));
    setStreak(dailyStreak());
  }, [isDaily, today.date, score]);

  // Pull today's board for a local percentile estimate (best-effort).
  useEffect(() => {
    if (!isDaily || serverDown) return;
    let cancelled = false;
    void api.dailyBoard(today.date).then((b) => {
      if (cancelled) return;
      setBoard(b.entries);
      if (b.me) setMyRank(b.me.rank);
    }).catch(() => { /* offline / guest — percentile stays local */ });
    return () => { cancelled = true; };
  }, [isDaily, today.date, serverDown]);

  if (!isDaily) return null;
  const meta = SCENARIOS_BY_ID[scenarioId];
  const pb = best ?? dailyBest(today.date);

  // Percentile: if we have a board, rank among entries + this score;
  // otherwise fall back to a soft estimate from personal best alone.
  const percentile = estimatePercentile(score, board, myRank);

  const share = async () => {
    const ok = await copyShare(buildShareText({
      date: today.date,
      label: meta?.label ?? scenarioId,
      flag: meta?.flag ?? "🗳️",
      role: roleShortName(today.role),
      won,
      unitLine,
      score,
    }));
    setCopied(ok);
    if (ok) setTimeout(() => setCopied(false), 2500);
  };

  const post = async () => {
    setPostState("busy");
    try {
      const out = await api.postDaily({ score, facts, evMargin, popularVoteMargin });
      setPostState("done");
      setMyRank(out.rank);
      setNote(out.posted ? `On the board: rank #${out.rank} today.` : `Kept your best (${out.personalBest}). Rank #${out.rank}.`);
    } catch (e) {
      setPostState("error");
      setNote(e instanceof Error ? e.message : "Could not reach the server");
    }
  };

  return (
    <div className="ens-daily" data-testid="daily-reveal-panel">
      <div className="ens-daily-top">
        <div>
          <div className="ens-daily-tag">
            <CalendarDays size={12} style={{ verticalAlign: "-2px" }} /> DAILY · {today.date}
          </div>
          <div className="ens-daily-score">
            {score}
            <span className="ens-daily-score-max"> / 1000</span>
          </div>
          <div className="muted small">
            {unitLine}
            {pb != null ? ` · best ${pb}` : ""}
            {streak > 0 ? ` · ${streak}-day streak` : ""}
          </div>
        </div>
        <div className="ens-daily-pct" data-testid="daily-percentile">
          {percentile != null ? (
            <>
              <div className="ens-daily-pct-num">Top {percentile}%</div>
              <div className="muted small">{myRank != null ? `Rank #${myRank}` : "vs today's board"}</div>
            </>
          ) : (
            <div className="muted small">Post to see your rank</div>
          )}
        </div>
      </div>

      <div className="ens-daily-actions">
        {onReplay && (
          <button className="ghost" onClick={onReplay} data-testid="daily-replay">
            <RotateCcw size={14} style={{ verticalAlign: "-2px", marginRight: 5 }} />
            Replay daily
          </button>
        )}
        <button className="ghost" onClick={share}>
          <Share2 size={14} style={{ verticalAlign: "-2px", marginRight: 5 }} />
          {copied ? "Copied ✓" : "Share"}
        </button>
        {user ? (
          <button
            className="primary"
            disabled={postState === "busy" || postState === "done" || serverDown}
            onClick={post}
            data-testid="daily-post"
          >
            {postState === "done" ? "Posted ✓" : postState === "busy" ? "Posting…" : serverDown ? "Offline" : "Post to daily board"}
          </button>
        ) : serverDown ? (
          <span className="muted small" data-testid="daily-offline">Play offline</span>
        ) : (
          <button className="primary" onClick={() => openModal("login")} data-testid="daily-login">
            Log in to post
          </button>
        )}
      </div>
      {note && <div className="muted small" style={{ marginTop: 6 }}>{note}</div>}
    </div>
  );
}
