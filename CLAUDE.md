# Pod Family Location: notes for Claude Code

Self-hosted, end-to-end encrypted family location sharing app. Deployment-specific notes (your server, domain, hosts) go in `CLAUDE.local.md`, which is git-ignored.

## Priorities (in order)
1. Privacy: the server must never be able to read family data. Never add server-side handling of plaintext locations, names or places.
2. Stability and ease of use: family members are non-technical, and a flaky app kills adoption.
3. Simple self-hosting: one `docker compose up`.

## Layout
- `server/`: Go, stdlib + `mattn/go-sqlite3` (cgo). `go test -race ./...` must pass. No new dependencies without a good reason.
- `deploy/`: docker-compose (pod-server + cloudflared), `.env.example`, `setup-map.sh`, `map/style.template.json` (generated from protomaps/basemaps, dark flavor; `__POD_PUBLIC_URL__` is replaced at serve time).
- `android/`: Kotlin + Compose. **Dark-only UI**: never add a light theme or dynamic color. Crypto lives in `crypto/PodCrypto.kt`.
- `docs/`: `DESIGN.md` (architecture, threat model), `PROTOCOL.md` (wire formats: the contract), `SELF-HOSTING.md`.

## Rules
- `docs/PROTOCOL.md`, `server/auth.go` and `android/.../crypto/PodCrypto.kt` must stay in sync. Change all three together, and bump the protocol version if the change isn't backward compatible.
- The server logs method/route/status/duration only. Never IPs, headers, query strings or bodies.
- Secrets (`deploy/.env`, `android/keystore.properties`, `*.jks`) are gitignored and never committed.
- **Updates must never require re-setup.** Never rename or remove fields in `@Serializable` classes that are stored (`AppState`, `Secrets`, payloads); only add fields with defaults. Release builds are always signed with the same release key, and `versionCode` only goes up.
- Releases are published with `tools/publish-release.ps1` (signed APK + `latest.json` to `deploy/app/` on the server; settings in the git-ignored `tools/publish.config.ps1`); phones update in-app and verify the APK's package, version and signature first.
- The app ID and launcher name come from `android/keystore.properties` (git-ignored). Never change them for an existing install base: phones would treat the update as a different app.

## UI rules (the app is edge-to-edge)
- Every screen and overlay must keep its controls out of the status bar and the phone's navigation bar: use `systemBarsPadding()` / `navigationBarsPadding()` (plus `imePadding()` where there's a text field) on the container holding the buttons. Backgrounds may extend under the bars; buttons and text may not.
- Never build full-screen UI with `Dialog(...)` / `DialogProperties(decorFitsSystemWindows = false)`: dialog windows get no system-bar insets, so the padding is zero and bottom buttons land under the navigation bar. Draw full-screen editors as a layer in the main window (see `ui/places/PlaceMapEditor.kt`) with a `BackHandler`. Small centered `AlertDialog`s are fine.
- Before finishing any UI change, check the bottom-most button of every screen you touched sits above the navigation bar (3-button nav is the worst case).
