import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { estimatePercentile } from "../../../src/lib/dailyStats";
import type { DailyBoardEntry } from "../../../src/lib/api";

const vectors = [];
for (const score of [0, 1, 333, 500, 999, 1000]) {
  for (const scores of [null, [], [500], [1000, 500, 500, 0], Array.from({ length: 25 }, (_, i) => 1000 - i * 20)]) {
    for (const rank of [null, 1, 2, 25, 26, 100]) {
      const board = scores?.map((value, i) => ({ score: value, rank: i + 1 })) as DailyBoardEntry[] | null;
      vectors.push({ score, scores, rank, result: estimatePercentile(score, board, rank) });
    }
  }
}
const text = '// Generated from the web daily percentile function, including ties and ranks beyond the visible board.\npackage com.lakesidegames.electioneer.engine\n\ninternal const val DAILY_PERCENTILE_WEB_VECTORS = ' + JSON.stringify(JSON.stringify(vectors)) + '\n';
const target = fileURLToPath(new URL('../shared/src/commonTest/kotlin/com/lakesidegames/electioneer/engine/DailyPercentileWebVectors.kt', import.meta.url));
if (process.argv.includes('--check')) {
  if (readFileSync(target, 'utf8') !== text) throw Error('Daily percentile vectors are stale');
} else writeFileSync(target, text);
console.log(`Verified ${vectors.length} daily percentile cases.`);
