# Client architecture

MOVGame is the source of truth for web gameplay and content. `shared/` ports its deterministic TypeScript simulation to Kotlin for native mobile clients. Cross-checked tests keep the two engines aligned; `scripts/export-content-bundles.ts` exports content from the web source in this checkout to the KMP bundle resources.

Android renders gameplay with Jetpack Compose in `androidApp/`. iOS renders it with SwiftUI in `iosApp/`. Their platform billing adapters use Play Billing and StoreKit. The mobile apps contain no React, WebView game UI, or Tauri runtime.

Desktop is separate: `src-tauri/` packages the pinned MOVGame web bundle. `desktop-direct` allows Lakeside checkout; Steam uses DLC only after Steamworks products are configured. Mobile app store products remain disabled until their SKU tables, keys, and store testing are complete.

The stable identifier `com.lakesidegames.electioneer` and the web path `/games/electioneer/` must not change during the repo split.
