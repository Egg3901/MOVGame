import { useMemo, useState, type ReactNode } from "react";
import { PAYWALL_ENABLED, SCENARIO_REGISTRY, type CountryCode, type ScenarioMeta } from "@content/scenarioRegistry";
import { PACKS } from "@content/packs";
import { countryCover } from "@content/covers";
import { useAuthStore } from "@store/authStore";
import { usePackPrices } from "@lib/usePackPrices";
import { DISTRIBUTION } from "@lib/distribution";
import { UserMenu } from "@ui/auth/UserMenu";
import { DailyCard } from "@ui/DailyCard";
import { ScenarioArt } from "@ui/ScenarioArt";
import { EvBar } from "@ui/EvBar";
import { Sparkline } from "@ui/Sparkline";
import {
  Lock, Play, Trophy, KeyRound, ChevronLeft, ChevronRight, Check, X, ShoppingBag,
  LineChart, Mic2, Globe2, MessageCircleQuestion, Search,
} from "lucide-react";
import { BRAND } from "../brand";
import { useGameStore } from "@store/gameStore";
import { listResumableCampaigns, type ResumeTarget } from "@persistence/resume";

const COUNTRY_NAMES: Record<CountryCode, string> = {
  US: "United States",
  UK: "United Kingdom",
  CA: "Canada",
  DE: "Germany",
  FR: "France",
  AU: "Australia",
};

const COUNTRY_BLURBS: Record<CountryCode, string> = {
  US: "Winner-take-all duels for 270 electoral votes.",
  UK: "Multiparty FPTP: coalitions, landslides, hung parliaments.",
  CA: "343 seats, five parties, coast to coast to coast.",
  DE: "Proportional Bundestag politics and the firewall.",
  FR: "The two-round runoff for the Élysée.",
  AU: "Preferential voting across 150 seats.",
};

export type LandingDestination =
  | { kind: "us"; scenarioId: string }   // native id, e.g. "2024"
  | { kind: "uk"; electionId?: string }
  | { kind: "country"; countryId: string; electionId?: string }
  | { kind: "leaderboard" }
  | { kind: "daily" }                    // App resolves via dailyAssignment()
  | { kind: "legal"; tab?: "privacy" | "terms" };

const DIFF_COLOR: Record<ScenarioMeta["difficulty"], string> = {
  easy: "var(--green)",
  medium: "var(--gold)",
  hard: "var(--rose)",
};

function CoverImg({ src, alt, tall }: { src?: string; alt: string; tall?: boolean }) {
  if (!src) return null;
  return (
    <img
      className={`scenario-cover${tall ? " tall" : ""}`}
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
    />
  );
}

