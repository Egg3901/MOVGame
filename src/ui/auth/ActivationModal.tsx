import { useState } from "react";
import { useAuthStore } from "@store/authStore";
import { SCENARIOS_BY_ID } from "@content/scenarioRegistry";
import { packCovered, packForScenario, PACKS_BY_ID, STORE_PACKS } from "@content/packs";
import { DISTRIBUTION } from "@lib/distribution";
import { usePackPrices } from "@lib/usePackPrices";
import { isValidActivationCode, normalizeActivationCode } from "@lib/activationCode";
import { X, KeyRound, Check } from "lucide-react";
import { Spinner } from "@ui/Skeleton";

export function ActivationModal() {
  const activate = useAuthStore((s) => s.activate);
  const user = useAuthStore((s) => s.user);
  const openModal = useAuthStore((s) => s.openModal);
  const closeModal = useAuthStore((s) => s.closeModal);
  const paywallScenarioId = useAuthStore((s) => s.paywallScenarioId);
  const buyPack = useAuthStore((s) => s.buyPack);
  const ownedPackIds = useAuthStore((s) => s.unlocked.packIds);
  const packPrices = usePackPrices();
  const [buying, setBuying] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [busy, setBusy] = useState(false);

  const scenario = paywallScenarioId ? SCENARIOS_BY_ID[paywallScenarioId] : undefined;
  const pack = paywallScenarioId ? packForScenario(paywallScenarioId) : undefined;
  const complete = PACKS_BY_ID.complete;
  const price = (id: string) => `$${((packPrices[id] ?? PACKS_BY_ID[id]?.price ?? 0) / 100).toFixed(2)}`;
  const buy = async (packId: string) => {
    setError("");
    setBuying(packId);
    const err = await buyPack(packId);
    if (err) { setError(err); setBuying(null); }
  };
  const buyButton = (packId: string, label: string, primary: boolean) =>
    packCovered(packId, ownedPackIds) ? (
      <span className="muted small"><Check size={13} style={{ verticalAlign: "-2px" }} /> Owned</span>
    ) : (
      <button className={primary ? "primary small" : "ghost small"} disabled={buying !== null} onClick={() => buy(packId)}>
        {buying === packId ? "Opening checkout…" : label}
      </button>
    );
  const normalized = normalizeActivationCode(code);
  const formatOk = normalized.length === 0 || isValidActivationCode(normalized);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setSuccess("");
    if (!isValidActivationCode(normalized)) {
      setError("Invalid format: expected CAMP-XXXX-XXXX-XXXX");
      return;
    }
    setBusy(true);
    const out = await activate(normalized);
    setBusy(false);
    if (out.error) { setError(out.error); return; }
    setCode("");
    setSuccess(out.packName ? `${out.packName} unlocked. Every scenario in the pack is now yours.` : "Scenario unlocked.");
  };

  return (
    <div className="overlay" onClick={closeModal}>
      <div className="modal" style={{ maxWidth: 460 }} onClick={(e) => e.stopPropagation()}>
        <div className="head">
          <div className="tag">Unlock</div>
          <h2><KeyRound size={18} style={{ verticalAlign: "-3px", marginRight: 6 }} />{scenario ? "Unlock this election" : "Unlock scenarios"}</h2>
          <button className="sheet-close" onClick={closeModal} aria-label="Close"><X size={18} /></button>
        </div>
        <div className="body">
          {scenario && (
            <p className="prompt">
              <strong>{scenario.flag} {scenario.label}</strong> is part of{" "}
              <strong>{pack?.name ?? "a scenario bundle"}</strong>
              {pack && <> ({pack.scenarios.length} elections, {price(pack.id)})</>}. The Complete Collection unlocks every country for {price("complete")}.
            </p>
          )}
          {DISTRIBUTION.externalStore ? (
            <div className="unlock-buy" style={{ display: "flex", flexDirection: "column", gap: 8, margin: "4px 0 14px" }}>
              {pack && pack.id !== "complete" && (
                <div className="kv" style={{ alignItems: "center" }}>
                  <span className="k">{pack.name} · {pack.scenarios.length} elections · {price(pack.id)}</span>
                  {buyButton(pack.id, `Buy ${pack.name}`, true)}
                </div>
              )}
              <div className="kv" style={{ alignItems: "center" }}>
                <span className="k">{complete.name} · every country · {price("complete")}</span>
                {buyButton("complete", "Buy everything", !pack)}
              </div>
              <p className="muted small" style={{ margin: 0 }}>
                One-time purchase through the Lakeside store, tied to your Lakeside account so it unlocks wherever you sign in.
              </p>
            </div>
          ) : (
            <p className="muted small">Bundles are sold through {DISTRIBUTION.nativeStoreName}.</p>
          )}
          <div className="tag muted small" style={{ margin: "6px 0" }}>HAVE A CODE?</div>
          {!user ? (
            <p className="muted">
              Sign in to redeem a code:{" "}
              <button className="su-link" onClick={() => openModal("login", paywallScenarioId)}>log in</button> or{" "}
              <button className="su-link" onClick={() => openModal("register", paywallScenarioId)}>register</button> first.
            </p>
          ) : (
            <form onSubmit={submit}>
              <div className="field" style={{ textAlign: "left" }}>
                <label>Code: CAMP-XXXX-XXXX-XXXX</label>
                <input
                  type="text" value={code} required autoFocus placeholder="CAMP-…"
                  onChange={(e) => setCode(e.target.value.toUpperCase())}
                  aria-invalid={!formatOk}
                  style={{ width: "100%", fontFamily: "monospace", letterSpacing: 1 }}
                />
              </div>
              {!formatOk && (
                <p className="muted small" style={{ color: "var(--rose)" }}>
                  Use the form CAMP-XXXX-XXXX-XXXX (letters A-Z except O/I/L, digits 2-9).
                </p>
              )}
              {error && <p className="muted small" style={{ color: "var(--rose)" }}>{error}</p>}
              {success && <p className="muted small" style={{ color: "var(--green)" }}>{success}</p>}
              <button className="primary" type="submit" disabled={busy || !formatOk || !normalized} style={{ width: "100%", marginTop: 8 }}>
                {busy ? <Spinner label="Redeeming…" /> : "Redeem"}
              </button>
            </form>
          )}
          <div style={{ marginTop: 16 }}>
            <div className="tag muted small" style={{ marginBottom: 6 }}>ALL BUNDLES</div>
            {STORE_PACKS.map((p) => (
              <div className="kv" key={p.id} style={{ alignItems: "center" }}>
                <span className="k">{p.name} · {p.scenarios.length} scenarios · {price(p.id)}</span>
                {DISTRIBUTION.externalStore && p.id !== pack?.id && p.id !== "complete"
                  ? buyButton(p.id, "Buy", false)
                  : null}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
