# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rules

- Working directory is `/home/kuro/Documents/Paradise`. Do not create or edit files outside this directory unless explicitly asked.
- Treat `CLAUDE_changelog.md` as an audit log. Append an entry for **every** action that changes state: file creates, edits, deletes; shell commands that install packages, modify config, run builds, or affect the system in any way; git operations. Each entry must include the date (YYYY-MM-DD), the target (file path or command), and a short description of what changed and why. No action that mutates state should go unlogged.

## What is Paradise?

Paradise is a **literate-programming** ClojureScript Matrix client that uses `matrix-rust-sdk` via WASM (through the `ffi-bindings` npm package). The authoritative source is the `docs/` org-mode files — the `src/` ClojureScript files are **generated** from them by the tangler. Always edit `docs/` `.org` files; never edit `src/` CLJS files directly unless you have a specific reason.

The pipeline is:
```
docs/**/*.org  →  (tangler)  →  src/**/*.cljs  →  (shadow-cljs)  →  build/  →  (vite)  →  browser
```

## Development Commands

Install dependencies first:
```sh
npm i
```

**Dev with auto-tangling** (org → CLJS → browser):
```sh
npm run dev
# Runs: shadow-cljs watch app + nbb tangler + vite dev server
```

**Dev without tangling** (edit CLJS directly):
```sh
npm run notangle
# Runs: shadow-cljs watch app + vite dev server
```

**Production build:**
```sh
npm run release
# Runs: shadow-cljs release app + vite build
```

Vite serves on port 8000 (`http://localhost:8000`).

## Literate Programming Workflow

- Source of truth: `docs/**/*.org` files
- CLJS output: `src/**/*.cljs` files (co-located with `docs/` mirroring the same directory layout)
- Tangling is done by `src/utils/tangler.cljs` (run via `nbb`). It watches `docs/` and re-emits any `#+begin_src clojurescript :tangle <path>` or `#+begin_src css :tangle <path>` blocks whenever they change.
- Use `:tangle no` on a src block to prevent it from being written to disk.
- The tangler runs every 1 second as a `setInterval` loop when started with `npm run dev`.

## Architecture

### Process Split: Main Thread vs. Web Worker

The app runs in two JS contexts:

| Module | Entry | Context | Role |
|--------|-------|---------|------|
| `client.core` | `build/main.js` | Main thread | Re-frame app, React/Reagent UI |
| `worker.core` | `build/engine.js` | Web Worker | matrix-rust-sdk WASM, all Matrix I/O |

Communication flows via `cljs-workers` (a custom fork): the main thread calls `main/do-with-pool!` to send requests to the worker, and the worker streams back diffs via `worker/stream-to-main!`. The main thread routes incoming stream messages in `client.core/handle-worker-stream!` using `:type` dispatch.

### Source Directory Layout

```
src/
  app.cljs                 # Root re-frame DB, app bootstrap, main-layout component
  client/                  # Main-thread state and SDK coordination
    core.cljs              # Worker pool init, :app/bootstrap, :sdk/start-sync
    state.cljs             # Global atoms: !engine-pool, !media-pool, !config, !slots
    session-store.cljs     # OPFS-based encrypted session persistence
    config.cljs            # Loads config.edn and i18n.edn at runtime
    key-handler.cljs       # Global keyboard shortcut dispatch
    diff-handler.cljs      # Shared diff-apply utilities for room/space list diffs
  worker/                  # Worker-side Matrix SDK interaction
    core.cljs              # Registers :init-wasm, :bootstrap, :login, :start-sync handlers
    state.cljs             # Worker atoms: !client, !media-cache, !plugin-handlers
    timeline.cljs          # Timeline event handling and pagination
    rooms.cljs             # Room list sync via RoomListService
    spaces.cljs            # Space hierarchy traversal
    members.cljs           # Room member list management
    composer.cljs          # Send message, edit, react, redact
    settings.cljs          # Encryption listeners and user settings
    previews.cljs          # Media preview config
    call.cljs              # Element Call / widget bridge
  container/               # Center-panel UI (timeline, composer, calls, pins, search)
  navigation/              # Sidebar UI (spaces bar, room list)
  overlays/                # Floating UI (settings, quick-switcher, lightbox, profiles, reactions, notifications)
  input/                   # Composer, autocomplete, emoji, drafts
  auth/                    # Login screen and auth event handlers
  utils/                   # Helpers, macros, SVG icons, net, images, global-ui
  plugins.cljs             # Plugin slot/wrapper system
  sci_runner.cljs          # SCI (in-browser Clojure evaluator) for plugin scripts
  service_worker.cljs      # PWA service worker entry point
```

### State Management

Re-frame is the single source of truth for UI state (`app/default-db` in `src/app.cljs`). Worker state (the Matrix client object) lives in `worker.state/!client`. Plugin overrides and component slots live in `client.state/!slots` and `client.state/!active-overrides`.

### Plugin System

Plugins are evaluated using SCI (`org.babashka/sci`) in the main thread (`sci-runner`) and in the worker (`worker-sci-runner`). Plugins can:
- Inject components into named slots via `defslot` / `(plugin-slot slot-id …)`
- Wrap existing components via `defoverride`
- Register worker-side message handlers

### Build Hooks (`src/build_hooks.clj`)

Run at shadow-cljs compile time:
- `include-root-files` — copies `index.html`, `config.edn`, `i18n.edn` into `build/`
- `include-themes` / `include-css` — copies `themes/` and `css/` into `build/`
- `copy-wasm` — copies the `ffi-bindings` WASM binary into `build/`
- `copy-element-call` — copies Element Call embedded dist into `build/element-call/`
- `stamp-version` — writes the `package.json` version into `config.edn`

### Mobile / Desktop

Capacitor wraps the web app for iOS and Android. Electron support is in a separate `electron/` directory. Sync with native platforms:
```sh
npm run ios:sync    # or ad:sync for Android
```

## Environment

Copy `sample.env` to `.env` for iOS builds (requires `APPLE_TEAM_ID`). Vite env vars for push notifications and homeserver are prefixed `VITE_` (see `vite.config.js` `define` block).

## Key Dependencies

- `ffi-bindings` — npm package providing matrix-rust-sdk WASM bindings
- `cljs-workers` — fork at `github.com/Paradise-in-Matrix/cljs-workers` providing the worker pool API
- `re-frame` + `reagent` — UI state and React rendering
- `taoensso/timbre` — logging
- `taoensso/tempura` — i18n
- `org.babashka/sci` — in-browser Clojure evaluation for plugins
- `@element-hq/element-call-embedded` — Element Call widget for voice/video
