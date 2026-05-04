# Changelog

| Date | File | Change |
|------|------|--------|
| 2026-05-04 | CLAUDE.md | Created initial CLAUDE.md with architecture, commands, and project guidance |
| 2026-05-04 | CLAUDE.md | Added Rules section: working directory constraint and mandatory CLAUDE_changelog.md logging |
| 2026-05-04 | CLAUDE_changelog.md | Created changelog file |
| 2026-05-04 | CLAUDE.md | Expanded Rules section: changelog now covers all state-mutating actions (file ops, shell commands, git) as a full audit log |
| 2026-05-04 | shell | `npm install -g yarn` — installed yarn globally (was missing; required before deps could install) |
| 2026-05-04 | shell | `npm i` — installed npm/yarn dependencies (node_modules created) |
| 2026-05-04 | shell | `concurrently … shadow-cljs … nbb … vite` — attempted dev server start; blocked: `clojure` CLI not on PATH, Java not installed |
| 2026-05-04 | shell | `sudo apt-get install -y default-jdk` + Clojure CLI install — installed Java 21 and Clojure CLI 1.12.4 |
| 2026-05-04 | shell | dev server started (shadow-cljs + nbb tangler + vite); build completed 381 files; serving at http://localhost:8001 |
| 2026-05-04 | shell | `npm install --save-dev @vitejs/plugin-basic-ssl` — added self-signed SSL plugin so OPFS works over LAN |
| 2026-05-04 | vite.config.js | Added basicSsl() plugin to enable HTTPS on dev server, required for navigator.storage (OPFS) on non-localhost origins |
| 2026-05-04 | vite.config.js | Added /api proxy → localhost:9630 (ws:true) so shadow-cljs hot-reload WebSocket tunnels through Vite HTTPS |
| 2026-05-04 | shadow-cljs.edn | Uncommented :devtools-url "." so devtools WS uses current page origin (routes through Vite proxy over WSS) |
