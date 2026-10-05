import { useEffect, useState } from "react";
import { useAuthStore } from "@store/authStore";
import { PACKS_BY_ID } from "@content/packs";
import { isValidActivationCode, normalizeActivationCode } from "@lib/activationCode";
import { X, KeyRound, CircleUser, Link2, ShoppingBag, LogOut } from "lucide-react";
import { Spinner } from "@ui/Skeleton";
import { LakesideButton } from "./LakesideButton";

function fmtDate(ts: number): string {
  return new Date(ts).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function fmtAmount(cents: number | null, currency: string | null, provider: string): string {
  if (provider === "apple") return "App Store";
  if (provider === "google") return "Google Play";
  if (provider === "supporter") return "Supporter perk";
  if (cents == null || currency == null) return provider === "code" ? "Code" : "Purchase";
  if (cents === 0) return "Code";
  return `$${(cents / 100).toFixed(2)} ${currency.toUpperCase()}`;
}

export function AccountModal() {
  const user = useAuthStore((s) => s.user);
  const unlocked = useAuthStore((s) => s.unlocked);
  const purchases = useAuthStore((s) => s.purchases);
  const loadPurchases = useAuthStore((s) => s.loadPurchases);
  const activate = useAuthStore((s) => s.activate);
  const closeModal = useAuthStore((s) => s.closeModal);
  const logout = useAuthStore((s) => s.logout);

  const [code, setCode] = useState("");
  const [redeemMsg, setRedeemMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { void loadPurchases(); }, [loadPurchases]);

  if (!user) return null;

  const normalized = normalizeActivationCode(code);
  const submitCode = async (e: React.FormEvent) => {
    e.preventDefault();
    setRedeemMsg(null);
    if (!isValidActivationCode(normalized)) {
      setRedeemMsg({ ok: false, text: "Use the form CAMP-XXXX-XXXX-XXXX" });
      return;
    }
    setBusy(true);
    const out = await activate(normalized);
    setBusy(false);
    if (out.error) { setRedeemMsg({ ok: false, text: out.error }); return; }
    setCode("");
    setRedeemMsg({ ok: true, text: out.packName ? `${out.packName} unlocked.` : "Scenario unlocked." });
    void loadPurchases();
  };

  return (
    <div className="overlay" onClick={closeModal}>
      <div className="modal" style={{ maxWidth: 520 }} onClick={(e) => e.stopPropagation()}>
        <div className="head">
          <div className="tag">Account</div>
          <h2><CircleUser size={18} style={{ verticalAlign: "-3px", marginRight: 6 }} />Your Account</h2>
          <button className="sheet-close" onClick={closeModal} aria-label="Close"><X size={18} /></button>
        </div>
        <div className="body">

          {/* Identity */}
          <div className="account-card">
            <div className="account-row">
              <span className="k">Username</span>
              <span>{user.username}</span>
            </div>
            <div className="account-row">
              <span className="k">Email</span>
              <span>{user.email}</span>
            </div>
            <div className="account-row">
              <span className="k"><Link2 size={12} style={{ verticalAlign: "-2px", marginRight: 4 }} />Lakeside Games account</span>
              {user.ahdLinked ? (
                <span className="account-chip linked">Linked</span>
              ) : (
                <span className="account-chip">Not linked</span>
              )}
            </div>
            {!user.ahdLinked && (
              <div style={{ marginTop: 10 }}>
                <LakesideButton />
              </div>
            )}
            <p className="muted small" style={{ margin: "10px 0 0" }}>
              Your saved games sync to this account while you are signed in, so you can pick up a campaign on any device.
            </p>
          </div>

          {/* Purchases */}
          <div className="account-card">
            <div className="account-title">
              <ShoppingBag size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />Purchases
            </div>
            {purchases.length === 0 ? (
              <p className="muted small" style={{ margin: "4px 0 0" }}>
                No purchases yet. US 2024, US 2020 and the daily challenge are free; other countries unlock from any locked election.
              </p>
            ) : (
              <div>
                {purchases.map((p, i) => (
                  <div className="account-row" key={i}>
                    <span className="k">
                      {p.packName ?? (p.packId ? PACKS_BY_ID[p.packId]?.name ?? p.packId : p.scenarioId ?? "Unlock")}
                      {p.status === "refunded" && <span className="account-chip" style={{ marginLeft: 6 }}>Refunded</span>}
                    </span>
                    <span className="muted small" style={{ whiteSpace: "nowrap" }}>
                      {fmtAmount(p.amountCents, p.currency, p.provider)} · {fmtDate(p.createdAt)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Code redemption */}
          <div className="account-card">
            <div className="account-title">
              <KeyRound size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />Redeem an activation code
            </div>
            <form onSubmit={submitCode} className="row" style={{ gap: 8, marginTop: 8 }}>
              <input
                type="text" value={code} placeholder="CAMP-XXXX-XXXX-XXXX"
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                style={{ flex: 1, fontFamily: "monospace", letterSpacing: 1, minWidth: 0 }}
              />
              <button className="primary small" type="submit" disabled={busy || !normalized}>
                {busy ? <Spinner label="Redeeming…" /> : "Redeem"}
              </button>
            </form>
            {redeemMsg && (
              <p className="muted small" style={{ margin: "8px 0 0", color: redeemMsg.ok ? "var(--green)" : "var(--rose)" }}>
                {redeemMsg.text}
              </p>
            )}
            {(unlocked.packIds.length > 0 || unlocked.scenarioIds.length > 0) && (
              <p className="muted small" style={{ margin: "8px 0 0" }}>
                Unlocked: {unlocked.packIds.map((p) => PACKS_BY_ID[p]?.name ?? p).join(", ")}
                {unlocked.packIds.length > 0 && unlocked.scenarioIds.some((s) => !unlocked.packIds.some((p) => PACKS_BY_ID[p]?.scenarios.includes(s))) && ", "}
                {unlocked.scenarioIds.filter((s) => !unlocked.packIds.some((p) => PACKS_BY_ID[p]?.scenarios.includes(s))).join(", ")}
              </p>
            )}
          </div>

          <div className="row" style={{ justifyContent: "flex-end", marginTop: 4 }}>
            <button className="ghost small" onClick={() => { closeModal(); logout(); }}>
              <LogOut size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Log out
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