function ScenarioCard({ s, unlocked, onPlay, onLocked, showCover = true }: {
  s: ScenarioMeta;
  unlocked: boolean;
  onPlay: () => void;
  onLocked: () => void;
  showCover?: boolean;
}) {
  return (
    <button
      type="button"
      className={`scenario-card${showCover ? " has-cover" : ""}`}
      style={{ position: "relative", textAlign: "left", alignItems: showCover ? "stretch" : "flex-start", opacity: unlocked ? 1 : 0.75, minHeight: showCover ? undefined : 96 }}
      onClick={unlocked ? onPlay : onLocked}
      title={s.description}
    >
      {showCover && (
        <ScenarioArt
          scenarioId={s.scenarioId}
          country={s.country}
          year={s.year}
        />
      )}
      <span className="row" style={{ gap: 8, alignItems: "center", width: "100%" }}>
        <span style={{ fontSize: 18 }}>{s.flag}</span>
        <span className="scenario-year" style={{ fontSize: 15 }}>{s.label.split("·")[0].trim()}</span>
        <span style={{ marginLeft: "auto", display: "inline-flex", gap: 6, alignItems: "center" }}>
          <span style={{ fontSize: 10, textTransform: "uppercase", letterSpacing: 1, color: DIFF_COLOR[s.difficulty] }}>{s.difficulty}</span>
          {s.free && PAYWALL_ENABLED ? (
            <span style={{ fontSize: 10, fontWeight: 800, color: "var(--green)", border: "1px solid var(--green)", borderRadius: 4, padding: "1px 5px" }}>FREE</span>
          ) : !unlocked ? (
            <Lock size={13} style={{ color: "var(--gold)" }} />
          ) : (
            <Play size={13} style={{ color: "var(--green)" }} />
          )}
        </span>
      </span>
      <span className="scenario-match" style={{ fontWeight: 700 }}>{s.label.split("·")[1]?.trim() ?? s.label}</span>
      <span className="muted small" style={{ fontSize: 11, lineHeight: 1.4 }}>{s.description}</span>
      {(s.scenarioId === "us-1984" || s.scenarioId === "uk-1983" || s.scenarioId === "uk-1997" || s.scenarioId === "uk-2001" || s.scenarioId === "uk-2024" || s.scenarioId === "fr-2017") && (
        <span className="muted small" style={{ fontSize: 10, color: "var(--rose)" }}>Historical tide</span>
      )}
      {s.scenarioId === "uk-2017" && (
        <span className="muted small" style={{ fontSize: 10, color: "var(--gold)" }}>Coin-flip election</span>
      )}
      {!unlocked && (
        <span className="muted small" style={{ fontSize: 10, color: "var(--gold)" }}>
          <KeyRound size={10} style={{ verticalAlign: "-1px" }} /> Unlock with a code
        </span>
      )}
    </button>
  );
}

// Sample polling swing used only to illustrate the mechanic on the landing
// page. Not tied to any real scenario or player data.
const SAMPLE_POLL_TREND = [0.47, 0.49, 0.48, 0.51, 0.53, 0.52, 0.55];

// Mock projection shape (same type EvBar takes in-game) used purely as an
// illustration of the electoral tally bar. Numbers are made up.
const SAMPLE_PROJECTION = {
  ev: { dem: 236, rep: 235 },
  tossupEv: 67,
} as unknown as Parameters<typeof EvBar>[0]["projection"];

function FeatureBlock({
  icon, title, blurb, children,
}: { icon: ReactNode; title: string; blurb: string; children: ReactNode }) {
  return (
    <div className="feature-card">
      <div className="feature-illustration">{children}</div>
      <span className="feature-icon">{icon}</span>
      <h3>{title}</h3>
      <p className="muted small" style={{ lineHeight: 1.5 }}>{blurb}</p>
    </div>
  );
}

function FeaturesSection() {
  return (
    <div className="field" style={{ margin: "8px 0 0" }}>
      <div className="feature-grid">
        <FeatureBlock
          icon={<LineChart size={18} />}
          title="Polling that moves like the real thing"
          blurb="Every rally, ad buy, and news cycle nudges a state's polling average. Track the swing turn by turn instead of watching a single fixed number."
        >
          <Sparkline values={SAMPLE_POLL_TREND} color="var(--dem-bright)" />
        </FeatureBlock>
        <FeatureBlock
          icon={<Mic2 size={18} />}
          title="Debates and events change the race"
          blurb="Scripted debates, scandals, and endorsements land on the calendar and shift the electoral map. The tally bar updates live as tossup states break."
        >
          <EvBar projection={SAMPLE_PROJECTION} />
        </FeatureBlock>
        <FeatureBlock
          icon={<Globe2 size={18} />}
          title="Six countries, six voting systems"
          blurb="US electoral college duels, UK multiparty first-past-the-post with hung parliaments, French two-round runoffs, German proportional Bundestag seats, and more."
        >
          <div className="feature-flags">
            {["\u{1F1FA}\u{1F1F8}", "\u{1F1EC}\u{1F1E7}", "\u{1F1E9}\u{1F1EA}", "\u{1F1EB}\u{1F1F7}", "\u{1F1E8}\u{1F1E6}", "\u{1F1E6}\u{1F1FA}"].map((f) => (
              <span key={f}>{f}</span>
            ))}
          </div>
        </FeatureBlock>
      </div>
    </div>
  );
}

