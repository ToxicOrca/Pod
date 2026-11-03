# Self-hosting Pod

You need:
- A Linux box with Docker and Docker Compose (a Proxmox LXC or VM is fine)
- A domain whose DNS is managed by Cloudflare (free plan is fine)
- About 1–2 GB of disk for a state-sized map

## 1. Get the code onto the server

```bash
git clone https://github.com/ToxicOrca/Pod.git
cd Pod/deploy
cp .env.example .env
```

## 2. Create the Cloudflare Tunnel

1. Cloudflare dashboard → **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel** → choose **Cloudflared**, and name it `pod`.
2. On the "Install connector" screen, copy the **token** (the long string after `--token`). Paste it into `.env` as `CLOUDFLARE_TUNNEL_TOKEN`. You don't need to install anything; the compose file runs `cloudflared` for you.
3. **Public Hostname** tab → add:
   - Subdomain: `pod` (or anything), Domain: your domain
   - Service: **HTTP**, URL: `pod-server:8080`
4. Put the full URL in `.env` as `POD_PUBLIC_URL`, e.g. `https://pod.example.com`.

Cloudflare settings to check for that hostname:
- **Don't** put Cloudflare Access (login page) in front of it. The app can't get past a browser login.
- Turn **Bot Fight Mode** off for the zone (Security → Bots). On the free plan it can't be skipped per hostname, and it challenges the app's API calls. If you have custom WAF rules, make sure they don't challenge this hostname.
- Leave caching at defaults. API responses are sent `no-store`. Only `/map/*` is cacheable, and it contains only public map data.

## 3. Set a setup code

```bash
openssl rand -base64 24
```
Put the result in `.env` as `POD_SETUP_TOKEN`. You type it once in the app when creating your family. Anyone with it can create a family on your server, so keep it private.

## 4. Download the map

```bash
BBOX="-124.6,41.9,-116.4,46.3" ./setup-map.sh  # your area: min_lon,min_lat,max_lon,max_lat (this one is Oregon)
# BBOX="-125,24,-66,50" ./setup-map.sh          # whole continental US (several GB)
```
Set `BBOX` to the area your family lives in and travels to (https://boundingbox.klokantech.com, "CSV" format, gives you one). Without it you only get a small example area (central Portland, Oregon). This pulls just your region's OpenStreetMap tiles from the latest Protomaps build, plus fonts and icons, into `deploy/map/`. Run it again every few months to refresh the map.

## 5. Start it

```bash
docker compose up -d --build
docker compose logs -f
curl https://pod.example.com/healthz   # → {"ok":true,...}
```

## 6. Install the app and create your family

1. Build the APK yourself and install it on your phone: see [Build the app yourself](../README.md#2-build-the-app-yourself) in the main README. To make installing easy for family, publish it to the server first ([Publishing updates](../README.md#publishing-updates)) and send them `https://your-host/download`.
2. Open Pod → **Start a new family** → enter your URL, the setup code and your name.
3. Go through the permission steps (choose **Allow all the time** and **Unrestricted** battery).
4. For each family member: install Pod on their phone → **Join my family**. On your phone, tap the invite button and let them scan the QR code. Their phone is approved automatically within a few seconds.

## Running behind your own reverse proxy

You can skip Cloudflare and use Caddy, nginx, Traefik or similar instead. Requirements: a real HTTPS certificate the phones trust (Let's Encrypt is fine; self-signed is not), and request timeouts of at least **120 seconds** (the app holds a request open for up to 85 s waiting for updates).

1. Create `deploy/docker-compose.override.yml` to publish the server on the host and stop trusting Cloudflare's IP header:
   ```yaml
   services:
     pod-server:
       ports: ["127.0.0.1:8080:8080"]   # drop 127.0.0.1 if the proxy runs on another machine
       environment:
         POD_TRUST_CF_IP: "false"
   ```
2. In `.env`, set `CLOUDFLARE_TUNNEL_TOKEN=unused` (the compose file requires a value) and set `POD_PUBLIC_URL` to your proxy's HTTPS URL.
3. Start only the server: `docker compose up -d --build pod-server`.
4. Point the proxy at `http://127.0.0.1:8080`. For example, with Caddy:
   ```
   pod.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```
   With nginx, also set `proxy_read_timeout 120s;`.

Don't set `POD_TRUST_CF_IP=true` unless Cloudflare is really in front: otherwise anyone could fake their IP to dodge rate limits. Without it, rate limits for joining and family setup are per proxy address, which is fine for a family server.

## Updating

```bash
git pull
docker compose up -d --build
```
The database schema migrates itself. Phones reconnect on their own.

## Backups

The database only holds encrypted blobs and public keys. A backup is useful so families don't have to re-join after a disk failure, but it has no readable location data.

```bash
docker compose stop pod-server
docker compose cp pod-server:/data/pod.db ./pod-backup-$(date +%F).db
docker compose start pod-server
```

## Troubleshooting

| Symptom | Check |
|---|---|
| App says "Can't reach the server" | `curl https://your-host/healthz` from outside your network. Check the tunnel status in the Cloudflare dashboard and `docker compose logs cloudflared`. |
| "invalid setup code" | `POD_SETUP_TOKEN` in `.env` (restart after changing it: `docker compose up -d`) |
| "clock skew too large" | The phone's date/time is wrong. Turn on automatic time. |
| Map is a plain dark background | `setup-map.sh` hasn't been run, or `deploy/map/tiles.pmtiles` is missing. Check `https://your-host/map/style.json`. |
| A family member stops updating | On their phone: Settings → "Is everything working?". Usually battery optimization was turned back on by the phone maker. See https://dontkillmyapp.com |
| Requests get a Cloudflare challenge page | Bot Fight Mode or WAF rules. See step 2. |
