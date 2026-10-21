// ferry server: docks (paired devices), boarding codes, and file crossings.
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"

	_ "modernc.org/sqlite"
	"rsc.io/qr"
)

// Set at build time: -ldflags "-X main.version=v1.2.3".
var version = "dev"

// bundledWeb holds the web app when built with -tags bundle (see bundle.go).
var bundledWeb fs.FS

const (
	codeTTL     = 5 * time.Minute
	transferTTL = 24 * time.Hour
)

const schema = `
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  name TEXT NOT NULL,
  platform TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  save_label TEXT NOT NULL DEFAULT '',
  last_seen INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pair_codes (
  code TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS transfers (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  from_device TEXT NOT NULL,
  name TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS transfer_targets (
  transfer_id TEXT NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  PRIMARY KEY (transfer_id, device_id)
);`

type server struct {
	db      *sql.DB
	blobs   string
	maxSize int64
	hub     *hub
	claims  *limiter
}

type device struct {
	ID, Group, Name string
}

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	data := flag.String("data", "./data", "data directory (sqlite + blobs)")
	web := flag.String("web", "../web", "web app directory to serve (ignored when the UI is bundled)")
	maxMB := flag.Int64("max-mb", 2048, "max file size in MB")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println("ferry", version)
		return
	}

	s, err := newServer(*data, *maxMB<<20)
	if err != nil {
		log.Fatal(err)
	}
	go s.janitor()

	webFS := bundledWeb
	if webFS == nil {
		webFS = os.DirFS(*web)
	}
	mux := s.routes()
	mux.Handle("/", http.FileServerFS(webFS))
	log.Printf("ferry %s listening on %s", version, *addr)
	// No WriteTimeout: the event stream and big downloads are long-lived.
	srv := &http.Server{Addr: *addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

func newServer(dataDir string, maxSize int64) (*server, error) {
	blobs := filepath.Join(dataDir, "blobs")
	if err := os.MkdirAll(blobs, 0o700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", filepath.Join(dataDir, "ferry.db")+"?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1) // ponytail: one writer connection, fine for a personal fleet; pool + Postgres if it grows
	if _, err := db.Exec(schema); err != nil {
		return nil, err
	}
	return &server{db: db, blobs: blobs, maxSize: maxSize, hub: newHub(), claims: newLimiter(10, 10*time.Minute)}, nil
}

func (s *server) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/fleet", s.createFleet)
	mux.HandleFunc("POST /api/dock/claim", s.claimCode)
	mux.HandleFunc("GET /api/qr", s.qrCode)
	mux.HandleFunc("POST /api/dock/code", s.authed(s.newCode))
	mux.HandleFunc("GET /api/docks", s.authed(s.listDocks))
	mux.HandleFunc("PATCH /api/docks/me", s.authed(s.updateMe))
	mux.HandleFunc("DELETE /api/docks/{id}", s.authed(s.undock))
	mux.HandleFunc("POST /api/crossings", s.authed(s.upload))
	mux.HandleFunc("GET /api/crossings", s.authed(s.listCrossings))
	mux.HandleFunc("GET /api/crossings/{id}/file", s.authed(s.download))
	mux.HandleFunc("POST /api/crossings/{id}/ack", s.authed(s.ack))
	mux.HandleFunc("GET /api/events", s.authed(s.events))
	return mux
}

// ---- docking ----

type dockReq struct {
	Name     string `json:"name"`
	Platform string `json:"platform"`
	Code     string `json:"code"`
}

// createFleet starts a new fleet with the calling device as its first dock.
func (s *server) createFleet(w http.ResponseWriter, r *http.Request) {
	var req dockReq
	if !readJSON(w, r, &req) {
		return
	}
	s.addDevice(w, newID(), req)
}

