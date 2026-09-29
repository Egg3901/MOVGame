import { useState } from "react";
import { useCountryStore } from "@store/countryStore";
import { COUNTRIES } from "@content/countries";
import { CountrySetup } from "./CountrySetup";
import { CountryMap, CountrySeatBar, CountryStandings, CountryRegionPanel } from "./CountryPanels";
import { CountryActionPanel } from "./CountryActionPanel";
import { CountryResults } from "./CountryResults";
import { partyColor, partyName } from "./helpers";
import { WeekRecapModal } from "@ui/WeekRecapModal";
import { MultipartyEventModal } from "@ui/MultipartyEventModal";
import { OnboardingCoach } from "@ui/coach/OnboardingCoach";
import { Vote } from "lucide-react";
import { BRAND } from "../../brand";
import { movAskUrlFromSnapshot, movSeatAskSnapshot } from "@lib/ask";

function countryEventView(countryId: string, electionId: string, eventId: string) {
  const country = COUNTRIES[countryId];
  if (!country) return null;
  const deck = country.elections[electionId]?.events ?? [];
  const ev = deck.find((e) => e.id === eventId) ?? country.events.find((e) => e.id === eventId);
  if (!ev?.choices) return null;
  return {
    title: ev.headline.replace("{party}", "your party"),
    prompt: ev.prompt ?? "How do you respond?",
    choices: ev.choices,
  };
}

// The generic country shell — the UkApp pattern, parameterized by bundle.
// Takes the country ID (not the bundle) so the bundles + map data stay inside
// this lazy chunk instead of the main bundle.
export function CountryApp({ countryId, onExit, initialElection, initialSeed, initialParty }: { countryId: string; onExit: () => void; initialElection?: string; initialSeed?: string; initialParty?: string }) {
  const country = COUNTRIES[countryId];
  const storeCountry = useCountryStore((s) => s.country);
  const game = useCountryStore((s) => s.game);
  const endTurn = useCountryStore((s) => s.endTurn);
  const undo = useCountryStore((s) => s.undo);
  const canUndo = useCountryStore((s) => s.history.length > 0);
  const live = useCountryStore((s) => s.liveProjection)();
  const resolvePlayerEvent = useCountryStore((s) => s.resolvePlayerEvent);
  const selectedRegionId = useCountryStore((s) => s.selectedRegionId);
  const [recapOpen, setRecapOpen] = useState(false);

  // A game from a different country in the store doesn't belong to this shell.
  if (!country) {
    return (
      <div className="app screen center">
        <div className="setup">
          <p className="sub">Unknown country.</p>
          <button className="primary" onClick={onExit}>Back to all scenarios</button>
        </div>
      </div>
    );
  }
  const activeGame = storeCountry?.id === country.id ? game : null;

  if (!activeGame) return <div className="app screen"><CountrySetup country={country} onBack={onExit} initialElection={initialElection} initialSeed={initialSeed} initialParty={initialParty} /></div>;
  if (activeGame.phase === "result") return <div className="app screen"><CountryResults onExit={onExit} /></div>;

  const weeksLeft = activeGame.totalTurns - activeGame.turn;
  const res = activeGame.resources[activeGame.playerParty];
  const used = activeGame.queuedActions.length;
  const playerSeats = live ? live.seats[activeGame.playerParty] ?? 0 : 0;
  const hasPending = !!activeGame.pendingEvent;
  const pendingView = hasPending && activeGame.pendingEvent
    ? countryEventView(country.id, activeGame.electionId, activeGame.pendingEvent.eventId)
    : null;
  const maj = activeGame.majority ?? country.system.majority;

  const handleEndTurn = () => {
    if (hasPending) return;
    if (activeGame.queuedActions.length === 0) {
      const ok = window.confirm("You have no actions queued this week. Unspent slots win nothing. End the week anyway?");
      if (!ok) return;
    }
    endTurn();
    const g = useCountryStore.getState().game;
    if (g && g.phase !== "result" && (g.lastRecap?.length ?? 0) > 0 && !g.pendingEvent) setRecapOpen(true);
  };

  return (
    <div className="app screen">
      <div className="topbar">
        <div className="brand-lockup">
          <span className="mark"><Vote size={22} /></span>
          <div>
            <div className="brand-name">{BRAND.nameCaps}</div>
            <div className="brand-yr">{country.flag} {country.label.toUpperCase()} · {activeGame.label}</div>
          </div>
        </div>
        <div className="turnchip">
          {weeksLeft} {weeksLeft === 1 ? "week" : "weeks"} to election day · leading{" "}
          <strong style={{ color: partyColor(country, activeGame.playerParty) }}>{partyName(country, activeGame.playerParty)}</strong>
        </div>
        {live && <CountrySeatBar country={country} result={live} />}
        <div className="stat"><span className="v" style={{ color: partyColor(country, activeGame.playerParty) }}>{playerSeats}</span><span className="l">Your {country.unitNamePlural}</span></div>
        <div className="stat"><span className="v">{country.currency}{res.funds.toFixed(0)}M</span><span className="l">Funds</span></div>
        <div className="stat"><span className="v" style={{ color: used >= res.maxActions ? "var(--gold)" : undefined }}>{res.maxActions - used}/{res.maxActions}</span><span className="l">Actions</span></div>
        <button className="ghost small" onClick={onExit}>Exit</button>
        {live && <a className="ghost small" href={movAskUrlFromSnapshot(movSeatAskSnapshot(activeGame, live, country.label))} target="_blank" rel="noopener noreferrer">Ask about campaign</a>}
        <button onClick={undo} disabled={!canUndo}>↶ Undo</button>
        <button className="primary" data-coach="endweek" onClick={handleEndTurn} disabled={hasPending}>
          {hasPending ? "Resolve event first" : "End Week →"}
        </button>
      </div>

      {activeGame.news.length > 0 && (
        <div className="ticker" role="status" aria-live="polite">
          <span className="ticker-tag"><span className="ticker-dot" /> WIRE</span>
          <span className="ticker-line">{activeGame.news[0].text}</span>
        </div>
      )}

      <div className="main">
        <div className="col">
          <CountryMap />
          <CountryStandings />
        </div>
        <div className="col">
          <CountryRegionPanel />
          <CountryActionPanel />
        </div>
      </div>

      {recapOpen && !hasPending && (
        <WeekRecapModal
          title={`Week ${Math.max(1, activeGame.turn)} resolved`}
          items={activeGame.lastRecap ?? []}
          unitLabel={` ${country.unitNamePlural}`}
          onClose={() => setRecapOpen(false)}
        />
      )}
      {!recapOpen && pendingView && (
        <MultipartyEventModal event={pendingView} onResolve={resolvePlayerEvent} />
      )}

      <OnboardingCoach
        doneKey={`coach-done-${country.id.toLowerCase()}-v1`}
        winLine={`Win ${maj.threshold} ${country.unitNamePlural} in {N} weeks. No pressure.`}
        mapHint="Click a region on the map to continue."
        mapBody="Your battleground. Colours show the current lean; click a competitive region."
        planBody="Queue your week: broadcasts, rallies, ground game. Actions cost slots. Spend all of them; unspent slots win nothing."
        selectedId={selectedRegionId}
        queuedLen={activeGame.queuedActions.length}
        turn={activeGame.turn}
        totalTurns={activeGame.totalTurns}
        mapSelector=".mapwrap"
        detailSelector={(mobile) =>
          mobile
            ? document.querySelector(".sheet .card") ?? document.querySelector(".sheet")
            : document.querySelectorAll(".main .col")[1]?.querySelector(".card") ?? null
        }
      />
    </div>
  );
}
