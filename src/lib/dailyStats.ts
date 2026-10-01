import type { DailyBoardEntry } from "./api";

/** Compare with the visible daily board, using the signed-in rank when available. */
export function estimatePercentile(score: number, board: DailyBoardEntry[] | null, myRank: number | null): number | null {
  if (myRank != null && board && board.length > 0) {
    return Math.max(1, Math.round((myRank / Math.max(board.length, myRank)) * 100));
  }
  if (board && board.length > 0) {
    const better = board.filter((entry) => entry.score > score).length;
    return Math.max(1, Math.round(((better + 1) / (board.length + 1)) * 100));
  }
  return null;
}
