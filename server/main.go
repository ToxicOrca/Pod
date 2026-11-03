// Pod Family Location server.
//
// The server is deliberately "blind": it stores and relays end-to-end
// encrypted blobs and public keys. It never sees locations, names, places,
// or any other family data in plaintext. See docs/DESIGN.md.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Version is overridden at build time with -ldflags "-X main.Version=...".
var Version = "dev"

type Config struct {
	Listen         string        // address to listen on, e.g. ":8080"
	DBPath         string        // SQLite database path
	SetupToken     string        // required to create a new family on this server
	PublicURL      string        // public https URL (used in the map style)
	MapDir         string        // directory with style.template.json, fonts/, sprites/, tiles.pmtiles
	AppDir         string        // directory with latest.json + the signed release APK (in-app updates)
	MaxTTL         time.Duration // maximum retention a client may request for a message
	TrustCF        bool          // trust CF-Connecting-IP for rate limiting (only behind Cloudflare Tunnel)
	MaxDevices     int           // max devices per family
	MaxDeviceBytes int64         // max stored message bytes per device (0 = unlimited)
	LogRequests    bool          // log method/route/status (never IPs or bodies)
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func loadConfig() (Config, error) {
	maxTTLDays, err := strconv.Atoi(env("POD_MAX_HISTORY_DAYS", "30"))
	if err != nil || maxTTLDays < 1 {
		return Config{}, errors.New("POD_MAX_HISTORY_DAYS must be a positive integer")
	}
	maxDevices, err := strconv.Atoi(env("POD_MAX_DEVICES", "20"))
	if err != nil || maxDevices < 2 {
		return Config{}, errors.New("POD_MAX_DEVICES must be >= 2")
	}
	// ~30 days of location every 10 s while driving all day is well under this.
	maxStorageMB, err := strconv.Atoi(env("POD_MAX_DEVICE_STORAGE_MB", "200"))
	if err != nil || maxStorageMB < 0 {
		return Config{}, errors.New("POD_MAX_DEVICE_STORAGE_MB must be a number of megabytes (0 = unlimited)")
	}
	c := Config{
		Listen:     env("POD_LISTEN", ":8080"),
		DBPath:     env("POD_DB", "/data/pod.db"),
		SetupToken: os.Getenv("POD_SETUP_TOKEN"),
		PublicURL:  env("POD_PUBLIC_URL", ""),
		MapDir:     env("POD_MAP_DIR", "/map"),
		AppDir:     env("POD_APP_DIR", "/app"),
		MaxTTL:     time.Duration(maxTTLDays) * 24 * time.Hour,
		// Only trust Cloudflare's client-IP header when told to (the Cloudflare Tunnel compose
		// file sets it). Otherwise anyone reaching the server directly could fake their IP.
		TrustCF:        env("POD_TRUST_CF_IP", "false") == "true",
		MaxDevices:     maxDevices,
		MaxDeviceBytes: int64(maxStorageMB) << 20,
		LogRequests:    env("POD_LOG_REQUESTS", "true") == "true",
	}
	if len(c.SetupToken) < 16 {
		return Config{}, errors.New("POD_SETUP_TOKEN must be set to a random value of at least 16 characters")
	}
	if strings.Contains(c.SetupToken, "change-me") {
		return Config{}, errors.New("POD_SETUP_TOKEN is still the example value: generate one with `openssl rand -base64 24`")
	}
	return c, nil
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		// Used by the Docker HEALTHCHECK (the image has no curl).
		addr := env("POD_LISTEN", ":8080")
		if addr[0] == ':' {
			addr = "127.0.0.1" + addr
		}
		client := &http.Client{Timeout: 5 * time.Second}
		resp, err := client.Get("http://" + addr + "/healthz")
		if err != nil || resp.StatusCode != 200 {
			os.Exit(1)
		}
		os.Exit(0)
	}

	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	store, err := OpenStore(cfg.DBPath)
	if err != nil {
		log.Fatalf("store: %v", err)
	}
	defer store.Close()

	srv := NewServer(cfg, store)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go srv.RunJanitor(ctx)

	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      120 * time.Second, // long-poll waits up to 85s
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	go func() {
		log.Printf("pod-server %s listening on %s", Version, cfg.Listen)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("listen: %v", err)
		}
	}()
	<-ctx.Done()
	log.Printf("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	srv.notifier.CloseAll()
	_ = httpSrv.Shutdown(shutdownCtx)
}
