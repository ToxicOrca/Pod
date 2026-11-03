package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Request authentication.
//
// There are no accounts or passwords. Each device has an Ed25519 key pair
// generated on the phone. Every request is signed:
//
//	Pod-Device:    <device id>              (omitted for self-signed bootstrap requests)
//	Pod-Timestamp: <unix seconds>
//	Pod-Nonce:     <16 random bytes, base64url>
//	Pod-Signature: base64(Ed25519(sk, canonical))
//
//	canonical = "POD-AUTH-1\n" + METHOD + "\n" + REQUEST_URI + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + hex(SHA256(body))
//
// Requests outside a +/-5 minute window or replaying a seen signature are rejected.

const (
	authSkew       = 5 * time.Minute
	headerDevice   = "Pod-Device"
	headerTime     = "Pod-Timestamp"
	headerSig      = "Pod-Signature"
	headerNonce    = "Pod-Nonce"
	headerSetup    = "Pod-Setup-Token"
	authPrefix     = "POD-AUTH-1"
	deviceIDLength = 16 // bytes of SHA-256(sign_pub) used for the device id
)

var b64url = base64.RawURLEncoding

// DeviceIDFromKey derives the device id from its Ed25519 public key.
func DeviceIDFromKey(signPub []byte) string {
	h := sha256.Sum256(signPub)
	return b64url.EncodeToString(h[:deviceIDLength])
}

func canonicalString(method, requestURI, ts, nonce string, body []byte) []byte {
	h := sha256.Sum256(body)
	return []byte(fmt.Sprintf("%s\n%s\n%s\n%s\n%s\n%s", authPrefix, method, requestURI, ts, nonce, hex.EncodeToString(h[:])))
}

var errAuth = errors.New("authentication failed")

// replayCache remembers recently seen signatures so a captured request cannot be resent.
type replayCache struct {
	mu   sync.Mutex
	seen map[string]int64
}

func newReplayCache() *replayCache { return &replayCache{seen: map[string]int64{}} }

func (c *replayCache) checkAndAdd(sig string, expires int64) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.seen[sig]; ok {
		return false
	}
	c.seen[sig] = expires
	return true
}

func (c *replayCache) prune() {
	t := now()
	c.mu.Lock()
	defer c.mu.Unlock()
	for k, exp := range c.seen {
		if exp < t {
			delete(c.seen, k)
		}
	}
}

// verifySignature checks the signature headers against the given public key.
func (s *Server) verifySignature(r *http.Request, body []byte, pub []byte) error {
	ts := r.Header.Get(headerTime)
	sigB64 := r.Header.Get(headerSig)
	nonce := r.Header.Get(headerNonce)
	if ts == "" || sigB64 == "" || len(nonce) < 16 || len(nonce) > 64 || len(pub) != ed25519.PublicKeySize {
		return errAuth
	}
	t, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return errAuth
	}
	skew := time.Duration(now()-t) * time.Second
	if skew > authSkew || skew < -authSkew {
		return fmt.Errorf("%w: clock skew too large (check the phone's date/time)", errAuth)
	}
	// Strict: reject non-canonical base64 so one signature has exactly one valid spelling.
	sig, err := base64.StdEncoding.Strict().DecodeString(sigB64)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return errAuth
	}
	if !ed25519.Verify(ed25519.PublicKey(pub), canonicalString(r.Method, r.URL.RequestURI(), ts, nonce, body), sig) {
		return errAuth
	}
	// Keyed on the decoded signature bytes (Ed25519 signatures are unique per message),
	// so re-encoding the same signature differently can't slip past the replay check.
	if !s.replay.checkAndAdd(string(sig), t+int64(2*authSkew/time.Second)) {
		return fmt.Errorf("%w: replayed request", errAuth)
	}
	return nil
}

// --- rate limiting -----------------------------------------------------------

type bucket struct {
	tokens float64
	last   time.Time
}

type rateLimiter struct {
	mu      sync.Mutex
	rate    float64 // tokens per second
	burst   float64
	buckets map[string]*bucket
}

func newRateLimiter(perSecond, burst float64) *rateLimiter {
	return &rateLimiter{rate: perSecond, burst: burst, buckets: map[string]*bucket{}}
}

func (l *rateLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	t := time.Now()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: t}
		l.buckets[key] = b
	}
	b.tokens += t.Sub(b.last).Seconds() * l.rate
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.last = t
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func (l *rateLimiter) prune() {
	l.mu.Lock()
	defer l.mu.Unlock()
	cutoff := time.Now().Add(-10 * time.Minute)
	for k, b := range l.buckets {
		if b.last.Before(cutoff) {
			delete(l.buckets, k)
		}
	}
}

// clientIP is used only as a rate-limit key; it is never logged or stored.
func (s *Server) clientIP(r *http.Request) string {
	if s.cfg.TrustCF {
		if ip := r.Header.Get("CF-Connecting-IP"); ip != "" {
			return ip
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
