package main

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

// Message kinds. Everything except KindSys is end-to-end encrypted by clients.
const (
	KindLoc     = "loc"     // location update (retained for its TTL -> history)
	KindEvent   = "event"   // arrival/departure, SOS, check-in, pause notice
	KindProfile = "profile" // member display name, color, avatar (sticky: latest per sender)
	KindPlaces  = "places"  // shared list of places/geofences (sticky: latest per sender)
	KindSys     = "sys"     // server-generated plaintext notices (member joined/removed, new key epoch)
)

var clientKinds = map[string]bool{KindLoc: true, KindEvent: true, KindProfile: true, KindPlaces: true}
var stickyKinds = map[string]bool{KindProfile: true, KindPlaces: true}

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("conflict")
	// ErrQuota means the sender already stores as much as the server allows.
	ErrQuota = errors.New("storage quota exceeded")
)

type Store struct {
	db *sql.DB
}

type Family struct {
	ID          string
	Epoch       int64
	AdminDevice string
	CreatedAt   int64
}

type Device struct {
	ID        string `json:"device_id"`
	FamilyID  string `json:"-"`
	SignPub   []byte `json:"sign_pub"`
	BoxPub    []byte `json:"box_pub"`
	Role      string `json:"role"`
	JoinProof []byte `json:"join_proof,omitempty"`
	CreatedAt int64  `json:"created_at"`
	LastSeen  int64  `json:"last_seen"`
}

type Message struct {
	ID        int64  `json:"id"`
	Sender    string `json:"sender"`
	Kind      string `json:"kind"`
	Blob      []byte `json:"blob"`
	CreatedAt int64  `json:"created_at"`
}

type Envelope struct {
	Epoch int64  `json:"epoch"`
	Data  []byte `json:"data"`
}

const schema = `
CREATE TABLE IF NOT EXISTS families (
  id           TEXT PRIMARY KEY,
  epoch        INTEGER NOT NULL DEFAULT 0,
  admin_device TEXT NOT NULL,
  created_at   INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
  id         TEXT PRIMARY KEY,
  family_id  TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  sign_pub   BLOB NOT NULL,
  box_pub    BLOB NOT NULL,
  role       TEXT NOT NULL,
  join_proof BLOB,
  created_at INTEGER NOT NULL,
  last_seen  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS devices_family ON devices(family_id);
CREATE TABLE IF NOT EXISTS invites (
  token_hash TEXT PRIMARY KEY,
  family_id  TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  created_by TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  used_by    TEXT
);
CREATE TABLE IF NOT EXISTS envelopes (
  family_id  TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  epoch      INTEGER NOT NULL,
  device_id  TEXT NOT NULL,
  data       BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (family_id, epoch, device_id)
);
CREATE TABLE IF NOT EXISTS messages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  family_id  TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  sender     TEXT NOT NULL,
  kind       TEXT NOT NULL,
  blob       BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS messages_family_id ON messages(family_id, id);
CREATE INDEX IF NOT EXISTS messages_latest ON messages(family_id, sender, kind, id);
CREATE INDEX IF NOT EXISTS messages_expiry ON messages(expires_at) WHERE expires_at IS NOT NULL;
`