const FAQ_ITEMS: { q: string; a: string }[] = [
  {
    q: "What's actually free?",
    a: "Two full scenarios plus a new daily challenge every day, no account and no payment required. Nothing is time limited or ad supported.",
  },
  {
    q: "What do I get if I pay?",
    a: "Scenario packs unlock more elections (extra US years, the UK, Canada, Germany, France, Australia). It's a one time purchase per pack through the Lakeside store, not a subscription.",
  },
  {
    q: "Where do I buy packs?",
    a: `Through lakesidegames.net/account/store. After checkout the pack unlocks automatically on your account, or you can redeem a code from the "Unlock with a code" prompt on a locked scenario.`,
  },
  {
    q: "Can I play offline or without an account?",
    a: "Yes for the free scenarios and daily challenge: progress saves in your browser. An account is only needed to keep a purchased pack across devices and to appear on leaderboards.",
  },
  {
    q: "What's your refund policy?",
    a: "Refunds for scenario packs follow the Lakeside store's standard policy. Contact Lakeside support through the store or reach us directly if a purchase didn't unlock correctly.",
  },
  {
    q: "What platforms does this run on?",
    a: "It's a browser game: desktop and mobile, any modern browser, nothing to install.",
  },
];

function FaqSection() {
  const items = FAQ_ITEMS.map((item) => {
    if (DISTRIBUTION.externalStore) return item;
    if (item.q === "What do I get if I pay?") {
      return { q: item.q, a: `Scenario packs unlock more elections. They are one time purchases managed by ${DISTRIBUTION.nativeStoreName}, not subscriptions.` };
    }
    if (item.q === "Where do I buy packs?") {
      return { q: item.q, a: `Purchases and purchase restoration will be handled by ${DISTRIBUTION.nativeStoreName} in this edition.` };
    }
    if (item.q === "What's your refund policy?") {
      return { q: item.q, a: `Purchases in this edition follow ${DISTRIBUTION.nativeStoreName} policies.` };
    }
    if (item.q === "What platforms does this run on?") {
      return { q: item.q, a: "Margin of Victory is available on the web and is being packaged for desktop and mobile." };
    }
    return item;
  });
  return (
    <div className="field" style={{ textAlign: "left", margin: "36px 0 0" }}>
      <label><MessageCircleQuestion size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Frequently asked</label>
      <div className="faq-list">
        {items.map((item) => (
          <details className="faq-item" key={item.q}>
            <summary>{item.q}</summary>
            <p className="muted small" style={{ lineHeight: 1.55 }}>{item.a}</p>
          </details>
        ))}
      </div>
    </div>
  );
}

// Quiet one-line banner for post-redirect notices (Stripe result, Lakeside
// sign-in). Success gets the green edge; cancel stays neutral.
function NoticeBanner() {
  const notice = useAuthStore((s) => s.notice);
  const dismiss = useAuthStore((s) => s.dismissNotice);
  if (!notice) return null;
  const good = notice.kind === "purchase-success" || notice.kind === "lakeside-signed-in";
  const text =
    notice.kind === "purchase-success"
      ? `Payment complete. ${notice.packName ?? "Your pack"} is unlocked on this account.`
      : notice.kind === "purchase-cancelled"
        ? "Checkout cancelled. Nothing was charged."
        : notice.kind === "lakeside-signed-in"
          ? `Signed in as ${notice.username} with your Lakeside Games account.`
          : "That sign in link expired. Try again from the login screen.";
  return (
    <div className={`notice-banner${good ? " good" : ""}`} role="status">
      {good ? <Check size={14} /> : null}
      <span>{text}</span>
      <button className="notice-close" onClick={dismiss} aria-label="Dismiss"><X size={14} /></button>
    </div>
  );
}