func (s *server) claimCode(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if !s.claims.allow(ip) {
		http.Error(w, "too many attempts, try again later", http.StatusTooManyRequests)
		return
	}
	var req dockReq
	if !readJSON(w, r, &req) {
		return
	}
	var group string
	err := s.db.QueryRow(`DELETE FROM pair_codes WHERE code = ? AND expires_at > ? RETURNING group_id`,
		strings.TrimSpace(strings.ReplaceAll(req.Code, " ", "")), time.Now().Unix()).Scan(&group)
	if err != nil {
		s.claims.fail(ip)
		http.Error(w, "boarding code is wrong or expired", http.StatusNotFound)
		return
	}
	s.addDevice(w, group, req)
	s.hub.broadcast(group, event{Type: "docks"})
}

func (s *server) addDevice(w http.ResponseWriter, group string, req dockReq) {
	name := cleanText(req.Name, 60)
	if name == "" {
		name = "Unnamed device"
	}
	platform := "web"
	if req.Platform == "android" {
		platform = "android"
	}
	id, token := newID(), newToken()
	_, err := s.db.Exec(`INSERT INTO devices (id, group_id, name, platform, token_hash, last_seen) VALUES (?, ?, ?, ?, ?, ?)`,
		id, group, name, platform, hashToken(token), time.Now().Unix())
	if err != nil {
		httpErr(w, err)
		return
	}
	writeJSON(w, map[string]string{"device_id": id, "token": token})
}

func (s *server) newCode(w http.ResponseWriter, r *http.Request, d device) {
	exp := time.Now().Add(codeTTL)
	for range 5 {
		n, _ := rand.Int(rand.Reader, big.NewInt(1_000_000))
		code := fmt.Sprintf("%06d", n.Int64())
		_, err := s.db.Exec(`INSERT INTO pair_codes (code, group_id, expires_at) VALUES (?, ?, ?)`, code, d.Group, exp.Unix())
		if err == nil {
			writeJSON(w, map[string]any{"code": code, "expires_at": exp.Unix()})
			return
		}
	}
	http.Error(w, "could not issue a code, retry", http.StatusServiceUnavailable)
}

// qrCode renders a boarding QR for a 6-digit code. It only encodes what it is
// given, so it needs no auth.
func (s *server) qrCode(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("code")
	if len(code) != 6 || strings.Trim(code, "0123456789") != "" {
		http.Error(w, "bad code", http.StatusBadRequest)
		return
	}
	payload := "ferry://dock?" + url.Values{"server": {origin(r)}, "code": {code}}.Encode()
	c, err := qr.Encode(payload, qr.M)
	if err != nil {
		httpErr(w, err)
		return
	}
	c.Scale = 8
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(c.PNG())
}

type dockJSON struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Platform  string `json:"platform"`
	Online    bool   `json:"online"`
	LastSeen  int64  `json:"last_seen"`
	SaveLabel string `json:"save_label"`
	Me        bool   `json:"me"`
}

func (s *server) listDocks(w http.ResponseWriter, r *http.Request, d device) {
	rows, err := s.db.Query(`SELECT id, name, platform, last_seen, save_label FROM devices WHERE group_id = ? ORDER BY name`, d.Group)
	if err != nil {
		httpErr(w, err)
		return
	}
	defer rows.Close()
	out := []dockJSON{}
	for rows.Next() {
		var x dockJSON
		if err := rows.Scan(&x.ID, &x.Name, &x.Platform, &x.LastSeen, &x.SaveLabel); err != nil {
			httpErr(w, err)
			return
		}
		x.Me = x.ID == d.ID
		x.Online = x.Me || s.hub.online(x.ID)
		out = append(out, x)
	}
	writeJSON(w, out)
}