func OpenStore(path string) (*Store, error) {
	dsn := fmt.Sprintf("file:%s?_journal_mode=WAL&_busy_timeout=5000&_foreign_keys=on&_synchronous=NORMAL", path)
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite allows one writer; a single connection avoids SQLITE_BUSY entirely
	// and is plenty for a family-sized server.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func now() int64 { return time.Now().Unix() }

func (s *Store) CreateFamily(familyID string, admin Device) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var exists int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM devices WHERE id = ?`, admin.ID).Scan(&exists); err != nil {
		return err
	}
	if exists > 0 {
		return ErrConflict
	}
	t := now()
	if _, err := tx.Exec(`INSERT INTO families (id, epoch, admin_device, created_at) VALUES (?, 0, ?, ?)`, familyID, admin.ID, t); err != nil {
		return err
	}
	if _, err := tx.Exec(`INSERT INTO devices (id, family_id, sign_pub, box_pub, role, created_at, last_seen) VALUES (?, ?, ?, ?, 'admin', ?, ?)`,
		admin.ID, familyID, admin.SignPub, admin.BoxPub, t, t); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) GetFamily(id string) (Family, error) {
	var f Family
	err := s.db.QueryRow(`SELECT id, epoch, admin_device, created_at FROM families WHERE id = ?`, id).
		Scan(&f.ID, &f.Epoch, &f.AdminDevice, &f.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return f, ErrNotFound
	}
	return f, err
}

func (s *Store) GetDevice(id string) (Device, error) {
	var d Device
	var proof []byte
	err := s.db.QueryRow(`SELECT id, family_id, sign_pub, box_pub, role, join_proof, created_at, last_seen FROM devices WHERE id = ?`, id).
		Scan(&d.ID, &d.FamilyID, &d.SignPub, &d.BoxPub, &d.Role, &proof, &d.CreatedAt, &d.LastSeen)
	if errors.Is(err, sql.ErrNoRows) {
		return d, ErrNotFound
	}
	d.JoinProof = proof
	return d, err
}

func (s *Store) ListDevices(familyID string) ([]Device, error) {
	rows, err := s.db.Query(`SELECT id, family_id, sign_pub, box_pub, role, join_proof, created_at, last_seen FROM devices WHERE family_id = ? ORDER BY created_at`, familyID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		var d Device
		var proof []byte
		if err := rows.Scan(&d.ID, &d.FamilyID, &d.SignPub, &d.BoxPub, &d.Role, &proof, &d.CreatedAt, &d.LastSeen); err != nil {
			return nil, err
		}
		d.JoinProof = proof
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *Store) TouchDevice(id string) error {
	_, err := s.db.Exec(`UPDATE devices SET last_seen = ? WHERE id = ?`, now(), id)
	return err
}

func (s *Store) CreateInvite(tokenHash, familyID, createdBy string, expires int64) error {
	_, err := s.db.Exec(`INSERT INTO invites (token_hash, family_id, created_by, expires_at) VALUES (?, ?, ?, ?)`,
		tokenHash, familyID, createdBy, expires)
	return err
}

// RedeemInvite atomically consumes an invite and registers the new device.
func (s *Store) RedeemInvite(tokenHash, familyID string, dev Device, maxDevices int) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var invFamily string
	var expires int64
	var usedBy sql.NullString
	err = tx.QueryRow(`SELECT family_id, expires_at, used_by FROM invites WHERE token_hash = ?`, tokenHash).Scan(&invFamily, &expires, &usedBy)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if invFamily != familyID || usedBy.Valid || expires < now() {
		return ErrNotFound
	}
	var count, exists int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM devices WHERE family_id = ?`, familyID).Scan(&count); err != nil {
		return err
	}
	if count >= maxDevices {
		return ErrConflict
	}
	if err := tx.QueryRow(`SELECT COUNT(*) FROM devices WHERE id = ?`, dev.ID).Scan(&exists); err != nil {
		return err
	}
	if exists > 0 {
		return ErrConflict
	}
	t := now()
	if _, err := tx.Exec(`UPDATE invites SET used_by = ? WHERE token_hash = ?`, dev.ID, tokenHash); err != nil {
		return err
	}
	if _, err := tx.Exec(`INSERT INTO devices (id, family_id, sign_pub, box_pub, role, join_proof, created_at, last_seen) VALUES (?, ?, ?, ?, 'member', ?, ?, ?)`,
		dev.ID, familyID, dev.SignPub, dev.BoxPub, dev.JoinProof, t, t); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) RemoveDevice(familyID, deviceID string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`DELETE FROM devices WHERE id = ? AND family_id = ?`, deviceID, familyID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	if _, err := tx.Exec(`DELETE FROM envelopes WHERE family_id = ? AND device_id = ?`, familyID, deviceID); err != nil {
		return err
	}
	// A removed member's own (encrypted) data goes with them.
	if _, err := tx.Exec(`DELETE FROM messages WHERE family_id = ? AND sender = ?`, familyID, deviceID); err != nil {
		return err
	}
	return tx.Commit()
}

