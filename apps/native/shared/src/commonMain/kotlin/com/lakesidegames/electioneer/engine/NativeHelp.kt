package com.lakesidegames.electioneer.engine

class NativeHelpStep(val title: String, val body: String)
class NativeHelp private constructor() {
    companion object {
        fun steps(countryId: String, goal: String): List<NativeHelpStep> = listOf(
            NativeHelpStep("Welcome to the war room", goal),
            NativeHelpStep("The battleground", if (countryId == "US") "Tap a state on the map. Inspect its electoral votes, polling margin and voter blocs." else "Tap a region or choose it from the region list. Compare polling, seats and voter blocs. National vote and seat projections can move differently."),
            NativeHelpStep("Coalitions, not colors", "Each contest contains voter blocs with different issue priorities and turnout. Inspect the persuadable blocs and choose issues that suit your candidate or party."),
            NativeHelpStep("Plan your week", "Queue moves across the week. Tune ad or broadcast spend, message, issue and target. Actions reserve cash and slots; each day holds up to three moves. Preparation before a rally or debate can strengthen its effect."),
            NativeHelpStep("Lock it in", "End the week to resolve your plan and the opposing campaigns. Read the recap, then revisit the map and your cash before planning again."),
            NativeHelpStep("Curveballs", "Events and debates pause the campaign for a response. Check the available choices and their trade-offs. Preparation and candidate traits affect debate performance."),
            NativeHelpStep("Your campaign menu", "Analysis shows polling, traits, issues, resources and trends. Saves preserve a campaign. Replay records the weeks; daily replay becomes available after finishing. Settings lets you replay this tutorial."),
            NativeHelpStep("Election night", "Watch the final calls, then inspect results and the campaign report. Standard campaigns with a known difficulty can post scores. Share your result or continue to the next election where available.")
        )
        fun guide(): List<NativeHelpStep> = listOf(
            NativeHelpStep("United States", "Reach 270 electoral votes. Most states award their votes to the winner; Maine and Nebraska split statewide and district votes. The default campaign lasts nine weeks. Cash, candidate stamina, momentum, readiness and staff capacity constrain the plan."),
            NativeHelpStep("Parliamentary elections", "UK, Canadian and Australian regional results produce seats. The majority line depends on the election and eligible seats. A hung parliament can yield a coalition, confidence and supply, or minority government. Check the government explanation in results."),
            NativeHelpStep("Germany", "National proportional allocation determines seats. Regional plurality map colors do not award every seat to that party. The 5% threshold can exclude parties; CSU is exempt. Compatible coalitions can form a government."),
            NativeHelpStep("France", "Win a majority of presidency points. Six planning weeks compress the two-week runoff. First-round transfers are included in opening support. National broadcasts and platform preparation reach the whole electorate."),
            NativeHelpStep("Advertising and broadcasts", "Positive messages persuade aligned blocs. Contrast messages challenge rivals with backlash risk. Issue messages raise the salience of an issue. Spend, regional cost and diminishing returns affect the result."),
            NativeHelpStep("Visits, surrogates and fundraising", "Visits and rallies move local appeal and momentum, but travel and low stamina can produce gaffes. Surrogates cover more ground. Fundraising replenishes cash and depends on traits and momentum."),
            NativeHelpStep("Ground game and turnout", "Build field infrastructure early. Turnout pushes are strongest where offices already exist. Repeated GOTV in one region has diminishing returns. Prioritize tight contests and low-turnout blocs."),
            NativeHelpStep("Research, preparation and issue pivots", "Research can expose an opponent or backfire. Debate and policy preparation improve readiness, then decay. Issue pivots win some blocs and lose others; abrupt reversals can cost trust."),
            NativeHelpStep("Polling and scoring", "Pollsters have bias and sampling error; compare them with projections and trends. Scores combine election margin, popular vote and difficulty. Short, long, edited and legacy campaigns can be ineligible for scores. The score controls explain the current campaign's eligibility."),
            NativeHelpStep("Daily challenge", "The challenge changes at midnight UTC. Date, election, role and seed are shared with the web. Finish before posting. Your local best and streak remain on this device; signed-in boards show rank, historical dates and daily champions.")
        )
    }
}
