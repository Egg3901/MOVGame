import {
  PLAN_BONUSES,
  activePlanBonusIds,
  type PlanActionLike,
} from "@engine/planBonuses";

export function PlanBonusStrip({ plan }: { plan: readonly PlanActionLike[] }) {
  const active = activePlanBonusIds(plan);

  return (
    <div className="plan-bonus-box">
      <div className="plan-bonus-head">
        <strong>Plan bonuses</strong>
        <span>Set up on an earlier day, follow through later</span>
      </div>
      <div className="plan-bonus-list">
        {PLAN_BONUSES.map((bonus) => {
          const unlocked = active.has(bonus.id);
          return (
            <span
              key={bonus.id}
              className={`plan-bonus${unlocked ? " active" : ""}`}
              title={`${bonus.recipe}. ${bonus.description} Bonus applies to the follow-through action only.`}
            >
              <span aria-hidden="true">{unlocked ? "✓" : "○"}</span>
              {bonus.name}
              <strong>+{Math.round((bonus.multiplier - 1) * 100)}%</strong>
            </span>
          );
        })}
      </div>
    </div>
  );
}
