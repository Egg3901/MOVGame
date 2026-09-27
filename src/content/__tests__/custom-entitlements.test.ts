import { describe, it, expect, beforeEach } from "vitest";
import {
  makeDefaultCustomScenario,
  makeDefaultMultiparty,
  baseScenarioId,
  baseScenarioIdForCustom,
  serializeCustomScenario,
  parseCustomScenario,
} from "@content/customScenario";
import { packForScenario } from "@content/packs";
import { PAYWALL_ENABLED } from "@content/scenarioRegistry";
import { dailyAssignment, utcDateString } from "@lib/daily";
import { useAuthStore } from "@store/authStore";

// Reset entitlement state to a signed-out guest before each case.
beforeEach(() => {
  useAuthStore.setState({ user: null, unlocked: { scenarioIds: [], packIds: [] } });
});

// A signed-out guest can play a paid base only when the paywall is off, or when
// that base happens to be today's (always-free) Daily Challenge scenario.
function guestCanPlay(id: string): boolean {
  return !PAYWALL_ENABLED || dailyAssignment(utcDateString()).scenarioId === id;
}

describe("custom base-election entitlement mapping", () => {
  it("US customs build on the free generic map (no base pack)", () => {
    const cs = makeDefaultCustomScenario();
    expect(baseScenarioIdForCustom(cs)).toBeNull();
  });

  it("maps a UK custom to its real base election id", () => {
    const cs = makeDefaultMultiparty("uk");
    const id = baseScenarioIdForCustom(cs);
    expect(id).toBe(`uk-${cs.mp!.baseElection}`);
    // That base is a real, paid registry scenario in a pack.
    expect(packForScenario(id!)?.id).toBe("uk-elections");
  });

  it("maps a country custom to its lowercased country base election id", () => {
    const cs = makeDefaultMultiparty("country", "DE");
    const id = baseScenarioIdForCustom(cs);
    expect(id).toBe(`de-${cs.mp!.baseElection}`);
    expect(packForScenario(id!)?.id).toBe("global");
  });

  it("baseScenarioId handles missing parts safely", () => {
    expect(baseScenarioId("us", undefined, "2024")).toBeNull();
    expect(baseScenarioId("uk", undefined, undefined)).toBeNull();
    expect(baseScenarioId("country", undefined, "2025")).toBeNull();
  });
});

describe("canPlay gate on custom base elections", () => {
  it("US custom (free base) is playable signed out", () => {
    const cs = makeDefaultCustomScenario();
    const id = baseScenarioIdForCustom(cs);
    // Free path: no base id to check, so the launch gate never blocks it.
    expect(id).toBeNull();
  });

  it("paid UK base gate follows the paywall flag when signed out", () => {
    const cs = makeDefaultMultiparty("uk");
    const id = baseScenarioIdForCustom(cs)!;
    expect(useAuthStore.getState().canPlay(id)).toBe(guestCanPlay(id));
  });

  it("paid UK base is allowed once the scenario is unlocked", () => {
    const cs = makeDefaultMultiparty("uk");
    const id = baseScenarioIdForCustom(cs)!;
    useAuthStore.setState({ unlocked: { scenarioIds: [id], packIds: ["uk-elections"] } });
    expect(useAuthStore.getState().canPlay(id)).toBe(true);
  });
});

describe("import of a paid-base custom", () => {
  it("loads without crashing and reports locked state signed out", () => {
    const cs = makeDefaultMultiparty("country", "CA");
    const restored = parseCustomScenario(serializeCustomScenario(cs));
    const id = baseScenarioIdForCustom(restored)!;
    // The doc is valid and imports fine...
    expect(restored.engine).toBe("country");
    // ...and the base gate follows the paywall flag (locked when it is on).
    expect(useAuthStore.getState().canPlay(id)).toBe(guestCanPlay(id));
    expect(packForScenario(id)?.name).toBeTruthy();
  });
});
