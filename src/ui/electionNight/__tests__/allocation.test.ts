import { expect, it } from "vitest";
import { COUNTRIES } from "@content/countries";
import { createCountryGame, countryAdvanceTurn } from "@engine/countryGame";
import { countryReveal } from "../adapters";
import { buildSchedule } from "../ElectionNight";

it("Germany's reveal awards the actual seat split and never invents a majority", () => {
  let game = createCountryGame(COUNTRIES.DE, { election: "2025", seed: "seat-reveal", playerParty: "lnk" });
  while (game.phase !== "result") game = countryAdvanceTurn(game, COUNTRIES.DE, { disableAi: true, autoResolvePlayerEvents: true });
  const reveal = countryReveal(COUNTRIES.DE, game);
  const schedule = buildSchedule(reveal.units, reveal.threshold);
  const majority = Object.entries(game.result!.seats).find(([, seats]) => seats >= reveal.threshold)?.[0] ?? null;
  expect(schedule.projectedId).toBe(majority);
});
