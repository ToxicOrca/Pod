package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)

type testDevice struct {
	pub  ed25519.PublicKey
	priv ed25519.PrivateKey
	box  []byte
	id   string
}

func newTestDevice(t *testing.T) *testDevice {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	box := make([]byte, 32)
	rand.Read(box)
	return &testDevice{pub: pub, priv: priv, box: box, id: DeviceIDFromKey(pub)}
}

type testEnv struct {
	t   *testing.T
	srv *httptest.Server
	s   *Server
}

func newTestEnv(t *testing.T) *testEnv {
	dir := t.TempDir()
	store, err := OpenStore(filepath.Join(dir, "pod.db"))
	if err != nil {
		t.Fatal(err)
	}
	mapDir := filepath.Join(dir, "map")
	os.MkdirAll(filepath.Join(mapDir, "fonts"), 0o755)
	os.WriteFile(filepath.Join(mapDir, "style.template.json"), []byte(`{"url":"pmtiles://__POD_PUBLIC_URL__/map/tiles.pmtiles"}`), 0o644)
	os.WriteFile(filepath.Join(mapDir, "tiles.pmtiles"), []byte("0123456789"), 0o644)
	appDir := filepath.Join(dir, "app")
	os.MkdirAll(appDir, 0o755)
	os.WriteFile(filepath.Join(appDir, "latest.json"), []byte(`{"versionCode":3}`), 0o644)
	os.WriteFile(filepath.Join(appDir, "pod-0.3.0.apk"), []byte("APK"), 0o644)
	os.WriteFile(filepath.Join(appDir, "secret.txt"), []byte("nope"), 0o644)
	cfg := Config{SetupToken: "setup-token-123456", MaxTTL: 30 * 24 * time.Hour, MaxDevices: 4, PublicURL: "https://pod.example.com", MapDir: mapDir, AppDir: appDir}
	s := NewServer(cfg, store)
	// Generous limits for tests.
	s.ipLimit = newRateLimiter(1000, 1000)
	srv := httptest.NewServer(s.Handler())
	t.Cleanup(func() { srv.Close(); store.Close() })
	return &testEnv{t: t, srv: srv, s: s}
}

// do sends a signed request. If dev is nil no signature is added.
func (e *testEnv) do(dev *testDevice, selfSigned bool, method, path string, body any, extra map[string]string) (int, map[string]any) {
	e.t.Helper()
	var raw []byte
	if body != nil {
		raw, _ = json.Marshal(body)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, bytes.NewReader(raw))
	if dev != nil {
		ts := strconv.FormatInt(time.Now().Unix(), 10)
		nonce := randomID()
		sig := ed25519.Sign(dev.priv, canonicalString(method, path, ts, nonce, raw))
		req.Header.Set(headerTime, ts)
		req.Header.Set(headerNonce, nonce)
		req.Header.Set(headerSig, base64.StdEncoding.EncodeToString(sig))
		if !selfSigned {
			req.Header.Set(headerDevice, dev.id)
		}
	}
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	out := map[string]any{}
	_ = json.Unmarshal(b, &out)
	return resp.StatusCode, out
}

func (e *testEnv) createFamily(admin *testDevice) string {
	code, out := e.do(admin, true, "POST", "/v1/families", keysBody{SignPub: admin.pub, BoxPub: admin.box},
		map[string]string{headerSetup: "setup-token-123456"})
	if code != 201 {
		e.t.Fatalf("create family: %d %v", code, out)
	}
	return out["family_id"].(string)
}

func (e *testEnv) invite(admin *testDevice) string {
	token := make([]byte, 32)
	rand.Read(token)
	h := sha256.Sum256(token)
	code, out := e.do(admin, false, "POST", "/v1/invites", map[string]any{"token_hash": hex.EncodeToString(h[:]), "ttl_seconds": 600}, nil)
	if code != 201 {
		e.t.Fatalf("invite: %d %v", code, out)
	}
	return b64url.EncodeToString(token)
}

func (e *testEnv) join(dev *testDevice, familyID, token string) (int, map[string]any) {
	proof := make([]byte, 32)
	return e.do(dev, true, "POST", "/v1/join", joinBody{FamilyID: familyID, Token: token, SignPub: dev.pub, BoxPub: dev.box, Proof: proof}, nil)
}

