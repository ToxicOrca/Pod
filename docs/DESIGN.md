# Pod Family Location: Design

Pod is a self-hosted, end-to-end encrypted family location sharing app. Three goals, in this order:

1. **Nobody but your family can see your location.** That includes the server, whoever hosts it, Cloudflare, and anyone who steals the database.
2. **It just works.** Family members who aren't technical install it once and never think about it again. If the app is flaky, nobody will use it.
3. **Easy to run yourself.** One `docker compose up` on a home server.

---

## Architecture

```
 ┌──────────────┐                                   ┌──────────────┐
 │  Phone (Mom) │                                   │  Phone (Kid) │
 │  GPS → encrypt│                                  │ decrypt → map│
 │  geofences   │                                   │  alerts      │
 └──────┬───────┘                                   └──────▲───────┘
        │ HTTPS (signed requests, encrypted blobs)         │ long-poll
        ▼                                                  │
 ┌─────────────────── Cloudflare Tunnel ───────────────────┴──┐
 │   sees: ciphertext, request timing, phone IP addresses     │
 └──────────────────────────┬─────────────────────────────────┘
                            ▼
 ┌────────────────────────────────────────────────────────────┐
 │  pod-server (Go, SQLite)          "blind relay"             │
 │  stores: public keys, encrypted blobs with expiry,         │
 │          wrapped family keys, map tiles (public OSM data)  │
 │  never has: locations, names, places, any private key      │
 └────────────────────────────────────────────────────────────┘
```

### Components

| Component | Tech | Why |
|---|---|---|
| Server | Go stdlib + SQLite (`mattn/go-sqlite3`), one binary in a distroless container | Small attack surface (one dependency), nothing to tune, survives reboots. A family generates a few MB a day at most. |
| Ingress | Cloudflare Tunnel (`cloudflared`) | No open ports at home, free TLS, hides your home IP. Acceptable *because* payloads are end-to-end encrypted. |
| Map | Protomaps PMTiles file (OpenStreetMap data) served by pod-server, rendered by MapLibre | Google and Mapbox never see what you look at. One static file, no tile server to run. |
| Android app | Kotlin, Jetpack Compose, dark-only | Native gives the most reliable background location. |
| Crypto | Google Tink primitives (Ed25519, X25519, XChaCha20-Poly1305, HKDF) | Audited library, no native code, pure Java = no device-specific crashes. |

---

## How the end-to-end encryption works

Full wire formats are in [PROTOCOL.md](PROTOCOL.md). The short version:

- **Device identity.** On first launch each phone makes two key pairs: Ed25519 for signing, X25519 for receiving keys. The private halves never leave the phone (encrypted at rest with an Android Keystore key, excluded from cloud backup). The device ID is a hash of the signing key, so the server can't swap the signing key without the ID changing. The X25519 key isn't covered by the ID, so the admin's phone pins each member's X25519 key when it verifies their invite proof, and only ever wraps family keys to those pinned keys, never to a key the server reports later.
- **No accounts.** No email, no password, nothing to phish or leak. Every API request is signed by the device key. The server checks it, along with a timestamp, a nonce, and a replay cache.
- **The family key.** The admin's phone generates a random 32-byte family key. It "wraps" the key separately for each member's X25519 key and signs each wrap. Members only accept a wrapped key signed by the admin whose identity they pinned when they scanned the QR code.
- **Messages.** Locations, places, profiles and events are JSON. Each is padded to a fixed size bucket, encrypted with XChaCha20-Poly1305 under the family key, and signed by the sender. The signature stops one family member from forging another's location. The padding stops the server from telling message types apart by size.
- **Removing someone.** The admin removes the device on the server and rotates to a new family key, wrapped only to the remaining verified members. The same happens when a member leaves on their own. The rotation is saved on the admin's phone and retried until the server accepts it, and the new key is only used for sending once it has been delivered. Phones refuse messages under an old key once the sender has moved to a newer one, or more than 24 hours after the newer key arrived, so a removed phone (which only has old keys) can't keep posting.

### Joining without trusting the server

Invites are QR codes shown **in person**. The QR contains:

| Field | Sent to server? | Purpose |
|---|---|---|
| server URL | – | where to connect |
| family ID | yes | which family |
| invite token | once, at join | server checks it against a stored hash. Single use, expires in 30 min. |
| invite secret | **never** | the joining phone sends `HMAC(secret, its keys)`. Only the admin's phone can check this. |
| admin device ID | – | pins the admin's identity key |

The admin's phone hands over the family key automatically, but only to a device whose HMAC proof checks out. A malicious server could try to insert its own device, but it can't produce the proof without the secret. Even if it registered a device, that device would never receive the family key. When a new phone is verified, the admin rotates to a new family key and the newcomer only receives that one, so it can't read history from before it joined. The admin's own identity in the QR code is checked against the admin phone's key when the family is created, so the server can't substitute its own.

---

## Threat model