func (s *server) updateMe(w http.ResponseWriter, r *http.Request, d device) {
	var req struct {
		Name      *string `json:"name"`
		SaveLabel *string `json:"save_label"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Name != nil {
		if n := cleanText(*req.Name, 60); n != "" {
			s.db.Exec(`UPDATE devices SET name = ? WHERE id = ?`, n, d.ID)
		}
	}
	if req.SaveLabel != nil {
		s.db.Exec(`UPDATE devices SET save_label = ? WHERE id = ?`, cleanText(*req.SaveLabel, 120), d.ID)
	}
	s.hub.broadcast(d.Group, event{Type: "docks"})
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) undock(w http.ResponseWriter, r *http.Request, d device) {
	id := r.PathValue("id")
	res, err := s.db.Exec(`DELETE FROM devices WHERE id = ? AND group_id = ?`, id, d.Group)
	if err != nil {
		httpErr(w, err)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		http.NotFound(w, r)
		return
	}
	s.db.Exec(`DELETE FROM transfer_targets WHERE device_id = ?`, id)
	s.hub.send(id, event{Type: "undocked"})
	s.hub.broadcast(d.Group, event{Type: "docks"})
	w.WriteHeader(http.StatusNoContent)
}

// ---- crossings ----

// upload streams the request body to disk: POST /api/crossings?name=a.jpg&to=id1,id2
func (s *server) upload(w http.ResponseWriter, r *http.Request, d device) {
	q := r.URL.Query()
	name := cleanFileName(q.Get("name"))
	targets, err := s.validTargets(d, strings.Split(q.Get("to"), ","))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if r.ContentLength > s.maxSize {
		http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
		return
	}

	id := newID()
	tmp, err := os.CreateTemp(s.blobs, "up-*")
	if err != nil {
		httpErr(w, err)
		return
	}
	defer os.Remove(tmp.Name()) // no-op after the rename below
	size, err := io.Copy(tmp, http.MaxBytesReader(w, r.Body, s.maxSize))
	tmp.Close()
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "upload interrupted", http.StatusBadRequest)
		return
	}
	if err := os.Rename(tmp.Name(), s.blobPath(id)); err != nil {
		httpErr(w, err)
		return
	}

	now := time.Now()
	tx, err := s.db.Begin()
	if err != nil {
		httpErr(w, err)
		return
	}
	defer tx.Rollback()
	tx.Exec(`INSERT INTO transfers (id, group_id, from_device, name, size, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, d.Group, d.ID, name, size, now.Unix(), now.Add(transferTTL).Unix())
	for _, t := range targets {
		tx.Exec(`INSERT INTO transfer_targets (transfer_id, device_id) VALUES (?, ?)`, id, t)
	}
	if err := tx.Commit(); err != nil {
		os.Remove(s.blobPath(id))
		httpErr(w, err)
		return
	}
	for _, t := range targets {
		s.hub.send(t, event{Type: "arrival", ID: id})
	}
	writeJSON(w, map[string]any{"id": id, "size": size})
}

func (s *server) validTargets(d device, ids []string) ([]string, error) {
	seen := map[string]bool{}
	var out []string
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id == "" || id == d.ID || seen[id] {
			continue
		}
		var ok bool
		s.db.QueryRow(`SELECT 1 FROM devices WHERE id = ? AND group_id = ?`, id, d.Group).Scan(&ok)
		if !ok {
			return nil, fmt.Errorf("unknown destination %q", id)
		}
		seen[id] = true
		out = append(out, id)
	}
	if len(out) == 0 {
		return nil, errors.New("pick at least one destination")
	}
	return out, nil
}

type crossingJSON struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	Size      int64             `json:"size"`
	CreatedAt int64             `json:"created_at"`
	From      string            `json:"from,omitempty"`
	Status    string            `json:"status,omitempty"`
	Targets   map[string]string `json:"targets,omitempty"` // device name -> status
}

