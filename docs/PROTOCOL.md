# Pod Protocol v1

This is the contract between clients and the server, and between family members. The reference implementations are `server/` (Go) and `android/app/src/main/kotlin/app/podfamily/crypto/PodCrypto.kt`. Any new client (iOS, web) must match this exactly.

Notation: `||` is byte concatenation, `b64` is standard base64 with padding (Go's `[]byte` JSON encoding), `b64url` is URL-safe base64 without padding. Integers are big-endian.

## Primitives

| Use | Algorithm |
|---|---|
| Signatures | Ed25519 (32-byte seed private key) |
| Key agreement | X25519 |
| KDF | HKDF-SHA256 |
| AEAD | XChaCha20-Poly1305, output `nonce(24) || ciphertext || tag(16)` (Tink and libsodium `crypto_aead_xchacha20poly1305_ietf` compatible) |
| MAC | HMAC-SHA256 |

## Identities

```
device_id = b64url( SHA-256(sign_pub)[0:16] )      # 22 chars
```
The server derives the ID from the key and refuses anything that doesn't match. Clients re-check every member key they receive.

## Request authentication

Every authenticated request carries:

```
Pod-Device:    <device_id>        (omitted on POST /v1/families and POST /v1/join; the key is in the body)
Pod-Timestamp: <unix seconds>
Pod-Nonce:     <16 random bytes, b64url>
Pod-Signature: b64( Ed25519(sign_seed, canonical) )

canonical = "POD-AUTH-1\n" METHOD "\n" REQUEST_URI "\n" TIMESTAMP "\n" NONCE "\n" hex(SHA-256(body))
```
`REQUEST_URI` is the path plus `?query` exactly as sent. The server rejects timestamps more than ±300 s away and any nonce+signature it has already seen.

## Invite

The admin generates `token` (32 random bytes) and `secret` (32 random bytes), and registers `SHA-256(token)` (hex) with the server. The QR code is:

```
pod://join?v=1&u=<urlencoded https server url>&f=<family_id>&t=<b64url token>&s=<b64url secret>&a=<admin device_id>
```

The joining device sends:

```
proof = HMAC-SHA256(secret, "pod-join-v1|" family_id "|" || sign_pub || box_pub)
```

The admin's phone approves a pending member only if `proof` verifies against one of its unused invite secrets.

## Family key envelope

Wraps the 32-byte family key for one recipient at one epoch:

```
aad    = "pod-env-v1|" family_id "|" epoch "|" recipient_device_id       (epoch as decimal text)
eph    = fresh X25519 key pair
shared = X25519(eph_priv, recipient_box_pub)
k      = HKDF-SHA256(ikm=shared, salt=eph_pub || recipient_box_pub, info="pod/envelope/v1", len=32)
ct     = XChaCha20-Poly1305(k).encrypt(family_key, aad)
body   = eph_pub(32) || ct
sig    = Ed25519(admin_sign_seed, aad || body)

envelope = 0x01 || sig(64) || body
```
Recipients must verify `sig` with the **pinned** admin key before decrypting.

## Message blob

```
aad  = "pod-msg-v1|" family_id "|" epoch "|" sender_device_id "|" kind
pt   = len(u32) || json || zero padding  → total size is the smallest of 256/512/1024/2048/4096 that fits,
                                           else the next multiple of 4096
ct   = XChaCha20-Poly1305(family_key[epoch]).encrypt(pt, aad)
sig  = Ed25519(sender_sign_seed, aad || ct)

blob = 0x01 || epoch(u32) || sig(64) || ct
```
`epoch` is an unsigned 32-bit integer (1 ≤ epoch < 2³²) and appears in decimal in the AAD.
Binding `kind` and `sender` into the AAD and signature stops a message being replayed as another kind or attributed to another member.

### Payloads (JSON inside the blob)

| kind | JSON | Server retention |
|---|---|---|
| `loc` | `{"lat":..,"lon":..,"acc":m,"t":ms,"bat":%,"chg":bool,"spd":m/s}` | client TTL (≤ server max) |
| `event` | `{"type":"arrive\|leave\|sos\|checkin\|paused\|resumed","t":ms,"place":name?,"placeId":id?,"lat":?,"lon":?}` | client TTL |
| `profile` | `{"name":"..","color":argb}` | latest per sender |
| `places` | `{"places":[{"id","name","lat","lon","radius"}],"t":ms}` | latest per sender; clients keep the one with the highest `t` |

Server notices use kind `sys`. They are **plaintext** JSON from the server: `{"type":"member_joined|member_removed","device_id":..}` or `{"type":"epoch","epoch":N}`.

## HTTP API

All bodies are JSON. Byte fields are `b64`.

| Method & path | Auth | Body → Response |
|---|---|---|
| `GET /healthz` | – | → `{ok, version, protocol}` |
| `POST /v1/families` | self-signed + `Pod-Setup-Token` header | `{sign_pub, box_pub}` → `{family_id, device_id}` |
| `POST /v1/join` | self-signed | `{family_id, token, sign_pub, box_pub, proof}` → `{family_id, device_id}` |
| `GET /v1/family` | device | → `{family_id, epoch, admin, you, members:[{device_id, sign_pub, box_pub, role, join_proof, created_at, last_seen}]}` |
| `DELETE /v1/family` | admin | deletes everything |
| `POST /v1/invites` | admin | `{token_hash, ttl_seconds}` (max 24 h) → `{expires_at}` |
| `DELETE /v1/members/{id}` | admin, or self to leave | → 204 |
| `PUT /v1/envelopes` | admin | `{epoch, envelopes:[{device_id, data}]}` → 204. A higher epoch becomes current. |
| `GET /v1/envelopes` | device | → `{current_epoch, envelopes:[{epoch, data}]}` (this device's only) |
| `POST /v1/messages` | device | `{kind, blob, ttl_seconds}` → `{id}` |
| `GET /v1/messages?after=ID&wait=S` | device | long-poll up to 85 s → `{messages:[{id, sender, kind, blob, created_at}], cursor, more}` |
| `GET /v1/state` | device | latest message per (sender, kind) → `{messages, cursor}` |
| `GET /v1/history?sender=ID&kind=loc&since=unix` | device | → `{messages}` |
| `GET /map/style.json`, `GET /map/*` | – | map style, fonts, sprites, `tiles.pmtiles` (HTTP Range) |
| `GET /app/latest.json`, `GET /app/pod-X.Y.Z.apk` | – | in-app updates: manifest and signed release APK |
| `GET /download` | – | 302 redirect to the APK named in `latest.json` (permanent link for first installs) |

Errors are `{"error": "human readable"}`. `401 device not registered` means the device was removed and should reset.

## Client rules

- When a client receives a new family key, it re-fetches `/v1/state`, because older messages may now be readable.
- When it sees a `sys` `epoch` notice, it fetches envelopes. If its newest key is newer than the one its profile was published under, it re-publishes its profile (and the admin re-publishes places). Everyone sends a fresh location, so newcomers see the family right away.
- **Key pinning (admin).** The admin's phone stores each member's `box_pub` when it verifies their join proof and seals family keys only to those stored keys plus its own. A different `box_pub` reported later by the server is ignored.
- **Rotation (admin).** A new epoch is created when a member is verified (the newcomer gets only the new key), removed, or leaves. The pending rotation (epoch + key) is persisted and re-sent with `PUT /v1/envelopes` until accepted; the new key is used for sending only after that.
- **Epoch acceptance.** A message under epoch E is refused if the sender was already seen using a newer epoch, or if a newer epoch is known and the message's `created_at` is more than 24 h after that newer key arrived.
- **Freshness and replay.** Alerts are judged by the signed `t` inside the payload, never by the server's `created_at`: events alert up to 2 h after `t` (late ones show their time), locations up to 15 min. Each event is handled once per `(sender, type, t)`. `places` and other payloads with `t` more than 10 min in the future are ignored.
- **Notices.** A `member_removed` notice for this phone is acted on only after the server also answers `401 device not registered`.
- A member is shown only once a valid `profile` from them has been decrypted. A device the server lists without one is "setting up" (or, to the admin, "not verified").
