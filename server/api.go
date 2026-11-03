package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	maxBodyBytes   = 64 << 10
	maxBlobBytes   = 16 << 10
	maxPollWait    = 85 // seconds; stays under Cloudflare's 100s proxy timeout
	pageLimit      = 500
	historyLimit   = 5000
	sysTTL         = 7 * 24 * time.Hour
	defaultLocTTL  = 24 * time.Hour
	maxInviteTTL   = 24 * time.Hour
	stylePlacehold = "__POD_PUBLIC_URL__"
)

type Server struct {
	cfg       Config
	store     *Store
	notifier  *notifier
	replay    *replayCache
	devLimit  *rateLimiter // per authenticated device
	ipLimit   *rateLimiter // per IP for unauthenticated/bootstrap endpoints
	failLimit *rateLimiter // per IP auth failures
}

func NewServer(cfg Config, store *Store) *Server {
	return &Server{
		cfg:       cfg,
		store:     store,
		notifier:  newNotifier(),
		replay:    newReplayCache(),
		devLimit:  newRateLimiter(5, 60),
		ipLimit:   newRateLimiter(0.2, 10),
		failLimit: newRateLimiter(0.5, 20),
	}
}

func (s *Server) RunJanitor(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if _, err := s.store.Cleanup(); err != nil {
				log.Printf("cleanup: %v", err)
			}
			s.replay.prune()
			s.devLimit.prune()
			s.ipLimit.prune()
			s.failLimit.prune()
		}
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)

	// Bootstrap (self-signed with the key in the body).
	mux.HandleFunc("POST /v1/families", s.handleCreateFamily)
	mux.HandleFunc("POST /v1/join", s.handleJoin)

	// Authenticated.
	mux.HandleFunc("GET /v1/family", s.authed(s.handleGetFamily))
	mux.HandleFunc("DELETE /v1/family", s.authed(s.handleDeleteFamily))
	mux.HandleFunc("POST /v1/invites", s.authed(s.handleCreateInvite))
	mux.HandleFunc("DELETE /v1/members/{id}", s.authed(s.handleRemoveMember))
	mux.HandleFunc("PUT /v1/envelopes", s.authed(s.handlePutEnvelopes))
	mux.HandleFunc("GET /v1/envelopes", s.authed(s.handleGetEnvelopes))
	mux.HandleFunc("POST /v1/messages", s.authed(s.handlePostMessage))
	mux.HandleFunc("GET /v1/messages", s.authed(s.handleGetMessages))
	mux.HandleFunc("GET /v1/state", s.authed(s.handleGetState))
	mux.HandleFunc("GET /v1/history", s.authed(s.handleGetHistory))

	// Self-hosted map (style, fonts, sprites, PMTiles). Public, contains no family data.
	mux.HandleFunc("GET /map/style.json", s.handleStyle)
	mux.Handle("GET /map/", http.StripPrefix("/map/", s.mapFiles()))

	// App updates: latest.json + signed APK. Public; Android refuses to install an
	// update that isn't signed with the same key as the installed app.
	mux.Handle("GET /app/", http.StripPrefix("/app/", s.appFiles()))
	// Permanent, shareable link to the newest app: redirects to the APK named in latest.json.
	mux.HandleFunc("GET /download", s.handleDownload)

	return s.middleware(mux)
}

// --- middleware & helpers -----------------------------------------------------

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		if !strings.HasPrefix(r.URL.Path, "/map/") && !strings.HasPrefix(r.URL.Path, "/app/") {
			h.Set("Cache-Control", "no-store")
		}
		sw := &statusWriter{ResponseWriter: w, status: 200}
		start := time.Now()
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("panic: %v", rec)
				http.Error(sw, "internal error", http.StatusInternalServerError)
			}
			// Never log IPs, headers, query strings or bodies.
			if s.cfg.LogRequests && !strings.HasPrefix(r.URL.Path, "/map/") && !strings.HasPrefix(r.URL.Path, "/app/") && r.URL.Path != "/healthz" {
				log.Printf("%s %s %d %dms", r.Method, r.Pattern, sw.status, time.Since(start).Milliseconds())
			}
		}()
		next.ServeHTTP(sw, r)
	})
}

