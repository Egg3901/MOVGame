> Historical planning document. Current source and builds live in MOVGame under `apps/native`; follow README.md and mobile.md.

# Margin of Victory native port

Mobile is Kotlin Multiplatform with native user interfaces: Jetpack Compose on Android and SwiftUI on iOS. Desktop alone uses Tauri. The earlier Tauri mobile plan was superseded by `docs/native-plan.md`.

The KMP branch contains the engine port, exported content, both mobile interfaces, and store billing adapters. `README.md` describes the build commands. `docs/release-checklist.md` tracks the remaining compilation, signing, store setup, purchase, refund, privacy, and device gates.

The local web checkout is still `/root/projects/ahd-sim`, although its GitHub repository is MOVGame. Moving that live checkout requires a separate coordinated service and path migration under `/root/AGENTS.md`.