// DeleteFamily wipes a family and everything belonging to it.
func (s *Store) DeleteFamily(familyID string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{
		`DELETE FROM messages WHERE family_id = ?`,
		`DELETE FROM envelopes WHERE family_id = ?`,
		`DELETE FROM invites WHERE family_id = ?`,
		`DELETE FROM devices WHERE family_id = ?`,
		`DELETE FROM families WHERE id = ?`,
	} {
		if _, err := tx.Exec(q, familyID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// PutEnvelopes stores wrapped family keys for an epoch. If epoch is newer
// than the family's current epoch it becomes the current epoch.
func (s *Store) PutEnvelopes(familyID string, epoch int64, envs map[string][]byte) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var cur int64
	if err := tx.QueryRow(`SELECT epoch FROM families WHERE id = ?`, familyID).Scan(&cur); err != nil {
		return err
	}
	if epoch < cur {
		return ErrConflict
	}
	t := now()
	for dev, data := range envs {
		var fam string
		err := tx.QueryRow(`SELECT family_id FROM devices WHERE id = ?`, dev).Scan(&fam)
		if errors.Is(err, sql.ErrNoRows) || fam != familyID {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT OR REPLACE INTO envelopes (family_id, epoch, device_id, data, created_at) VALUES (?, ?, ?, ?, ?)`,
			familyID, epoch, dev, data, t); err != nil {
			return err
		}
	}
	if epoch > cur {
		if _, err := tx.Exec(`UPDATE families SET epoch = ? WHERE id = ?`, epoch, familyID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) GetEnvelopes(familyID, deviceID string) ([]Envelope, error) {
	rows, err := s.db.Query(`SELECT epoch, data FROM envelopes WHERE family_id = ? AND device_id = ? ORDER BY epoch`, familyID, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Envelope{}
	for rows.Next() {
		var e Envelope
		if err := rows.Scan(&e.Epoch, &e.Data); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// AddMessage appends a message. For sticky kinds, older messages of the same
// kind from the same sender are removed so only the latest survives.
// AddMessage stores a message. maxBytes > 0 caps how much one sender may keep stored
// (unexpired), so a misbehaving or stolen phone can't fill the server's disk.
func (s *Store) AddMessage(familyID, sender, kind string, blob []byte, ttl time.Duration, maxBytes int64) (int64, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	t := now()
	if maxBytes > 0 {
		var used int64
		if err := tx.QueryRow(`SELECT COALESCE(SUM(LENGTH(blob)), 0) FROM messages
			WHERE family_id = ? AND sender = ? AND (expires_at IS NULL OR expires_at > ?)`, familyID, sender, t).Scan(&used); err != nil {
			return 0, err
		}
		if used+int64(len(blob)) > maxBytes {
			return 0, ErrQuota
		}
	}
	var expires any
	if ttl > 0 {
		expires = t + int64(ttl/time.Second)
	}
	if stickyKinds[kind] {
		if _, err := tx.Exec(`DELETE FROM messages WHERE family_id = ? AND sender = ? AND kind = ?`, familyID, sender, kind); err != nil {
			return 0, err
		}
	}
	res, err := tx.Exec(`INSERT INTO messages (family_id, sender, kind, blob, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)`,
		familyID, sender, kind, blob, t, expires)
	if err != nil {
		return 0, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return 0, err
	}
	return id, tx.Commit()
}

func scanMessages(rows *sql.Rows) ([]Message, error) {
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.Sender, &m.Kind, &m.Blob, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) MessagesAfter(familyID string, after int64, limit int) ([]Message, error) {
	rows, err := s.db.Query(`SELECT id, sender, kind, blob, created_at FROM messages
		WHERE family_id = ? AND id > ? AND (expires_at IS NULL OR expires_at > ?)
		ORDER BY id LIMIT ?`, familyID, after, now(), limit)
	if err != nil {
		return nil, err
	}
	return scanMessages(rows)
}

// LatestState returns the newest non-expired message per (sender, kind) for
// all client kinds. This is what a device needs on first sync.
func (s *Store) LatestState(familyID string) ([]Message, int64, error) {
	// Read the cursor FIRST: a message posted while the state query runs is then
	// delivered again by the next poll (harmless) instead of being skipped forever.
	cursor, err := s.Cursor(familyID)
	if err != nil {
		return nil, 0, err
	}
	rows, err := s.db.Query(`SELECT m.id, m.sender, m.kind, m.blob, m.created_at FROM messages m
		JOIN (SELECT MAX(id) AS id FROM messages
		      WHERE family_id = ? AND kind != 'sys' AND (expires_at IS NULL OR expires_at > ?)
		      GROUP BY sender, kind) latest ON latest.id = m.id
		ORDER BY m.id`, familyID, now())
	if err != nil {
		return nil, 0, err
	}
	msgs, err := scanMessages(rows)
	if err != nil {
		return nil, 0, err
	}
	return msgs, cursor, nil
}

// History returns non-expired messages of one kind from one sender in a time range.
func (s *Store) History(familyID, sender, kind string, since int64, limit int) ([]Message, error) {
	rows, err := s.db.Query(`SELECT id, sender, kind, blob, created_at FROM messages
		WHERE family_id = ? AND sender = ? AND kind = ? AND created_at >= ? AND (expires_at IS NULL OR expires_at > ?)
		ORDER BY id LIMIT ?`, familyID, sender, kind, since, now(), limit)
	if err != nil {
		return nil, err
	}
	return scanMessages(rows)
}

func (s *Store) Cursor(familyID string) (int64, error) {
	var c sql.NullInt64
	err := s.db.QueryRow(`SELECT MAX(id) FROM messages WHERE family_id = ?`, familyID).Scan(&c)
	return c.Int64, err
}

// Cleanup removes expired messages and invites. Returns rows removed.
func (s *Store) Cleanup() (int64, error) {
	t := now()
	r1, err := s.db.Exec(`DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?`, t)
	if err != nil {
		return 0, err
	}
	r2, err := s.db.Exec(`DELETE FROM invites WHERE expires_at <= ?`, t-86400)
	if err != nil {
		return 0, err
	}
	n1, _ := r1.RowsAffected()
	n2, _ := r2.RowsAffected()
	return n1 + n2, nil
}
