import type { SaveMeta, SaveRecord } from "./types";

const US_SESSION_SAVE_KEY = "campaign-us-session-autosave";

export function saveUsSessionAutosave(record: SaveRecord): void {
  try { localStorage.setItem(US_SESSION_SAVE_KEY, JSON.stringify(record)); } catch { /* unavailable or full */ }
}

export function loadUsSessionAutosave(): SaveRecord | null {
  try {
    const raw = localStorage.getItem(US_SESSION_SAVE_KEY);
    if (!raw) return null;
    const record = JSON.parse(raw) as SaveRecord;
    return record.id === "autosave" && typeof record.updatedAt === "number" &&
      typeof record.state?.turn === "number" && !!record.state?.playerCandidate ? record : null;
  } catch { return null; }
}

export function removeUsSessionAutosave(): void {
  try { localStorage.removeItem(US_SESSION_SAVE_KEY); } catch { /* unavailable */ }
}

export type ResumeTarget =
  | { kind: "us"; saveId: string }
  | { kind: "uk" }
  | { kind: "country"; countryId: string };

export interface ResumeOption {
  target: ResumeTarget;
  label: string;
  turn: number;
  updatedAt: number;
}

const COUNTRY_NAMES: Record<string, string> = {
  CA: "Canada", DE: "Germany", FR: "France", AU: "Australia",
};

export function listResumableCampaigns(usSaves: SaveMeta[]): ResumeOption[] {
  const options: ResumeOption[] = usSaves.map((save) => ({
    target: { kind: "us", saveId: save.id },
    label: save.id === "autosave" ? "US campaign" : save.name,
    turn: save.turn,
    updatedAt: save.updatedAt,
  }));
  const mirror = loadUsSessionAutosave();
  if (mirror) {
    const existing = options.find((option) => option.target.kind === "us" && option.target.saveId === "autosave");
    if (!existing || mirror.updatedAt > existing.updatedAt) {
      if (existing) options.splice(options.indexOf(existing), 1);
      options.push({ target: { kind: "us", saveId: "autosave" }, label: "US campaign", turn: mirror.turn, updatedAt: mirror.updatedAt });
    }
  }
  if (typeof localStorage === "undefined") return options;
  for (const [key, target, countryName] of [
    ["campaign-uk-autosave", { kind: "uk" }, "UK"],
    ...Object.entries(COUNTRY_NAMES).map(([countryId, name]) => [
      `campaign-country-autosave-${countryId}`, { kind: "country", countryId }, name,
    ]),
  ] as [string, ResumeTarget, string][]) {
    try {
      const raw = localStorage.getItem(key);
      if (!raw) continue;
      const record = JSON.parse(raw) as { updatedAt?: number; state?: { turn?: number; label?: string } };
      if (typeof record.state?.turn !== "number") continue;
      options.push({
        target,
        label: `${countryName} · ${record.state.label ?? "campaign"}`,
        turn: record.state.turn,
        updatedAt: record.updatedAt ?? 0,
      });
    } catch {
      // A corrupt or unavailable local entry cannot hide the other saves.
    }
  }
  return options.sort((a, b) => b.updatedAt - a.updatedAt);
}
