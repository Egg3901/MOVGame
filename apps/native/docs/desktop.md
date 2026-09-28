# Desktop builds

The Tauri 2 desktop shell lives here. It wraps the web edition from this same checkout, so the simulation and interface are never forked.

## Status

A verified release build of this shell produced `.deb`, `.rpm` and `.AppImage`
packages under `src-tauri/target/release/bundle`. That build was produced while
the shell still lived in the `MOVGame` repo (then `ahd-sim`), so it is history,
not a current claim: **re-verify from this repo** before the desktop channel is
treated as working.

The channel is selected by Vite mode. The normal desktop build uses
`desktop-direct`, which permits the existing Lakeside checkout; the Steam build
uses `steam`, which removes every external Lakeside store link and checkout
action. The base Steam client is free, and Steam DLC ownership verification is
not implemented, so paid DLC must not launch until that adapter is complete.

The checked-in master icon is `public/brand/margin-of-victory-icon.png` in
`MOVGame`; the Tauri-generated sizes for every platform live in
`src-tauri/icons/` here.

## Build

```text
npm ci --prefix ../..
npm ci
npm run fetch:web          # check the local web source path
npm run tauri:dev          # dev shell against the root web source

npm run desktop:build      # .deb / .rpm / .AppImage (mode desktop-direct)
npm run steam:build        # Steam channel (mode steam)
```

Platform prerequisites are the standard Tauri 2 ones. On Linux (Debian/Ubuntu):
`libwebkit2gtk-4.1-dev`, `build-essential`, `curl`, `wget`, `file`,
`libxdo-dev`, `libssl-dev`, `libayatana-appindicator3-dev`, `librsvg2-dev`.
Windows needs the MSVC build tools plus WebView2; macOS needs the Xcode command
line tools. `cargo-xwin` is installed on the ops host, so the Windows bundle may
be cross-compilable from Linux — verify before relying on it.

## Remaining release work

- Re-verify the Linux bundle from this repo and smoke-test it.
- Build and smoke-test Windows packages (or confirm the `cargo-xwin` cross-build).
- Build, sign and notarize macOS packages on macOS.
- Acquire signing identities before public direct downloads.
- Implement and test Steam DLC ownership before selling packs on Steam.
- Verify install, upgrade, uninstall, save retention and offline startup on every
  supported desktop OS.

Unsigned local packages are suitable for development, not public distribution.
