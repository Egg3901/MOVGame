import { describe, it, expect } from "vitest";
import {
  makeDefaultCustomScenario,
  validateCustomScenario,
  registerCustomScenario,
  serializeCustomScenario,
  parseCustomScenario,
  isCustomScenarioId,
  type CustomScenario,
} from "@content/customScenario";
import { getScenario } from "@content/scenarios";
import { createGame } from "@engine/setup";
import { beginGame, advanceTurn } from "@engine/turn";

describe("custom scenario schema", () => {
  it("accepts a fresh default scenario", () => {
    const result = validateCustomScenario(makeDefaultCustomScenario());
    expect(result.ok).toBe(true);
  });

  it("survives a JSON export/import round-trip unchanged", () => {
    const cs = makeDefaultCustomScenario();
    cs.label = "1968 · Rematch";
    cs.dem.name = "Test Candidate";
    cs.rep.traits.charisma = 42;
    const restored = parseCustomScenario(serializeCustomScenario(cs));
    expect(restored.label).toBe("1968 · Rematch");
    expect(restored.dem.name).toBe("Test Candidate");
    expect(restored.rep.traits.charisma).toBe(42);
    expect(restored.id).toBe(cs.id);
  });

  it("rejects bad data with readable errors", () => {
    const cs = makeDefaultCustomScenario() as unknown as Record<string, unknown>;
    const bad = structuredClone(cs);
    (bad.dem as Record<string, unknown>).name = "";
    (bad as { year: number }).year = 3000;
    ((bad.rep as Record<string, Record<string, number>>).traits).charisma = 500;
    const result = validateCustomScenario(bad);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.length).toBeGreaterThan(0);
      expect(result.errors.join(" ")).toMatch(/name is required/);
      expect(result.errors.join(" ")).toMatch(/Year must be/);
      expect(result.errors.join(" ")).toMatch(/charisma.*between 0 and 100/);
    }
  });

  it("rejects an unsupported version", () => {
    const bad = { ...makeDefaultCustomScenario(), version: 999 };
    const result = validateCustomScenario(bad);
    expect(result.ok).toBe(false);
  });

  it("rejects a non-custom id and out-of-range issue positions", () => {
    const bad = makeDefaultCustomScenario();
    bad.id = "us-2024";
    bad.dem.issuePositions.economy = 5;
    const result = validateCustomScenario(bad);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(" ")).toMatch(/must start with "custom-"/);
      expect(result.errors.join(" ")).toMatch(/economy.*between -1 and 1/);
    }
  });

  it("throws a readable error on invalid JSON", () => {
    expect(() => parseCustomScenario("{not json")).toThrow(/valid JSON/);
  });

  it("flags custom ids", () => {
    expect(isCustomScenarioId("custom-abc")).toBe(true);
    expect(isCustomScenarioId("us-2024")).toBe(false);
    expect(isCustomScenarioId(undefined)).toBe(false);
  });
});

describe("custom scenario loads into the engine", () => {
  it("registers, starts a game, and advances one turn", () => {
    const cs: CustomScenario = makeDefaultCustomScenario();
    cs.dem.name = "Ada Custom";
    cs.dem.shortName = "Custom";
    cs.rep.name = "The Rival";
    cs.rep.shortName = "Rival";

    registerCustomScenario(cs);
    expect(getScenario(cs.id).id).toBe(cs.id);

    const game = createGame({ scenario: cs.id, seed: "custom-smoke", playerCandidate: "dem" });
    expect(game.scenarioId).toBe(cs.id);
    expect(game.candidates.dem.shortName).toBe("Custom");
    expect(game.candidates.rep.shortName).toBe("Rival");
    // Standard board is intact.
    expect(game.states.reduce((s, st) => s + st.electoralVotes, 0)).toBe(538);

    const started = beginGame(game);
    expect(started.turn).toBe(0);
    const next = advanceTurn(started, [], "custom-smoke");
    expect(next.turn).toBe(1);
  });
});

// Portable native campaigns must not depend on a browser editor library.
describe("portable custom campaign documents", () => {
  it("restores a deleted registry entry from the campaign's authoring document", async () => {
    const { registerCampaignCustomScenario } = await import("../customScenario");
    const { SCENARIOS } = await import("../scenarios");
    const doc = makeDefaultCustomScenario();
    doc.id = "custom-portable-test";
    const campaign = { scenarioId: doc.id, customScenario: JSON.stringify(doc) };
    delete SCENARIOS[doc.id];
    registerCampaignCustomScenario(campaign);
    expect(SCENARIOS[doc.id].dem.name).toBe(doc.dem.name);
    delete SCENARIOS[doc.id];
    expect(() => registerCampaignCustomScenario({ scenarioId: doc.id })).toThrow("missing its authoring document");
  });
  it("rejects mismatched and malformed documents before they enter the registry", async () => {
    const { registerCampaignCustomScenario } = await import("../customScenario");
    const doc = makeDefaultCustomScenario();
    expect(() => registerCampaignCustomScenario({ scenarioId: "custom-other", customScenario: JSON.stringify(doc) })).toThrow("does not match");
    expect(() => registerCampaignCustomScenario({ scenarioId: "2024", customScenario: JSON.stringify(doc) })).toThrow("does not match");
    expect(() => registerCampaignCustomScenario({ scenarioId: doc.id, customScenario: "{" })).toThrow("valid JSON");
  });
});
