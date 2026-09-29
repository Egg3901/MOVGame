import { describe, expect, it } from "vitest";
import { createGame } from "@engine/setup";
import { projectElection } from "@engine/voteModel";
import { createUkGame, projectUk } from "@engine/ukGame";
import { movAskSnapshot, movAskUrl, movSeatAskSnapshot } from "./ask";

describe("Ask campaign handoff", () => {
  it("includes the current player-side map and omits the full save", () => {
    const game = createGame({ seed: 42, playerCandidate: "rep" });
    const projection = projectElection(game);
    const snapshot = movAskSnapshot(game, projection);
    expect(snapshot.player).toBe("rep");
    expect(snapshot.evPlayer).toBe(projection.ev.rep);
    expect(snapshot.evOpponent).toBe(projection.ev.dem);
    expect(snapshot.states.length).toBe(projection.contests.length);
    expect(JSON.stringify(snapshot)).not.toContain("rngState");
    const encoded = movAskUrl(game, projection).split("#mov=")[1];
    expect(encoded).toBeTruthy();
    const binary = atob(encoded.replace(/-/g, "+").replace(/_/g, "/"));
    const decoded = JSON.parse(new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0))));
    expect(decoded.scenario).toBe(snapshot.scenario);
  });

  it("uses the current multiparty seat projection for a UK campaign", () => {
    const game = createUkGame({ election: "2024", playerParty: "lab", seed: 42 });
    const projection = projectUk(game);
    const snapshot = movSeatAskSnapshot(game, projection, "UK");
    expect(snapshot.player).toBe("lab");
    expect(snapshot.seatPlayer).toBe(projection.seats.lab);
    expect(snapshot.regions[0].playerSeats).toBe(projection.seatResults[0].seatsByParty.lab);
    expect(snapshot.regions.length).toBe(projection.seatResults.length);
  });
});