// listCrossings returns arrivals (?box=in, default) or departures (?box=out).
func (s *server) listCrossings(w http.ResponseWriter, r *http.Request, d device) {
	out := []crossingJSON{}
	if r.URL.Query().Get("box") == "out" {
		rows, err := s.db.Query(`
			SELECT t.id, t.name, t.size, t.created_at, COALESCE(dv.name, 'Undocked device'), tt.status
			FROM transfers t
			JOIN transfer_targets tt ON tt.transfer_id = t.id
			LEFT JOIN devices dv ON dv.id = tt.device_id
			WHERE t.from_device = ? ORDER BY t.created_at DESC LIMIT 500`, d.ID)
		if err != nil {
			httpErr(w, err)
			return
		}
		defer rows.Close()
		idx := map[string]int{}
		for rows.Next() {
			var c crossingJSON
			var target, status string
			rows.Scan(&c.ID, &c.Name, &c.Size, &c.CreatedAt, &target, &status)
			i, ok := idx[c.ID]
			if !ok {
				c.Targets = map[string]string{}
				out = append(out, c)
				i = len(out) - 1
				idx[c.ID] = i
			}
			out[i].Targets[target] = status
		}
		writeJSON(w, out)
		return
	}
	rows, err := s.db.Query(`
		SELECT t.id, t.name, t.size, t.created_at, COALESCE(dv.name, 'Undocked device'), tt.status
		FROM transfer_targets tt
		JOIN transfers t ON t.id = tt.transfer_id
		LEFT JOIN devices dv ON dv.id = t.from_device
		WHERE tt.device_id = ? ORDER BY t.created_at DESC LIMIT 200`, d.ID)
	if err != nil {
		httpErr(w, err)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var c crossingJSON
		rows.Scan(&c.ID, &c.Name, &c.Size, &c.CreatedAt, &c.From, &c.Status)
		out = append(out, c)
	}
	writeJSON(w, out)
}

func (s *server) download(w http.ResponseWriter, r *http.Request, d device) {
	id := r.PathValue("id")
	var name string
	err := s.db.QueryRow(`SELECT t.name FROM transfers t JOIN transfer_targets tt ON tt.transfer_id = t.id
		WHERE t.id = ? AND tt.device_id = ?`, id, d.ID).Scan(&name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(s.blobPath(id))
	if err != nil {
		http.Error(w, "this crossing has already landed everywhere or expired", http.StatusGone)
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(name))
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func (s *server) ack(w http.ResponseWriter, r *http.Request, d device) {
	id := r.PathValue("id")
	res, err := s.db.Exec(`UPDATE transfer_targets SET status = 'arrived' WHERE transfer_id = ? AND device_id = ?`, id, d.ID)
	if err != nil {
		httpErr(w, err)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		http.NotFound(w, r)
		return
	}
	var pending int
	s.db.QueryRow(`SELECT COUNT(*) FROM transfer_targets WHERE transfer_id = ? AND status = 'pending'`, id).Scan(&pending)
	if pending == 0 {
		os.Remove(s.blobPath(id)) // everyone has it; the row stays as history until expiry
	}
	var from string
	if s.db.QueryRow(`SELECT from_device FROM transfers WHERE id = ?`, id).Scan(&from) == nil {
		s.hub.send(from, event{Type: "departure", ID: id})
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) blobPath(id string) string { return filepath.Join(s.blobs, id) }

// janitor drops expired crossings and boarding codes.
func (s *server) janitor() {
	for {
		s.sweep(time.Now())
		time.Sleep(10 * time.Minute)
	}
}

func (s *server) sweep(now time.Time) {
	rows, err := s.db.Query(`DELETE FROM transfers WHERE expires_at <= ? RETURNING id`, now.Unix())
	if err == nil {
		var ids []string
		for rows.Next() {
			var id string
			rows.Scan(&id)
			ids = append(ids, id)
		}
		rows.Close()
		for _, id := range ids {
			os.Remove(s.blobPath(id))
		}
	}
	s.db.Exec(`DELETE FROM pair_codes WHERE expires_at <= ?`, now.Unix())
}

// ---- live events (Server-Sent Events) ----

type event struct {
	Type string `json:"type"`
	ID   string `json:"id,omitempty"`
}

func (s *server) events(w http.ResponseWriter, r *http.Request, d device) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	ch := s.hub.sub(d)
	defer s.hub.unsub(d, ch)
	fmt.Fprint(w, ": aboard\n\n")
	fl.Flush()
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case e := <-ch:
			b, _ := json.Marshal(e)
			fmt.Fprintf(w, "data: %s\n\n", b)
			fl.Flush()
			if e.Type == "undocked" {
				return
			}
		case <-ping.C:
			fmt.Fprint(w, ": ping\n\n")
			fl.Flush()
		}
	}
}

