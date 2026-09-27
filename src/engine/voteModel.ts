import type {
  CandidateId,
  GameResult,
  GameState,
  StateBloc,
  StateContest,
  StateResult,
} from "./types";
import { sigmoid } from "./setup";

// Effective two-party Biden share for a single bloc = sigmoid of its baseline
// margin plus everything the campaign has done to it (campaignMargin). This is
// the whole vote model: legible because campaignMargin is literally the sum of
// the causes logged against that bloc.
export function blocDemShare(baselineMargin: number, campaignMargin: number): number {
  return sigmoid(baselineMargin + campaignMargin);
}

export interface ContestTally {
  stateId: string;
  demVotes: number;
  repVotes: number;
  totalVotes: number;
  demShare: number; // two-party
  winner: CandidateId;
}

// Per-point coupling of signed state momentum (Biden−Trump) into vote margin.
// Transient: momentum decays each turn, so this term fades — it never bakes
// into campaignMargin. Zero at neutral, so calibration is untouched.
const MOMENTUM_COUPLING = 0.0017;

// Live two-party Dem support for a bloc *inside its state* — baseline plus the
// campaign margin plus the state's momentum term. This is what the tally sums,
// so UI bloc bars should use it (not the static baseline `support`) to stay
// consistent with the state result.
export function liveBlocDemShare(state: StateContest, bloc: StateBloc): number {
  return blocDemShare(bloc.baselineMargin, bloc.campaignMargin + state.momentum * MOMENTUM_COUPLING);
}

// Tallies one vote-bearing contest (a contest with blocs).
export function tallyContest(state: StateContest): ContestTally {
  let demVotes = 0;
  let totalVotes = 0;
  const momentumTerm = state.momentum * MOMENTUM_COUPLING;
  for (const bloc of state.blocs) {
    const votes = bloc.size * bloc.turnoutPropensity * bloc.enthusiasm;
    const share = blocDemShare(bloc.baselineMargin, bloc.campaignMargin + momentumTerm);
    demVotes += votes * share;
    totalVotes += votes;
  }
  const repVotes = totalVotes - demVotes;
  const demShare = totalVotes > 0 ? demVotes / totalVotes : 0.5;
  return {
    stateId: state.id,
    demVotes,
    repVotes,
    totalVotes,
    demShare,
    winner: demShare >= 0.5 ? "dem" : "rep",
  };
}

// Runs the full electoral model: every contest, the ME/NE at-large aggregates,
// the EV tally, the national popular vote, and the winner (incl. 269–269 tie).
export function computeResult(game: GameState): GameResult {
  const tallies = new Map<string, ContestTally>();
  for (const st of game.states) {
    if (st.blocs.length > 0) tallies.set(st.id, tallyContest(st));
  }

  const ev: Record<CandidateId, number> = { dem: 0, rep: 0 };
  const popularVote: Record<CandidateId, number> = { dem: 0, rep: 0 };
  const stateResults: StateResult[] = [];

  for (const st of game.states) {
    let winner: CandidateId;
    let demShare: number;

    if (st.aggregateOf && st.aggregateOf.length > 0) {
      // At-large unit: winner of the combined district vote.
      let b = 0;
      let t = 0;
      for (const id of st.aggregateOf) {
        const dt = tallies.get(id);
        if (dt) {
          b += dt.demVotes;
          t += dt.repVotes;
        }
      }
      winner = b >= t ? "dem" : "rep";
      demShare = b + t > 0 ? b / (b + t) : 0.5;
      // Aggregate units add no popular vote (already counted in districts).
    } else {
      const dt = tallies.get(st.id)!;
      winner = dt.winner;
      demShare = dt.demShare;
      popularVote.dem += dt.demVotes;
      popularVote.rep += dt.repVotes;
    }

    ev[winner] += st.electoralVotes;
    stateResults.push({
      stateId: st.id,
      electoralVotes: st.electoralVotes,
      demShare,
      winner,
      margin: Math.abs(demShare - 0.5) * 2 * 100,
    });
  }

  const totalPop = popularVote.dem + popularVote.rep;
  const winner: GameResult["winner"] =
    ev.dem >= 270 ? "dem" : ev.rep >= 270 ? "rep" : "tie";

  // Post-mortem: the player's biggest self-caused swings, by magnitude.
  const postMortem = [...game.causes]
    .filter((cause) => cause.actor === game.playerCandidate && cause.marginDelta !== 0)
    .sort((a, b) => Math.abs(b.marginDelta) - Math.abs(a.marginDelta))
    .slice(0, 8);

  return {
    electoralVotes: ev,
    winner,
    popularVote,
    popularShare: {
      dem: totalPop > 0 ? popularVote.dem / totalPop : 0.5,
      rep: totalPop > 0 ? popularVote.rep / totalPop : 0.5,
    },
    stateResults,
    postMortem,
  };
}

// Lightweight EV projection used in-game (for the tally bar / AI), with a
// "tossup" band for contests inside `margin` points of 50/50.
export interface Projection {
  ev: Record<CandidateId, number>;
  tossupEv: number;
  contests: { stateId: string; demShare: number; lean: CandidateId | "tossup"; ev: number }[];
}

export function projectElection(game: GameState, tossupBand = 3): Projection {
  const result = computeResult(game);
  const ev: Record<CandidateId, number> = { dem: 0, rep: 0 };
  let tossupEv = 0;
  const contests = result.stateResults.map((sr) => {
    const pts = (sr.demShare - 0.5) * 200; // Biden margin in points
    let lean: CandidateId | "tossup";
    if (Math.abs(pts) <= tossupBand) {
      lean = "tossup";
      tossupEv += sr.electoralVotes;
    } else {
      lean = pts > 0 ? "dem" : "rep";
      ev[lean] += sr.electoralVotes;
    }
    return { stateId: sr.stateId, demShare: sr.demShare, lean, ev: sr.electoralVotes };
  });
  return { ev, tossupEv, contests };
}
