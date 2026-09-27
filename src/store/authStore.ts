import { create } from "zustand";
import { api, ApiError, clearSession, getStoredUser, getToken, lakesideCheckoutUrl, storeSession, type ApiUser, type Purchase, type Unlocked } from "@lib/api";
import { isFreeScenario } from "@content/scenarioRegistry";
import { PACKS_BY_ID } from "@content/packs";
import { dailyAssignment, utcDateString } from "@lib/daily";

// Post-redirect notices (Stripe success/cancel, Lakeside sign-in).
export type AuthNotice =
  | { kind: "purchase-success"; packName: string | null }
  | { kind: "purchase-cancelled" }
  | { kind: "lakeside-signed-in"; username: string }
  | { kind: "lakeside-failed" };

interface AuthStore {
  user: ApiUser | null;
  unlocked: Unlocked;
  purchases: Purchase[];
  notice: AuthNotice | null;
  // Which auth modal is open, if any. `paywallScenarioId` remembers the locked
  // scenario the user clicked so we can resume after login/activation.
  modal: "login" | "register" | "activate" | "account" | null;
  paywallScenarioId: string | null;
  serverDown: boolean;

  openModal: (m: AuthStore["modal"], paywallScenarioId?: string | null) => void;
  closeModal: () => void;
  dismissNotice: () => void;

  login: (email: string, password: string) => Promise<string | null>;
  register: (username: string, email: string, password: string) => Promise<string | null>;
  activate: (code: string) => Promise<{ error?: string; packName?: string }>;
  buyPack: (packId: string) => Promise<string | null>;
  loadPurchases: () => Promise<void>;
  logout: () => void;
  refresh: () => Promise<void>;

  canPlay: (scenarioId: string) => boolean;
}

export const useAuthStore = create<AuthStore>((set, get) => ({
  user: getStoredUser(),
  unlocked: { scenarioIds: [], packIds: [] },
  purchases: [],
  notice: null,
  modal: null,
  paywallScenarioId: null,
  serverDown: false,

  openModal: (m, paywallScenarioId = null) => set({ modal: m, paywallScenarioId }),
  closeModal: () => set({ modal: null, paywallScenarioId: null }),
  dismissNotice: () => set({ notice: null }),

  login: async (email, password) => {
    try {
      const { token, user } = await api.login(email, password);
      storeSession(token, user);
      set({ user });
      await get().refresh();
      return null;
    } catch (e) {
      return e instanceof ApiError ? e.message : "Could not reach the server";
    }
  },

  register: async (username, email, password) => {
    try {
      const { token, user } = await api.register(username, email, password);
      storeSession(token, user);
      set({ user });
      return null;
    } catch (e) {
      return e instanceof ApiError ? e.message : "Could not reach the server";
    }
  },

  activate: async (code) => {
    try {
      const out = await api.activate(code);
      set({ unlocked: out.unlocked });
      return { packName: out.packName };
    } catch (e) {
      return { error: e instanceof ApiError ? e.message : "Could not reach the server" };
    }
  },

  buyPack: async (packId) => {
    // Checkout is owned by the Lakeside platform now. Hand off to it; it takes
    // over the page and returns with ?purchase=success.
    window.location.assign(lakesideCheckoutUrl(packId));
    return null;
  },

  loadPurchases: async () => {
    try {
      const { purchases } = await api.myEntitlements();
      set({ purchases: Array.isArray(purchases) ? purchases : [] });
    } catch {
      /* account view shows an empty list; nothing to do */
    }
  },

  logout: () => {
    clearSession();
    set({ user: null, unlocked: { scenarioIds: [], packIds: [] }, purchases: [] });
  },

  refresh: async () => {
    // Always probe the server so offline guests get the "play offline" UX
    // instead of lock/login prompts that can't succeed.
    if (!getToken()) {
      try {
        await api.daily();
        set({ serverDown: false });
      } catch {
        set({ serverDown: true });
      }
      return;
    }
    try {
      const { user, unlocked } = await api.me();
      // A 200 without a usable user (SPA-fallback HTML parsed as {}, an old
      // cache, a partial response) must not wipe the signed-in state or push
      // undefined into `unlocked`; treat it like an unreachable server.
      if (!user || typeof user !== "object" || typeof user.id !== "string") {
        set({ serverDown: true });
        return;
      }
      set({ user, unlocked, serverDown: false });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        clearSession();
        set({ user: null, unlocked: { scenarioIds: [], packIds: [] }, serverDown: false });
      } else {
        set({ serverDown: true });
      }
    }
  },

  canPlay: (scenarioId) => {
    if (isFreeScenario(scenarioId)) return true;
    // The Daily Challenge is free for everyone, every day, whatever the
    // paywall says: it lands on a rotating scenario (often a paid one) and the
    // server scores it ungated (routes/daily.ts). Keep the client in step so
    // the daily never prompts for sign-in or purchase.
    if (dailyAssignment(utcDateString()).scenarioId === scenarioId) return true;
    return get().unlocked.scenarioIds.includes(scenarioId);
  },
}));

// Handle redirect landings once on module load, before the entitlement
// refresh: ?lakeside_code= (Lakeside sign-in bounce) and ?purchase= (Stripe
// success/cancel). Consumed params are stripped from the URL so reloads and
// share links stay clean.
async function consumeRedirectParams(): Promise<void> {
  if (typeof window === "undefined") return;
  let params: URLSearchParams;
  try {
    params = new URLSearchParams(window.location.search);
  } catch {
    return;
  }
  const lakesideCode = params.get("lakeside_code");
  const purchase = params.get("purchase");
  const packId = params.get("pack");
  if (!lakesideCode && !purchase) return;

  for (const p of ["lakeside_code", "purchase", "pack", "session_id"]) params.delete(p);
  const clean = window.location.pathname + (params.toString() ? `?${params}` : "") + window.location.hash;
  window.history.replaceState(null, "", clean);

  if (lakesideCode) {
    try {
      const { token, user, unlocked } = await api.lakesideExchange(lakesideCode);
      storeSession(token, user);
      useAuthStore.setState({ user, unlocked, notice: { kind: "lakeside-signed-in", username: user.username } });
    } catch {
      useAuthStore.setState({ notice: { kind: "lakeside-failed" } });
    }
  }
  if (purchase === "success") {
    useAuthStore.setState({
      notice: { kind: "purchase-success", packName: packId ? PACKS_BY_ID[packId]?.name ?? null : null },
    });
  } else if (purchase === "cancelled") {
    useAuthStore.setState({ notice: { kind: "purchase-cancelled" } });
  }
}

// Restore entitlements once on module load (fire-and-forget). The redirect
// params run first so a fresh Lakeside session or purchase is reflected.
void consumeRedirectParams().then(() => useAuthStore.getState().refresh());