export function LandingPage({ onGo, onResume }: { onGo: (dest: LandingDestination) => void; onResume: (target: ResumeTarget) => void }) {
  const usSaves = useGameStore((s) => s.saves);
  const currentUsGame = useGameStore((s) => s.game);
  const resumable = listResumableCampaigns(usSaves);
  if (currentUsGame) {
    const autosaveIndex = resumable.findIndex((option) => option.target.kind === "us" && option.target.saveId === "autosave");
    if (autosaveIndex >= 0) resumable.splice(autosaveIndex, 1);
    resumable.unshift({ target: { kind: "us", saveId: "autosave" }, label: "US campaign", turn: currentUsGame.turn, updatedAt: Date.now() });
  }
  const canPlay = useAuthStore((s) => s.canPlay);
  const unlocked = useAuthStore((s) => s.unlocked);
  const openModal = useAuthStore((s) => s.openModal);
  const user = useAuthStore((s) => s.user);
  const buyPack = useAuthStore((s) => s.buyPack);
  const packPrices = usePackPrices();
  const [country, setCountry] = useState<CountryCode | null>(null);
  const [query, setQuery] = useState("");
  const [difficultyFilter, setDifficultyFilter] = useState<"all" | ScenarioMeta["difficulty"]>("all");
  const [accessFilter, setAccessFilter] = useState<"all" | "playable" | "locked">("all");
  const [catalogView, setCatalogView] = useState<"compact" | "visual">("compact");
  const [buyBusy, setBuyBusy] = useState<string | null>(null);
  const [buyError, setBuyError] = useState<string | null>(null);

  const buy = async (packId: string) => {
    if (!user) { openModal("register"); return; }
    setBuyBusy(packId);
    setBuyError(null);
    const err = await buyPack(packId);
    setBuyBusy(null);
    if (err) setBuyError(err);
  };

  // Country-first browsing: one card per country, then that country's
  // elections (newest first) once picked.
  const countries = useMemo(() => {
    const order: CountryCode[] = [];
    for (const s of SCENARIO_REGISTRY) if (!order.includes(s.country)) order.push(s.country);
    return order.map((code) => {
      const rows = SCENARIO_REGISTRY.filter((s) => s.country === code);
      const years = rows.map((r) => r.year);
      return {
        code,
        flag: rows[0].flag,
        name: COUNTRY_NAMES[code],
        blurb: COUNTRY_BLURBS[code],
        count: rows.length,
        from: Math.min(...years),
        to: Math.max(...years),
        cover: countryCover(code),
      };
    });
  }, []);

  const normalizedQuery = query.trim().toLowerCase();
  const scenarios = useMemo(() => {
    const inCountry = country
      ? SCENARIO_REGISTRY.filter((s) => s.country === country)
      : SCENARIO_REGISTRY;
    const matches = normalizedQuery
      ? inCountry.filter((s) => [
        s.label,
        s.description,
        String(s.year),
        COUNTRY_NAMES[s.country],
        s.difficulty,
      ].some((value) => value.toLowerCase().includes(normalizedQuery)))
      : inCountry;
    return [...matches].sort((a, b) => b.year - a.year);
  }, [country, normalizedQuery]);

  const visibleScenarios = scenarios.filter((s) => {
    if (difficultyFilter !== "all" && s.difficulty !== difficultyFilter) return false;
    if (accessFilter === "playable" && !canPlay(s.scenarioId)) return false;
    if (accessFilter === "locked" && canPlay(s.scenarioId)) return false;
    return true;
  });
  const hasCatalogFilter = difficultyFilter !== "all" || accessFilter !== "all";
  const playableScenarios = visibleScenarios.filter((s) => canPlay(s.scenarioId));

  const free = SCENARIO_REGISTRY.filter((s) => s.free);

  const play = (s: ScenarioMeta) => {
    if (s.engine === "us") onGo({ kind: "us", scenarioId: s.nativeId });
    else if (s.engine === "uk") onGo({ kind: "uk", electionId: s.nativeId });
    else onGo({ kind: "country", countryId: s.country, electionId: s.nativeId });
  };

  const locked = (s: ScenarioMeta) => {
    openModal(user ? "activate" : "login", s.scenarioId);
  };

  const playRandom = () => {
    if (!playableScenarios.length) return;
    const pick = playableScenarios[Math.floor(Math.random() * playableScenarios.length)];
    play(pick);
  };

  return (
    <div className="landing">
      {/* Sticky topbar: game brand left, auth right */}
      <div className="landing-topbar">
        <div className="landing-topbar-inner">
          <span className="landing-brand">
            <span className="mark"><img src="brand/margin-of-victory-icon.png" alt="" width={24} height={24} /></span>{BRAND.name}
          </span>
          <span className="row" style={{ gap: 6 }}>
            {DISTRIBUTION.externalStore && (
              <a className="ghost small" href={BRAND.storeUrl}>
                <ShoppingBag size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Store
              </a>
            )}
            <button className="ghost small" onClick={() => onGo({ kind: "leaderboard" })}>
              <Trophy size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Leaderboard
            </button>
            <UserMenu />
          </span>
        </div>
      </div>

      <div className="landing-body">
        <NoticeBanner />
        <header className="landing-hero">
          <div className="landing-hero-copy">
            <div className="kicker">Turn-based election campaign sim · {BRAND.eyebrow}</div>
            <h1>{BRAND.name}</h1>
            <p className="sub">
              Run a real campaign turn by turn: allocate a budget, target battlegrounds, survive debates,
              and watch the map move. {SCENARIO_REGISTRY.length} elections across {new Set(SCENARIO_REGISTRY.map((s) => s.country)).size} countries.
              {PAYWALL_ENABLED
                ? " Two complete scenarios and the daily challenge are free forever."
                : " All of them are free to play right now."}
            </p>
            <div className="landing-hero-actions">
              <button className="primary" onClick={() => play(free[0])}><Play size={15} /> Play free</button>
              {resumable[0] && (
                <button className="ghost" onClick={() => onResume(resumable[0].target)}>
                  Resume {resumable[0].label} · Week {resumable[0].turn + 1}
                </button>
              )}
              <button className="ghost" onClick={() => document.getElementById("campaign-catalog")?.scrollIntoView({ behavior: "smooth" })}>
                Browse 49 campaigns
              </button>
            </div>
            {resumable.length > 1 && (
              <div className="row" style={{ gap: 8, flexWrap: "wrap", marginTop: 8 }} aria-label="Saved campaigns">
                {resumable.slice(1, 5).map((save) => (
                  <button key={JSON.stringify(save.target)} className="ghost small" onClick={() => onResume(save.target)}>
                    Load {save.label} · Week {save.turn + 1}
                  </button>
                ))}
              </div>
            )}
            <div className="landing-proof" aria-label="Product highlights">
              <span>No account needed</span><span>20 to 40 minute campaigns</span><span>No subscription</span>
            </div>
          </div>
          <div className="landing-hero-art" aria-label="Campaigns from around the world">
            <div className="hero-art-card hero-art-main">
              <CoverImg src={countryCover("US")} alt="United States election campaigns" />
              <span><b>17 US elections</b><small>Fight for 270</small></span>
            </div>
            <div className="hero-art-card hero-art-side hero-art-uk">
              <CoverImg src={countryCover("UK")} alt="United Kingdom election campaigns" />
              <span><b>United Kingdom</b><small>Win a working majority</small></span>
            </div>
            <div className="hero-art-card hero-art-side hero-art-world">
              <CoverImg src={countryCover("FR")} alt="French election campaigns" />
              <span><b>Six countries</b><small>Six voting systems</small></span>
            </div>
          </div>
        </header>

        <FeaturesSection />

        {/* Daily Challenge: one seeded race, same for everyone, every day */}
        <div className="field" style={{ textAlign: "left", margin: "28px 0 0" }}>
          <DailyCard onPlay={() => onGo({ kind: "daily" })} />
        </div>

        {/* Featured starters, text-led (portraits live in the setup wizard) */}
        <div className="field" id="campaign-catalog" style={{ textAlign: "left", margin: "36px 0 0", scrollMarginTop: 76 }}>
          <label>{PAYWALL_ENABLED ? "Play free, no account needed" : "Start here, no account needed"}</label>
          <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(2, 1fr)" }}>
            {free.map((s) => (
              <ScenarioCard key={s.scenarioId} s={s} unlocked showCover={false} onPlay={() => play(s)} onLocked={() => {}} />
            ))}
          </div>
        </div>

        {/* Full catalog: search globally or pick a country, then an election. */}
        <div className="field" style={{ textAlign: "left", margin: "36px 0 0" }}>
          <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
            <label>
              {normalizedQuery
                ? `${visibleScenarios.length} matching election${visibleScenarios.length === 1 ? "" : "s"}${country ? ` in ${COUNTRY_NAMES[country]}` : ""}`
                : hasCatalogFilter
                  ? `${visibleScenarios.length} campaign${visibleScenarios.length === 1 ? "" : "s"}${country ? ` in ${COUNTRY_NAMES[country]}` : ""}`
                : country
                  ? `${COUNTRY_NAMES[country]}: pick an election`
                  : "Browse all campaigns"}
            </label>
            <span className="row" style={{ gap: 4 }}>
              {normalizedQuery && (
                <button className="ghost small" onClick={() => setQuery("")}>Clear search</button>
              )}
              {hasCatalogFilter && (
                <button className="ghost small" onClick={() => { setDifficultyFilter("all"); setAccessFilter("all"); }}>
                  Reset filters
                </button>
              )}
              {country && (
                <button className="ghost small" onClick={() => setCountry(null)}>
                  <ChevronLeft size={13} style={{ verticalAlign: "-2px" }} /> All countries
                </button>
              )}
            </span>
          </div>
          <div className="catalog-search">
            <Search size={15} aria-hidden="true" />
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              aria-label="Search election scenarios"
              placeholder={country ? `Search ${COUNTRY_NAMES[country]} elections` : "Search by year, country, candidate, or issue"}
            />
          </div>
          <div className="catalog-filters" aria-label="Filter election scenarios">
            <span className="catalog-filter-group">
              <span className="catalog-filter-label">Difficulty</span>
              {(["all", "easy", "medium", "hard"] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  className={difficultyFilter === value ? "sel" : ""}
                  aria-pressed={difficultyFilter === value}
                  onClick={() => setDifficultyFilter(value)}
                >
                  {value === "all" ? "Any" : value[0].toUpperCase() + value.slice(1)}
                </button>
              ))}
            </span>
            {PAYWALL_ENABLED && (
              <span className="catalog-filter-group">
                <span className="catalog-filter-label">Access</span>
                {(["all", "playable", "locked"] as const).map((value) => (
                  <button
                    key={value}
                    type="button"
                    className={accessFilter === value ? "sel" : ""}
                    aria-pressed={accessFilter === value}
                    onClick={() => setAccessFilter(value)}
                  >
                    {value === "all" ? "All" : value[0].toUpperCase() + value.slice(1)}
                  </button>
                ))}
              </span>
            )}
            <span className="catalog-filter-group" aria-label="Catalog view">
              <span className="catalog-filter-label">View</span>
              {(["compact", "visual"] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  className={catalogView === value ? "sel" : ""}
                  aria-pressed={catalogView === value}
                  onClick={() => setCatalogView(value)}
                >
                  {value[0].toUpperCase() + value.slice(1)}
                </button>
              ))}
            </span>
            {(country || normalizedQuery || hasCatalogFilter) && playableScenarios.length > 0 && (
              <button type="button" className="catalog-random" onClick={playRandom}>
                <Play size={12} aria-hidden="true" /> Pick a playable campaign
              </button>
            )}
          </div>

          {!country && !normalizedQuery && !hasCatalogFilter ? (
            <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))" }}>
              {countries.map((c) => (
                <button
                  key={c.code}
                  type="button"
                  className={`scenario-card${c.cover ? " has-cover" : ""}`}
                  style={{ textAlign: "left", alignItems: c.cover ? "stretch" : "flex-start", minHeight: c.cover ? undefined : 88 }}
                  onClick={() => setCountry(c.code)}
                >
                  <CoverImg src={c.cover} alt="" tall />
                  <span className="row" style={{ gap: 10, alignItems: "center", width: "100%" }}>
                    <span style={{ fontSize: 24 }}>{c.flag}</span>
                    <span className="scenario-year" style={{ fontSize: 16 }}>{c.name}</span>
                    <ChevronRight size={15} style={{ marginLeft: "auto", color: "var(--muted)" }} />
                  </span>
                  <span className="scenario-match" style={{ fontWeight: 700 }}>
                    {c.count === 1 ? `1 election · ${c.to}` : `${c.count} elections · ${c.from}-${c.to}`}
                  </span>
                  <span className="muted small" style={{ fontSize: 11, lineHeight: 1.4 }}>{c.blurb}</span>
                </button>
              ))}
            </div>
          ) : visibleScenarios.length ? (
            <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))" }}>
              {visibleScenarios.map((s) => (
                <ScenarioCard
                  key={s.scenarioId}
                  s={s}
                  unlocked={canPlay(s.scenarioId)}
                  onPlay={() => play(s)}
                  onLocked={() => locked(s)}
                  showCover={catalogView === "visual"}
                />
              ))}
            </div>
          ) : (
            <div className="catalog-empty" role="status">
              No elections match these filters{normalizedQuery ? ` for "${query.trim()}"` : ""}{country ? ` in ${COUNTRY_NAMES[country]}` : ""}.
            </div>
          )}
        </div>

        {/* Pricing: honest free vs paid, no fake tiers */}
        <div className="field" style={{ textAlign: "left", margin: "44px 0 0" }}>
          <label>Pricing</label>
          <div className="pricing-grid">
            <div className="pricing-card">
              <h3>Free</h3>
              <p className="pricing-price">$0</p>
              <ul className="pricing-list">
                <li><Check size={13} /> {PAYWALL_ENABLED ? "2 full scenarios" : `All ${SCENARIO_REGISTRY.length} scenarios`}, no account needed</li>
                <li><Check size={13} /> A new daily challenge every day</li>
                <li><Check size={13} /> Leaderboards with a free account</li>
              </ul>
              <p className="muted small" style={{ marginTop: 8 }}>No ads, no time limit, no trial.</p>
            </div>
            <div className="pricing-card">
              <h3>Scenario packs</h3>
              <p className="pricing-price">{PAYWALL_ENABLED ? "Priced per pack" : "Free for now"}</p>
              <ul className="pricing-list">
                <li><Check size={13} /> {SCENARIO_REGISTRY.length - free.length} additional elections across {PACKS.length} packs</li>
                {PAYWALL_ENABLED ? (
                  <>
                    <li><Check size={13} /> One time purchase, yours to keep, no subscription</li>
                    <li><Check size={13} /> {DISTRIBUTION.externalStore ? "Bought and delivered through the Lakeside store" : `Purchased and restored through ${DISTRIBUTION.nativeStoreName}`}</li>
                  </>
                ) : (
                  <li><Check size={13} /> Play every election free while paid tiers are being prepared</li>
                )}
              </ul>
              {!PAYWALL_ENABLED ? null : DISTRIBUTION.externalStore ? (
                <a className="ghost small" style={{ marginTop: 8 }} href={BRAND.storeUrl}>
                  <ShoppingBag size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />Browse packs on lakesidegames.net
                </a>
              ) : (
                <p className="muted small" style={{ marginTop: 8 }}>
                  Purchases and restoration will be handled by {DISTRIBUTION.nativeStoreName}.
                </p>
              )}
            </div>
          </div>
        </div>

        {/* Packs strip: text-led, no cover art */}
        <div className="field" style={{ textAlign: "left", margin: "36px 0 0" }}>
          <label>{PAYWALL_ENABLED ? "Scenario packs" : "Scenario packs: free for now, every scenario is playable while we finish the paid tiers"}</label>
          <div className="scenario-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(210px, 1fr))" }}>
            {PACKS.map((p) => {
              const owned = unlocked.packIds.includes(p.id);
              return (
                <div key={p.id} className={`scenario-card pack-card${owned ? " sel" : ""}`}
                  style={{ textAlign: "left", alignItems: "flex-start" }}>
                  <span className="scenario-year" style={{ fontSize: 14 }}>
                    {p.name} {owned && <Check size={13} style={{ verticalAlign: "-2px", color: "var(--green)" }} />}
                  </span>
                  <span className="scenario-match">{p.scenarios.length} scenarios</span>
                  <span className="muted small" style={{ fontSize: 11 }}>{p.description}</span>
                  <span className="pack-foot">
                    <span className="pack-price">
                      {PAYWALL_ENABLED ? (
                        <>${((packPrices[p.id] ?? p.price) / 100).toFixed(2)}</>
                      ) : (
                        <>
                          <s style={{ opacity: 0.5, fontWeight: 500 }}>${((packPrices[p.id] ?? p.price) / 100).toFixed(2)}</s>
                          <span style={{ color: "var(--green)", fontWeight: 800, marginLeft: 6 }}>Free</span>
                        </>
                      )}
                    </span>
                    {owned ? (
                      <span className="muted small">Owned</span>
                    ) : PAYWALL_ENABLED && DISTRIBUTION.externalStore ? (
                      <button className="primary small" disabled={buyBusy === p.id} onClick={() => buy(p.id)}>
                        {buyBusy === p.id ? "Opening…" : "Buy"}
                      </button>
                    ) : PAYWALL_ENABLED ? (
                      <span className="pack-beta-note">Available through {DISTRIBUTION.nativeStoreName}</span>
                    ) : (
                      <span className="pack-beta-note">Free for now</span>
                    )}
                  </span>
                </div>
              );
            })}
          </div>
          {buyError && <p className="muted small" style={{ color: "var(--rose)", marginTop: 8 }}>{buyError}</p>}
        </div>

        <FaqSection />

        {/* Footer: legal + studio credit + contact + attribution */}
        <div className="landing-foot">
          <button className="ghost small" onClick={() => onGo({ kind: "legal", tab: "privacy" })}>Privacy</button>
          <button className="ghost small" onClick={() => onGo({ kind: "legal", tab: "terms" })}>Terms</button>
          <a className="ghost small" href={`mailto:${BRAND.supportEmail}`}>Contact</a>
          <a className="ghost small" href="https://ask.lakesidegames.net/?game=electioneer" target="_blank" rel="noopener noreferrer">Ask about the game</a>
          {DISTRIBUTION.externalStore && <a className="ghost small" href={BRAND.storeUrl}>Store</a>}
          <span className="muted small" title="Coming soon">Discord (coming soon)</span>
          <a className="lakeside-credit" href="https://lakesidegames.net" target="_blank" rel="noopener">
            <img src="lakeside-mark.svg" alt="Lakeside Games logo" width={20} height={20} />
            by Lakeside Games
          </a>
          <span>Country photos: Wikimedia Commons / public domain</span>
        </div>
      </div>
    </div>
  );
}