type hub struct {
	mu    sync.Mutex
	subs  map[string]map[chan event]bool // device id -> streams
	group map[string]string              // device id -> group id
}

func newHub() *hub {
	return &hub{subs: map[string]map[chan event]bool{}, group: map[string]string{}}
}

func (h *hub) sub(d device) chan event {
	ch := make(chan event, 16)
	h.mu.Lock()
	first := len(h.subs[d.ID]) == 0
	if first {
		h.subs[d.ID] = map[chan event]bool{}
	}
	h.subs[d.ID][ch] = true
	h.group[d.ID] = d.Group
	h.mu.Unlock()
	if first {
		h.broadcast(d.Group, event{Type: "docks"})
	}
	return ch
}

func (h *hub) unsub(d device, ch chan event) {
	h.mu.Lock()
	delete(h.subs[d.ID], ch)
	last := len(h.subs[d.ID]) == 0
	if last {
		delete(h.subs, d.ID)
		delete(h.group, d.ID)
	}
	h.mu.Unlock()
	if last {
		h.broadcast(d.Group, event{Type: "docks"})
	}
}

func (h *hub) online(id string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.subs[id]) > 0
}

// send never blocks: a client with a full buffer refetches on its next event anyway.
func (h *hub) send(id string, e event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[id] {
		select {
		case ch <- e:
		default:
		}
	}
}

// ponytail: scans every connected device, fine for personal fleets; index by group if many groups share a server
func (h *hub) broadcast(group string, e event) {
	h.mu.Lock()
	var ids []string
	for id, g := range h.group {
		if g == group {
			ids = append(ids, id)
		}
	}
	h.mu.Unlock()
	for _, id := range ids {
		h.send(id, e)
	}
}

// ---- auth + helpers ----

// authed resolves the device from "Authorization: Bearer <token>", or ?token=
// for EventSource and plain download links, which cannot set headers.
func (s *server) authed(fn func(http.ResponseWriter, *http.Request, device)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" {
			tok = r.URL.Query().Get("token")
		}
		var d device
		err := s.db.QueryRow(`UPDATE devices SET last_seen = ? WHERE token_hash = ? RETURNING id, group_id, name`,
			time.Now().Unix(), hashToken(tok)).Scan(&d.ID, &d.Group, &d.Name)
		if tok == "" || err != nil {
			http.Error(w, "not docked", http.StatusUnauthorized)
			return
		}
		fn(w, r, d)
	}
}

type limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	fails  map[string][]time.Time
}

func newLimiter(max int, window time.Duration) *limiter {
	return &limiter{max: max, window: window, fails: map[string][]time.Time{}}
}

func (l *limiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	cut := time.Now().Add(-l.window)
	recent := l.fails[key][:0]
	for _, t := range l.fails[key] {
		if t.After(cut) {
			recent = append(recent, t)
		}
	}
	l.fails[key] = recent
	return len(recent) < l.max
}

func (l *limiter) fail(key string) {
	l.mu.Lock()
	l.fails[key] = append(l.fails[key], time.Now())
	l.mu.Unlock()
}

// ponytail: RemoteAddr only; trust X-Forwarded-For once behind a known proxy
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func origin(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func newID() string    { return randHex(16) }
func newToken() string { return randHex(32) }

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func hashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}

func cleanText(s string, max int) string {
	s = strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, s))
	if r := []rune(s); len(r) > max {
		s = string(r[:max])
	}
	return s
}

func cleanFileName(s string) string {
	s = cleanText(filepath.Base(strings.ReplaceAll(s, "\\", "/")), 200)
	if s == "" || s == "." || s == "/" || s == ".." {
		return "file"
	}
	return s
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(v); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func httpErr(w http.ResponseWriter, err error) {
	log.Print(err)
	http.Error(w, "server error", http.StatusInternalServerError)
}
