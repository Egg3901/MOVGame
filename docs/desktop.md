# Desktop builds — moved

The Tauri 2 desktop shell now lives in the
[`MOVGame-native`](https://github.com/Egg3901/MOVGame-native) repository,
together with the iOS and Android projects and the store billing adapters. This
repository publishes the web edition only.

Build instructions, the verified package status, and the remaining release work
are in `MOVGame-native/docs/desktop.md`. The native build fetches this repo at
the commit recorded in `MOVGame-native/web.pin`, so a desktop build always
compiles a known web revision.
