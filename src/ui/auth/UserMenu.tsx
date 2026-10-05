import { useState } from "react";
import { useAuthStore } from "@store/authStore";
import { PACKS_BY_ID } from "@content/packs";
import { CircleUser, KeyRound, LogOut, WifiOff } from "lucide-react";

// Top-right auth affordance: Login/Register buttons when signed out, a
// username dropdown (activations, redeem, logout) when signed in. When the
// API is unreachable, hide login and show an offline chip instead.
export function UserMenu() {
  const user = useAuthStore((s) => s.user);
  const unlocked = useAuthStore((s) => s.unlocked);
  const serverDown = useAuthStore((s) => s.serverDown);
  const openModal = useAuthStore((s) => s.openModal);
  const logout = useAuthStore((s) => s.logout);
  const [open, setOpen] = useState(false);

  if (serverDown && !user) {
    return (
      <div className="muted small" title="The campaign server is unreachable. Free scenarios still play locally."
        style={{ display: "flex", alignItems: "center", gap: 5 }}>
        <WifiOff size={14} /> Play offline
      </div>
    );
  }

  if (!user) {
    return (
      <div className="row" style={{ gap: 6 }}>
        <button className="ghost small" onClick={() => openModal("login")}>Log In</button>
        <button className="primary small" onClick={() => openModal("register")}>Register</button>
      </div>
    );
  }

  return (
    <div style={{ position: "relative" }}>
      <button className="ghost small" onClick={() => setOpen((o) => !o)}>
        <CircleUser size={15} style={{ verticalAlign: "-3px", marginRight: 5 }} />
        {user.username}
      </button>
      {open && (
        <div
          className="card"
          style={{ position: "absolute", right: 0, top: "calc(100% + 6px)", width: 260, zIndex: 60, padding: 14 }}
          onMouseLeave={() => setOpen(false)}
        >
          <div className="muted small" style={{ marginBottom: 8 }}>{user.email}</div>
          {serverDown && (
            <div className="muted small" style={{ marginBottom: 8, display: "flex", alignItems: "center", gap: 5 }}>
              <WifiOff size={13} /> Offline: scores won't sync
            </div>
          )}
          <div className="tag muted small">MY ACTIVATIONS</div>
          {unlocked.packIds.length === 0 && unlocked.scenarioIds.length === 0 ? (
            <p className="muted small" style={{ margin: "6px 0 10px" }}>Free scenarios only. Unlock a country for $0.99, or everything for $3.99.</p>
          ) : (
            <div style={{ margin: "6px 0 10px" }}>
              {unlocked.packIds.map((p) => (
                <div className="kv" key={p}><span className="k">📦 {PACKS_BY_ID[p]?.name ?? p}</span></div>
              ))}
              {unlocked.scenarioIds.filter((s) => !unlocked.packIds.some((p) => PACKS_BY_ID[p]?.scenarios.includes(s))).map((s) => (
                <div className="kv" key={s}><span className="k">🎫 {s}</span></div>
              ))}
            </div>
          )}
          <button className="ghost small" style={{ width: "100%", marginBottom: 6 }} onClick={() => { setOpen(false); openModal("account"); }}>
            <CircleUser size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Your account
          </button>
          <div className="row" style={{ gap: 6 }}>
            <button className="ghost small" style={{ flex: 1 }} onClick={() => { setOpen(false); openModal("activate"); }}>
              <KeyRound size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Redeem
            </button>
            <button className="ghost small" style={{ flex: 1 }} onClick={() => { setOpen(false); logout(); }}>
              <LogOut size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Log out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