func TestFullFlow(t *testing.T) {
	e := newTestEnv(t)
	admin := newTestDevice(t)
	kid := newTestDevice(t)

	// Wrong setup token is rejected.
	if code, _ := e.do(admin, true, "POST", "/v1/families", keysBody{SignPub: admin.pub, BoxPub: admin.box}, map[string]string{headerSetup: "nope"}); code != 403 {
		t.Fatalf("expected 403, got %d", code)
	}
	fam := e.createFamily(admin)

	token := e.invite(admin)
	if code, out := e.join(kid, fam, token); code != 201 || out["device_id"] != kid.id {
		t.Fatalf("join: %d %v", code, out)
	}
	// Invite is single use.
	other := newTestDevice(t)
	if code, _ := e.join(other, fam, token); code != 404 {
		t.Fatalf("reused invite should fail, got %d", code)
	}

	// Family listing.
	code, out := e.do(kid, false, "GET", "/v1/family", nil, nil)
	if code != 200 || len(out["members"].([]any)) != 2 || out["admin"] != admin.id {
		t.Fatalf("family: %d %v", code, out)
	}

	// Non-admin cannot distribute keys or invite.
	if code, _ := e.do(kid, false, "PUT", "/v1/envelopes", map[string]any{"epoch": 1, "envelopes": []map[string]any{{"device_id": kid.id, "data": []byte("x")}}}, nil); code != 403 {
		t.Fatalf("kid put envelopes: %d", code)
	}
	// Admin distributes epoch 1.
	code, _ = e.do(admin, false, "PUT", "/v1/envelopes", map[string]any{"epoch": 1, "envelopes": []map[string]any{
		{"device_id": admin.id, "data": []byte("env-admin")}, {"device_id": kid.id, "data": []byte("env-kid")}}}, nil)
	if code != 204 {
		t.Fatalf("put envelopes: %d", code)
	}
	code, out = e.do(kid, false, "GET", "/v1/envelopes", nil, nil)
	envs := out["envelopes"].([]any)
	if code != 200 || len(envs) != 1 || out["current_epoch"].(float64) != 1 {
		t.Fatalf("get envelopes: %d %v", code, out)
	}
	if d, _ := base64.StdEncoding.DecodeString(envs[0].(map[string]any)["data"].(string)); string(d) != "env-kid" {
		t.Fatalf("kid got wrong envelope %q", d)
	}

	// Messages + cursor.
	_, st := e.do(kid, false, "GET", "/v1/state", nil, nil)
	cursor := int64(st["cursor"].(float64))
	for i := 0; i < 3; i++ {
		if code, _ := e.do(admin, false, "POST", "/v1/messages", map[string]any{"kind": "loc", "blob": []byte(fmt.Sprintf("loc-%d", i)), "ttl_seconds": 3600}, nil); code != 201 {
			t.Fatalf("post: %d", code)
		}
	}
	e.do(admin, false, "POST", "/v1/messages", map[string]any{"kind": "profile", "blob": []byte("p1")}, nil)
	e.do(admin, false, "POST", "/v1/messages", map[string]any{"kind": "profile", "blob": []byte("p2")}, nil)
	code, out = e.do(kid, false, "GET", fmt.Sprintf("/v1/messages?after=%d", cursor), nil, nil)
	if code != 200 || len(out["messages"].([]any)) != 4 { // 3 locs + latest profile (sticky replaced p1)
		t.Fatalf("messages: %d %v", code, out)
	}
	// State: latest loc + latest profile from admin.
	_, st = e.do(kid, false, "GET", "/v1/state", nil, nil)
	if n := len(st["messages"].([]any)); n != 2 {
		t.Fatalf("state should have 2 messages, got %d", n)
	}

	// Bad kind rejected.
	if code, _ := e.do(kid, false, "POST", "/v1/messages", map[string]any{"kind": "sys", "blob": []byte("x")}, nil); code != 400 {
		t.Fatalf("sys kind should be rejected: %d", code)
	}

	// Kid cannot remove admin; admin removes kid; kid is locked out.
	if code, _ := e.do(kid, false, "DELETE", "/v1/members/"+admin.id, nil, nil); code != 403 {
		t.Fatalf("kid removing admin: %d", code)
	}
	if code, _ := e.do(admin, false, "DELETE", "/v1/members/"+kid.id, nil, nil); code != 204 {
		t.Fatalf("remove kid: %d", code)
	}
	if code, _ := e.do(kid, false, "GET", "/v1/family", nil, nil); code != 401 {
		t.Fatalf("removed device should get 401, got %d", code)
	}
}

