# Security

Pod exists to keep family locations private, so security reports are very welcome.

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's private vulnerability reporting instead:
the repository's **Security** tab → **Report a vulnerability**. Include what you found, how to
reproduce it, and what an attacker could do with it. You'll get a reply as soon as possible.

## Scope

In scope: the server (`server/`), the Android app (`android/`), the protocol (`docs/PROTOCOL.md`),
and the deployment files (`deploy/`, `tools/`).

Especially interesting:
- anything that lets the server, the network, or a non-member read or forge family data;
- a removed member keeping access;
- ways to install something other than a genuine update through the in-app updater;
- authentication bypasses, replay, or denial of service against the server.

## Known limitations

These are documented design limits, listed in [docs/DESIGN.md](docs/DESIGN.md#threat-model):
- Server notices (member joined/left, key epoch) are not signed, so a malicious server can delay a key change.
- No forward secrecy within a key epoch.
- One admin per family; a stolen, unlocked admin phone can only be cut off by deleting the family.
- Metadata (connection times, message sizes, IP addresses at the tunnel provider) is visible outside the phones.