type apiError struct {
	Error string `json:"error"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, apiError{Error: msg})
}

func readBody(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		writeErr(w, http.StatusRequestEntityTooLarge, "body too large")
		return nil, false
	}
	return body, true
}

func randomID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b64url.EncodeToString(b)
}

type reqCtx struct {
	dev  Device
	fam  Family
	body []byte
}

func (c *reqCtx) isAdmin() bool { return c.fam.AdminDevice == c.dev.ID }

// authed verifies the request signature and loads the caller's device and family.
func (s *Server) authed(h func(http.ResponseWriter, *http.Request, *reqCtx)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := s.clientIP(r)
		body, ok := readBody(w, r)
		if !ok {
			return
		}
		devID := r.Header.Get(headerDevice)
		dev, err := s.store.GetDevice(devID)
		if err == nil {
			err = s.verifySignature(r, body, dev.SignPub)
		}
		if err != nil {
			if !s.failLimit.allow(ip) {
				writeErr(w, http.StatusTooManyRequests, "too many failed requests")
				return
			}
			if errors.Is(err, ErrNotFound) {
				// Device was removed from the family (or never existed).
				writeErr(w, http.StatusUnauthorized, "device not registered")
				return
			}
			writeErr(w, http.StatusUnauthorized, err.Error())
			return
		}
		if !s.devLimit.allow(dev.ID) {
			writeErr(w, http.StatusTooManyRequests, "slow down")
			return
		}
		fam, err := s.store.GetFamily(dev.FamilyID)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "family not found")
			return
		}
		if now()-dev.LastSeen > 60 {
			_ = s.store.TouchDevice(dev.ID)
		}
		h(w, r, &reqCtx{dev: dev, fam: fam, body: body})
	}
}

func (s *Server) sys(familyID string, payload map[string]any) {
	b, _ := json.Marshal(payload)
	if _, err := s.store.AddMessage(familyID, "server", KindSys, b, sysTTL, 0); err != nil {
		log.Printf("sys message: %v", err)
		return
	}
	s.notifier.Notify(familyID)
}

func validKey(b []byte) bool { return len(b) == 32 }

// --- handlers -----------------------------------------------------------------

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"ok": true, "version": Version, "protocol": 1})
}

type keysBody struct {
	SignPub []byte `json:"sign_pub"`
	BoxPub  []byte `json:"box_pub"`
}

func (s *Server) handleCreateFamily(w http.ResponseWriter, r *http.Request) {
	if !s.ipLimit.allow(s.clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "slow down")
		return
	}
	token := r.Header.Get(headerSetup)
	if subtle.ConstantTimeCompare([]byte(token), []byte(s.cfg.SetupToken)) != 1 {
		writeErr(w, http.StatusForbidden, "invalid setup code")
		return
	}
	body, ok := readBody(w, r)
	if !ok {
		return
	}
	var req keysBody
	if err := json.Unmarshal(body, &req); err != nil || !validKey(req.SignPub) || !validKey(req.BoxPub) {
		writeErr(w, http.StatusBadRequest, "invalid keys")
		return
	}
	if err := s.verifySignature(r, body, req.SignPub); err != nil {
		writeErr(w, http.StatusUnauthorized, err.Error())
		return
	}
	dev := Device{ID: DeviceIDFromKey(req.SignPub), SignPub: req.SignPub, BoxPub: req.BoxPub}
	familyID := randomID()
	if err := s.store.CreateFamily(familyID, dev); err != nil {
		if errors.Is(err, ErrConflict) {
			writeErr(w, http.StatusConflict, "device already registered")
			return
		}
		log.Printf("create family: %v", err)
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"family_id": familyID, "device_id": dev.ID})
}

type joinBody struct {
	FamilyID string `json:"family_id"`
	Token    string `json:"token"` // base64url, 32 bytes
	SignPub  []byte `json:"sign_pub"`
	BoxPub   []byte `json:"box_pub"`
	Proof    []byte `json:"proof"` // HMAC(invite secret, ...) - verified by the admin's phone, not the server
}

func (s *Server) handleJoin(w http.ResponseWriter, r *http.Request) {
	if !s.ipLimit.allow(s.clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "slow down")
		return
	}
	body, ok := readBody(w, r)
	if !ok {
		return
	}
	var req joinBody
	if err := json.Unmarshal(body, &req); err != nil || !validKey(req.SignPub) || !validKey(req.BoxPub) || len(req.Proof) != 32 {
		writeErr(w, http.StatusBadRequest, "invalid request")
		return
	}
	token, err := b64url.DecodeString(req.Token)
	if err != nil || len(token) != 32 {
		writeErr(w, http.StatusBadRequest, "invalid invite")
		return
	}
	if err := s.verifySignature(r, body, req.SignPub); err != nil {
		writeErr(w, http.StatusUnauthorized, err.Error())
		return
	}
	th := sha256.Sum256(token)
	dev := Device{ID: DeviceIDFromKey(req.SignPub), SignPub: req.SignPub, BoxPub: req.BoxPub, JoinProof: req.Proof}
	if err := s.store.RedeemInvite(hex.EncodeToString(th[:]), req.FamilyID, dev, s.cfg.MaxDevices); err != nil {
		switch {
		case errors.Is(err, ErrNotFound):
			writeErr(w, http.StatusNotFound, "invite is invalid, expired, or already used")
		case errors.Is(err, ErrConflict):
			writeErr(w, http.StatusConflict, "family is full or device already registered")
		default:
			log.Printf("join: %v", err)
			writeErr(w, 500, "internal error")
		}
		return
	}
	s.sys(req.FamilyID, map[string]any{"type": "member_joined", "device_id": dev.ID})
	writeJSON(w, http.StatusCreated, map[string]any{"family_id": req.FamilyID, "device_id": dev.ID})
}

func (s *Server) handleGetFamily(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	devs, err := s.store.ListDevices(c.fam.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{
		"family_id": c.fam.ID,
		"epoch":     c.fam.Epoch,
		"admin":     c.fam.AdminDevice,
		"members":   devs,
		"you":       c.dev.ID,
	})
}

func (s *Server) handleDeleteFamily(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	if !c.isAdmin() {
		writeErr(w, http.StatusForbidden, "only the family admin can delete the family")
		return
	}
	if err := s.store.DeleteFamily(c.fam.ID); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	s.notifier.Notify(c.fam.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleCreateInvite(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	if !c.isAdmin() {
		writeErr(w, http.StatusForbidden, "only the family admin can invite")
		return
	}
	var req struct {
		TokenHash  string `json:"token_hash"`
		TTLSeconds int64  `json:"ttl_seconds"`
	}
	if err := json.Unmarshal(c.body, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid request")
		return
	}
	if b, err := hex.DecodeString(req.TokenHash); err != nil || len(b) != 32 {
		writeErr(w, http.StatusBadRequest, "invalid token hash")
		return
	}
	ttl := time.Duration(req.TTLSeconds) * time.Second
	if ttl <= 0 || ttl > maxInviteTTL {
		ttl = maxInviteTTL
	}
	exp := time.Now().Add(ttl).Unix()
	if err := s.store.CreateInvite(strings.ToLower(req.TokenHash), c.fam.ID, c.dev.ID, exp); err != nil {
		writeErr(w, http.StatusConflict, "invite already exists")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"expires_at": exp})
}

func (s *Server) handleRemoveMember(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	target := r.PathValue("id")
	self := target == c.dev.ID
	if !self && !c.isAdmin() {
		writeErr(w, http.StatusForbidden, "only the family admin can remove members")
		return
	}
	if self && c.isAdmin() {
		writeErr(w, http.StatusConflict, "the admin cannot leave; delete the family instead")
		return
	}
	if err := s.store.RemoveDevice(c.fam.ID, target); err != nil {
		if errors.Is(err, ErrNotFound) {
			writeErr(w, http.StatusNotFound, "no such member")
			return
		}
		writeErr(w, 500, "internal error")
		return
	}
	s.sys(c.fam.ID, map[string]any{"type": "member_removed", "device_id": target})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handlePutEnvelopes(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	if !c.isAdmin() {
		writeErr(w, http.StatusForbidden, "only the family admin can distribute keys")
		return
	}
	var req struct {
		Epoch     int64 `json:"epoch"`
		Envelopes []struct {
			DeviceID string `json:"device_id"`
			Data     []byte `json:"data"`
		} `json:"envelopes"`
	}
	if err := json.Unmarshal(c.body, &req); err != nil || req.Epoch < 1 || len(req.Envelopes) == 0 {
		writeErr(w, http.StatusBadRequest, "invalid request")
		return
	}
	envs := map[string][]byte{}
	for _, e := range req.Envelopes {
		if len(e.Data) == 0 || len(e.Data) > 1024 {
			writeErr(w, http.StatusBadRequest, "invalid envelope")
			return
		}
		envs[e.DeviceID] = e.Data
	}
	if err := s.store.PutEnvelopes(c.fam.ID, req.Epoch, envs); err != nil {
		switch {
		case errors.Is(err, ErrConflict):
			writeErr(w, http.StatusConflict, "epoch is older than the current epoch")
		case errors.Is(err, ErrNotFound):
			writeErr(w, http.StatusBadRequest, "envelope for unknown device")
		default:
			writeErr(w, 500, "internal error")
		}
		return
	}
	s.sys(c.fam.ID, map[string]any{"type": "epoch", "epoch": req.Epoch})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleGetEnvelopes(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	envs, err := s.store.GetEnvelopes(c.fam.ID, c.dev.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{"current_epoch": c.fam.Epoch, "envelopes": envs})
}

func (s *Server) handlePostMessage(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	var req struct {
		Kind       string `json:"kind"`
		Blob       []byte `json:"blob"`
		TTLSeconds int64  `json:"ttl_seconds"`
	}
	if err := json.Unmarshal(c.body, &req); err != nil || !clientKinds[req.Kind] || len(req.Blob) == 0 {
		writeErr(w, http.StatusBadRequest, "invalid message")
		return
	}
	if len(req.Blob) > maxBlobBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "message too large")
		return
	}
	var ttl time.Duration
	if !stickyKinds[req.Kind] {
		ttl = time.Duration(req.TTLSeconds) * time.Second
		if ttl <= 0 {
			ttl = defaultLocTTL
		}
		if ttl > s.cfg.MaxTTL {
			ttl = s.cfg.MaxTTL
		}
	}
	id, err := s.store.AddMessage(c.fam.ID, c.dev.ID, req.Kind, req.Blob, ttl, s.cfg.MaxDeviceBytes)
	if errors.Is(err, ErrQuota) {
		writeErr(w, http.StatusInsufficientStorage, "this phone has reached the server's storage limit; older history expires automatically")
		return
	}
	if err != nil {
		log.Printf("add message: %v", err)
		writeErr(w, 500, "internal error")
		return
	}
	s.notifier.Notify(c.fam.ID)
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

func (s *Server) handleGetMessages(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	q := r.URL.Query()
	after, _ := strconv.ParseInt(q.Get("after"), 10, 64)
	wait, _ := strconv.Atoi(q.Get("wait"))
	if wait < 0 {
		wait = 0
	}
	if wait > maxPollWait {
		wait = maxPollWait
	}
	// Subscribe before querying so a message arriving in between is not missed.
	ch := s.notifier.Wait(c.fam.ID)
	msgs, err := s.store.MessagesAfter(c.fam.ID, after, pageLimit)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	if len(msgs) == 0 && wait > 0 {
		timer := time.NewTimer(time.Duration(wait) * time.Second)
		select {
		case <-ch:
		case <-timer.C:
		case <-r.Context().Done():
			timer.Stop()
			return
		}
		timer.Stop()
		if msgs, err = s.store.MessagesAfter(c.fam.ID, after, pageLimit); err != nil {
			writeErr(w, 500, "internal error")
			return
		}
	}
	cursor := after
	if len(msgs) > 0 {
		cursor = msgs[len(msgs)-1].ID
	}
	// If the family was deleted while waiting, tell the client.
	if _, err := s.store.GetFamily(c.fam.ID); errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusGone, "family deleted")
		return
	}
	writeJSON(w, 200, map[string]any{"messages": msgs, "cursor": cursor, "more": len(msgs) == pageLimit})
}

func (s *Server) handleGetState(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	msgs, cursor, err := s.store.LatestState(c.fam.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{"messages": msgs, "cursor": cursor})
}

func (s *Server) handleGetHistory(w http.ResponseWriter, r *http.Request, c *reqCtx) {
	q := r.URL.Query()
	kind := q.Get("kind")
	if kind == "" {
		kind = KindLoc
	}
	if !clientKinds[kind] {
		writeErr(w, http.StatusBadRequest, "invalid kind")
		return
	}
	since, _ := strconv.ParseInt(q.Get("since"), 10, 64)
	msgs, err := s.store.History(c.fam.ID, q.Get("sender"), kind, since, historyLimit)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{"messages": msgs})
}

// --- map ------------------------------------------------------------------------

func (s *Server) publicURL(r *http.Request) string {
	if s.cfg.PublicURL != "" {
		return strings.TrimRight(s.cfg.PublicURL, "/")
	}
	return "https://" + r.Host
}

func (s *Server) handleStyle(w http.ResponseWriter, r *http.Request) {
	b, err := os.ReadFile(filepath.Join(s.cfg.MapDir, "style.template.json"))
	if err != nil {
		writeErr(w, http.StatusNotFound, "map style not installed (run deploy/setup-map.sh)")
		return
	}
	out := strings.ReplaceAll(string(b), stylePlacehold, s.publicURL(r))
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	_, _ = io.WriteString(w, out)
}

// mapFiles serves fonts, sprites and the PMTiles archive (with HTTP Range
// support, which PMTiles needs). Directory listings are disabled.
func (s *Server) mapFiles() http.Handler {
	fs := http.FileServer(http.Dir(s.cfg.MapDir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "" || strings.HasSuffix(r.URL.Path, "/") || strings.Contains(r.URL.Path, "..") {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		fs.ServeHTTP(w, r)
	})
}

// appFiles serves the update manifest and APKs. latest.json is never cached so
// phones see a new release immediately; APK names are versioned so they can be.
func (s *Server) appFiles() http.Handler {
	fs := http.FileServer(http.Dir(s.cfg.AppDir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if p == "" || strings.HasSuffix(p, "/") || strings.Contains(p, "..") ||
			!(p == "latest.json" || strings.HasSuffix(p, ".apk")) {
			http.NotFound(w, r)
			return
		}
		if p == "latest.json" {
			w.Header().Set("Cache-Control", "no-cache")
		} else {
			w.Header().Set("Cache-Control", "public, max-age=86400")
			w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		}
		fs.ServeHTTP(w, r)
	})
}

var apkNameRe = regexp.MustCompile(`^pod-[0-9]+\.[0-9]+\.[0-9]+\.apk$`)

// handleDownload sends people to the newest published APK, so one link works forever.
// It only ever redirects to a strictly named file under /app/; nothing else is reachable.
func (s *Server) handleDownload(w http.ResponseWriter, r *http.Request) {
	raw, err := os.ReadFile(filepath.Join(s.cfg.AppDir, "latest.json"))
	if err != nil {
		writeErr(w, http.StatusNotFound, "no app release has been published yet")
		return
	}
	var m struct {
		APK string `json:"apk"`
	}
	if json.Unmarshal(raw, &m) != nil || !apkNameRe.MatchString(m.APK) {
		writeErr(w, http.StatusNotFound, "no app release has been published yet")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	http.Redirect(w, r, "/app/"+m.APK, http.StatusFound)
}

// b64 helpers used by tests and tooling.
var b64std = base64.StdEncoding
