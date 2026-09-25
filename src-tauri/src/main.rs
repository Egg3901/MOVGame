// Entry point for the Margin of Victory desktop shell.
// Tauri v2 loads the built Vite output or dev server in a native webview.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    electioneer_lib::run();
}