func TestAuthRejections(t *testing.T) {
	e := newTestEnv(t)
	admin := newTestDevice(t)
	e.createFamily(admin)

	// Tampered body.
	raw, _ := json.Marshal(map[string]any{"kind": "loc", "blob": []byte("a")})
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	nonce := randomID()
	sig := ed25519.Sign(admin.priv, canonicalString("POST", "/v1/messages", ts, nonce, raw))
	send := func(body []byte, ts string) int {
		req, _ := http.NewRequest("POST", e.srv.URL+"/v1/messages", bytes.NewReader(body))
		req.Header.Set(headerDevice, admin.id)
		req.Header.Set(headerTime, ts)
		req.Header.Set(headerNonce, nonce)
		req.Header.Set(headerSig, base64.StdEncoding.EncodeToString(sig))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	tampered, _ := json.Marshal(map[string]any{"kind": "loc", "blob": []byte("b")})
	if c := send(tampered, ts); c != 401 {
		t.Fatalf("tampered body accepted: %d", c)
	}
	if c := send(raw, ts); c != 201 {
		t.Fatalf("valid request rejected: %d", c)
	}
	if c := send(raw, ts); c != 401 {
		t.Fatalf("replay accepted: %d", c)
	}
	// Old timestamp.
	old := strconv.FormatInt(time.Now().Add(-10*time.Minute).Unix(), 10)
	nonce = randomID()
	sig = ed25519.Sign(admin.priv, canonicalString("POST", "/v1/messages", old, nonce, raw))
	if c := send(raw, old); c != 401 {
		t.Fatalf("stale request accepted: %d", c)
	}
	// Unknown device.
	stranger := newTestDevice(t)
	if code, _ := e.do(stranger, false, "GET", "/v1/family", nil, nil); code != 401 {
		t.Fatalf("stranger accepted: %d", code)
	}
}

func TestLongPollWakes(t *testing.T) {
	e := newTestEnv(t)
	admin := newTestDevice(t)
	e.createFamily(admin)
	_, st := e.do(admin, false, "GET", "/v1/state", nil, nil)
	cursor := int64(st["cursor"].(float64))

	var wg sync.WaitGroup
	wg.Add(1)
	var got int
	var elapsed time.Duration
	go func() {
		defer wg.Done()
		start := time.Now()
		_, out := e.do(admin, false, "GET", fmt.Sprintf("/v1/messages?after=%d&wait=20", cursor), nil, nil)
		elapsed = time.Since(start)
		got = len(out["messages"].([]any))
	}()
	time.Sleep(300 * time.Millisecond)
	e.do(admin, false, "POST", "/v1/messages", map[string]any{"kind": "event", "blob": []byte("arrived")}, nil)
	wg.Wait()
	if got != 1 || elapsed > 5*time.Second {
		t.Fatalf("long poll: got %d messages after %v", got, elapsed)
	}
}

func TestFamilyLimitAndMap(t *testing.T) {
	e := newTestEnv(t)
	admin := newTestDevice(t)
	fam := e.createFamily(admin)
	for i := 0; i < 3; i++ {
		if code, _ := e.join(newTestDevice(t), fam, e.invite(admin)); code != 201 {
			t.Fatalf("join %d failed: %d", i, code)
		}
	}
	if code, _ := e.join(newTestDevice(t), fam, e.invite(admin)); code != 409 {
		t.Fatalf("family over limit should be 409, got %d", code)
	}

	resp, _ := http.Get(e.srv.URL + "/map/style.json")
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != `{"url":"pmtiles://https://pod.example.com/map/tiles.pmtiles"}` {
		t.Fatalf("style: %s", b)
	}
	req, _ := http.NewRequest("GET", e.srv.URL+"/map/tiles.pmtiles", nil)
	req.Header.Set("Range", "bytes=2-4")
	resp, _ = http.DefaultClient.Do(req)
	b, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 206 || string(b) != "234" {
		t.Fatalf("range request: %d %q", resp.StatusCode, b)
	}
	resp, _ = http.Get(e.srv.URL + "/map/fonts/")
	resp.Body.Close()
	if resp.StatusCode != 404 {
		t.Fatalf("directory listing should be disabled, got %d", resp.StatusCode)
	}
}

func TestDeleteFamily(t *testing.T) {
	e := newTestEnv(t)
	admin := newTestDevice(t)
	kid := newTestDevice(t)
	fam := e.createFamily(admin)
	e.join(kid, fam, e.invite(admin))
	if code, _ := e.do(kid, false, "DELETE", "/v1/family", nil, nil); code != 403 {
		t.Fatalf("kid deleting family: %d", code)
	}
	if code, _ := e.do(admin, false, "DELETE", "/v1/family", nil, nil); code != 204 {
		t.Fatalf("delete family: %d", code)
	}
	if code, _ := e.do(admin, false, "GET", "/v1/family", nil, nil); code != 401 {
		t.Fatalf("after delete: %d", code)
	}
}

func TestAppUpdates(t *testing.T) {
	e := newTestEnv(t)
	get := func(path string) (int, string, http.Header) {
		resp, err := http.Get(e.srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b), resp.Header
	}
	if c, body, h := get("/app/latest.json"); c != 200 || body != `{"versionCode":3}` || h.Get("Cache-Control") != "no-cache" {
		t.Fatalf("latest.json: %d %q %q", c, body, h.Get("Cache-Control"))
	}
	if c, body, h := get("/app/pod-0.3.0.apk"); c != 200 || body != "APK" || h.Get("Content-Type") != "application/vnd.android.package-archive" {
		t.Fatalf("apk: %d %q %q", c, body, h.Get("Content-Type"))
	}
	for _, p := range []string{"/app/secret.txt", "/app/"} {
		if c, _, _ := get(p); c != 404 && c != 301 {
			t.Fatalf("%s should not be served, got %d", p, c)
		}
	}
}

// One signature has 16 base64 spellings (unused trailing bits). Only one request may get through.
func TestReplayAlternateBase64(t *testing.T) {
	e := newTestEnv(t)
	admin := newTestDevice(t)
	e.createFamily(admin)
	raw, _ := json.Marshal(map[string]any{"kind": "loc", "blob": []byte("a")})
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	nonce := randomID()
	sig := ed25519.Sign(admin.priv, canonicalString("POST", "/v1/messages", ts, nonce, raw))
	enc := base64.StdEncoding.EncodeToString(sig) // 88 chars, ends in "=="
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	accepted := 0
	for _, c := range alphabet {
		variant := enc[:85] + string(c) + "=="
		req, _ := http.NewRequest("POST", e.srv.URL+"/v1/messages", bytes.NewReader(raw))
		req.Header.Set(headerDevice, admin.id)
		req.Header.Set(headerTime, ts)
		req.Header.Set(headerNonce, nonce)
		req.Header.Set(headerSig, variant)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode == 201 {
			accepted++
		}
	}
	if accepted != 1 {
		t.Fatalf("expected exactly 1 accepted request, got %d", accepted)
	}
}

func TestDownloadRedirect(t *testing.T) {
	e := newTestEnv(t)
	noFollow := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	get := func() (int, string) {
		resp, err := noFollow.Get(e.srv.URL + "/download")
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode, resp.Header.Get("Location")
	}
	// The test manifest has no apk field yet.
	if code, _ := get(); code != 404 {
		t.Fatalf("expected 404 without a release, got %d", code)
	}
	os.WriteFile(filepath.Join(e.s.cfg.AppDir, "latest.json"), []byte(`{"versionCode":3,"apk":"pod-0.3.0.apk"}`), 0o644)
	if code, loc := get(); code != 302 || loc != "/app/pod-0.3.0.apk" {
		t.Fatalf("expected redirect to the apk, got %d %q", code, loc)
	}
	os.WriteFile(filepath.Join(e.s.cfg.AppDir, "latest.json"), []byte(`{"apk":"../secret.txt"}`), 0o644)
	if code, _ := get(); code != 404 {
		t.Fatalf("bad apk name must not redirect, got %d", code)
	}
}

func TestStorageQuota(t *testing.T) {
	e := newTestEnv(t)
	e.s.cfg.MaxDeviceBytes = 2500
	admin := newTestDevice(t)
	e.createFamily(admin)
	blob := make([]byte, 1000)
	codes := []int{}
	for i := 0; i < 4; i++ {
		code, _ := e.do(admin, false, "POST", "/v1/messages", map[string]any{"kind": "loc", "blob": blob, "ttl_seconds": 3600}, nil)
		codes = append(codes, code)
	}
	if codes[0] != 201 || codes[1] != 201 || codes[2] != 507 || codes[3] != 507 {
		t.Fatalf("expected 201,201,507,507 got %v", codes)
	}
}

func TestConfigRejectsUnsafeDefaults(t *testing.T) {
	t.Setenv("POD_SETUP_TOKEN", "change-me-to-a-long-random-value")
	if _, err := loadConfig(); err == nil {
		t.Fatal("example setup token was accepted")
	}
	t.Setenv("POD_SETUP_TOKEN", "a-real-random-token-123456")
	t.Setenv("POD_TRUST_CF_IP", "")
	c, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	if c.TrustCF {
		t.Fatal("Cloudflare IP header must not be trusted by default")
	}
	if c.MaxDeviceBytes != 200<<20 {
		t.Fatalf("unexpected default storage limit %d", c.MaxDeviceBytes)
	}
}