| Adversary | Can see | Cannot see |
|---|---|---|
| Server operator / stolen database / backup leak | Number of devices per family, when each device last connected, message counts and sizes (padded), retention settings | Locations, names, places, events, photos. Can't forge messages or add a working member. |
| Cloudflare | All of the above, plus phone IP addresses, request timing, and **which map tiles are requested** (see gaps) | Same as above |
| Network attacker (coffee-shop Wi-Fi) | That you talk to your Pod hostname | Anything else (TLS + E2EE) |
| Removed family member | Whatever they saw while they were in the family | Anything after removal |
| Malicious family member | The family's locations (that's the point of the app) | Can't forge someone else's location or events (per-sender signatures) |
| Someone with your recovery kit file **and** its passphrase | Everything that phone could see; they can act as it (as admin, if it is the admin's kit) | Nothing without the passphrase (PBKDF2-SHA256 at 600k rounds + XChaCha20-Poly1305) |
| Thief with an unlocked phone | That phone's view of the family | – (remove the phone from Settings on the admin's phone). If it's the admin's phone, the only remedy today is deleting the family (see gap 3). |

### Known gaps (on the roadmap)

1. **Map tile requests leak viewing area to Cloudflare.** PMTiles requests are byte ranges of a public file. Someone with the same file could work out roughly which area you zoomed into. Fix: let phones download the region file once and render offline (MapLibre supports `pmtiles://file://`). Or put the tiles behind Tailscale only.
2. **Metadata.** The server sees when phones check in. Uploads go on movement plus a 5-minute heartbeat, so timing shows roughly when someone is moving. A fixed upload schedule would hide this at a battery cost.
3. **Single admin.** Mitigated by the recovery kit (Settings → Recovery kit): restoring it on a new phone brings back the same admin identity, and newer family keys are fetched from the server automatically. Still planned: an optional second admin, for when the admin and their kit are both unavailable.
4. **Address search uses public OpenStreetMap search services** (Photon at photon.komoot.io, with Nominatim as a fallback), and house-number searches also go to the US Census Bureau geocoder (geocoding.geo.census.gov), which has far better US house coverage. The Census lookup receives only the typed text. When you search while adding a place, the typed text plus a rough area (rounded to ~11 km) goes to those services from your phone. No live locations are ever sent. It can be turned off in Settings. A self-hosted Photon geocoder would remove this.
5. **Server notices aren't signed.** The current key epoch and join/remove notices come from the server. A malicious server could withhold a new key so members keep using the previous one; it can't make anyone use a key it knows. Planned: an admin-signed epoch statement.
6. **No forward secrecy within an epoch.** If the family key leaks, messages from that epoch can be decrypted until the next rotation. Planned: automatic periodic rotation, then MLS (RFC 9420) for groups.

---

## Reliability design ("it just works")

The things that make background location apps flaky on Android, and what Pod does about each:

| Failure | Mitigation |
|---|---|
| Android kills the app in the background | Foreground service with a visible notification (`START_STICKY`), restarted by a WorkManager watchdog every 15 min |
| Phone reboots or app updates | `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` receiver restarts sharing without opening the app |
| Doze / battery optimizations | Guided setup asks for "Unrestricted" battery. Links to dontkillmyapp.com for Samsung/Xiaomi/etc. |
| User forgets "Allow all the time" | Step-by-step permission screen that won't continue without it. Settings has an "Is everything working?" checklist. |
| Flaky network, server reboot | Sync loop never gives up: exponential backoff to 60 s, then a full resync. Uploads are retried. Only the newest location matters. |
| Battery drain | Adaptive location: low-power (Wi-Fi/cell) every 2 min when still, GPS every 30 s only while moving. Uploads only after 25 m of movement, a battery change, or a 4–5 min heartbeat. |
| No Google Play Services (de-Googled phones) | Falls back to Android's LocationManager |
| Corrupted local data after a crash | All local files written atomically (temp file + fsync + rename) |
| "It stopped working" with no clue why | Local diagnostics log (no locations, names or keys), viewable and copyable in Settings. No third-party crash reporters. |
| GPS jitter causes repeated arrive/leave alerts | Geofence hysteresis: enter at radius, exit at radius + max(75 m, accuracy) |
| Server can't be reached for the map | Map falls back to a plain dark background. Family dots and the member list still work. |
| Phone clock wrong | Server error message tells the user to fix date/time (signed requests allow ±5 min) |

Alerts arrive through a long-poll (`GET /v1/messages?wait=85`) held open by the foreground service. No Google Firebase, no separate push app to install. Each family phone holds one quiet HTTPS connection that the server answers as soon as something happens.

---

## Data retention

| Data | Kept for |
|---|---|
| Location history | Phone-selected: 1, 7 (default) or 30 days. Server max is `POD_MAX_HISTORY_DAYS`. |
| Events (arrive/leave/SOS) | Same as history |
| Profile, places | Latest version only |
| Server notices (member joined, etc.) | 7 days |
| Unused invites | Deleted 1 day after expiry |
| Removed member's data | Deleted immediately on removal |
| Battery, charging, speed, arrivals | Each member can stop sharing them (Settings → What I share); the fields are then left out of the encrypted payload entirely |
| Whole family | Deleted immediately when the admin taps "Delete family" |

The server logs one line per request: method, route, status, and duration. It never logs IPs, headers, query strings or bodies.

---

## Roadmap

1. **MVP (this repo):** server, Android app, live map, places + alerts, SOS, check-in, pause (always visible to family), invite by QR, remove + key rotation, diagnostics.
2. Location history view on the map, offline map download, a second admin.
3. iOS: accept OwnTracks clients, or build a native app. Web viewer (decrypts in the browser, Tailscale-only).
4. MLS group encryption, automatic key rotation, reproducible builds, F-Droid.
