package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/vmihailenco/msgpack/v5"
	"golang.org/x/crypto/bcrypt"
)

// ── helpers ────────────────────────────────────────────────────

func mkMsg(m map[string]interface{}) []byte {
	b, _ := msgpack.Marshal(m)
	return b
}

func newTestServer() *httptest.Server {
	return httptest.NewServer(newRouter())
}

func dial(t *testing.T, base string) *websocket.Conn {
	t.Helper()
	u, _ := url.Parse(base)
	u.Scheme = "ws"
	u.Path = "/ws"
	c, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	return c
}

func readUntil(t *testing.T, c *websocket.Conn, pred func(map[string]interface{}) bool) map[string]interface{} {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	for {
		_, b, err := c.ReadMessage()
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		m := map[string]interface{}{}
		if err := msgpack.Unmarshal(b, &m); err != nil {
			continue
		}
		if pred(m) {
			return m
		}
	}
}

// register_user auto-authenticates the connection; wait for the "registered" ack.
func regUser(t *testing.T, c *websocket.Conn, user, fp string) {
	c.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{
		"type": "register_user", "username": user, "password": "p", "fingerprint": fp,
	}))
	readUntil(t, c, func(m map[string]interface{}) bool { return m["type"] == "registered" })
}

func roomCount() int {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	return len(rooms)
}

// ── unit tests for the pure-function fixes ─────────────────────

func TestValidatePassword(t *testing.T) {
	if err := validatePassword(""); err == nil {
		t.Fatal("empty password should be rejected")
	}
	if err := validatePassword(strings.Repeat("x", 73)); err == nil {
		t.Fatal("password >72 bytes should be rejected")
	}
	if err := validatePassword(strings.Repeat("x", 72)); err != nil {
		t.Fatalf("72-byte password should be accepted, got %v", err)
	}
	if err := validatePassword("correct horse"); err != nil {
		t.Fatalf("normal password should be accepted, got %v", err)
	}
}

func TestHashPassword(t *testing.T) {
	hash, err := hashPassword("hunter2")
	if err != nil {
		t.Fatalf("hashPassword: %v", err)
	}
	if hash == "" {
		t.Fatal("hash must not be empty (the old code swallowed the error and stored \"\")")
	}
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte("hunter2")) != nil {
		t.Fatal("hash must verify against the original password")
	}
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte("wrong")) == nil {
		t.Fatal("hash must NOT verify against a different password")
	}
}

func TestSanitizeUsername(t *testing.T) {
	cases := map[string]string{
		"Alice":   "alice",
		"Bob_123": "bob_123",
		"a b/c!d": "abcd",
		"UPPER":   "upper",
	}
	for in, want := range cases {
		if got := sanitizeUsername(in); got != want {
			t.Errorf("sanitizeUsername(%q) = %q, want %q", in, got, want)
		}
	}
	if got := sanitizeUsername(strings.Repeat("a", 100)); len(got) != 32 {
		t.Errorf("username should be truncated to 32 chars, got %d", len(got))
	}
	if got := sanitizeUsername("---"); got != "" {
		t.Errorf("all-invalid username should be empty, got %q", got)
	}
}

// ── handleCall fail-fast: no room leaked ───────────────────────

// Calling your own fingerprint must error and must NOT create a room.
func TestSelfCallRejected(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()
	users = map[string]*User{}

	c := dial(t, srv.URL)
	defer c.Close()
	regUser(t, c, "self", "fpSelf")

	before := roomCount()
	c.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{"type": "call", "target": "fpSelf"}))
	errMsg := readUntil(t, c, func(m map[string]interface{}) bool { return m["type"] == "error" })
	if got := errMsg["message"]; got != "cannot call yourself" {
		t.Fatalf("expected 'cannot call yourself', got %v", got)
	}
	if roomCount() != before {
		t.Fatalf("self-call leaked a room: %d -> %d", before, roomCount())
	}
}

// Calling an unknown (offline, no-push) target must error and NOT leak a room.
func TestOfflineNoPushNoRoom(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()
	users = map[string]*User{}

	c := dial(t, srv.URL)
	defer c.Close()
	regUser(t, c, "caller", "fpCaller")

	before := roomCount()
	c.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{"type": "call", "target": "fpGhost"}))
	errMsg := readUntil(t, c, func(m map[string]interface{}) bool { return m["type"] == "error" })
	if got := errMsg["message"]; got != "target offline, no push token" {
		t.Fatalf("expected offline error, got %v", got)
	}
	if roomCount() != before {
		t.Fatalf("offline call leaked a room: %d -> %d", before, roomCount())
	}
}

// ── the data-race regression ───────────────────────────────────

// Two peers seated in the same room disconnect concurrently. leaveRoom()
// previously iterated room.clients without the lock, racing the sibling
// readPump's locked delete. Run under `go test -race` to catch a regression.
func TestLeaveRoomRace(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()
	users = map[string]*User{}

	for iter := 0; iter < 25; iter++ {
		a := dial(t, srv.URL)
		b := dial(t, srv.URL)
		regUser(t, a, "a"+itoa(iter), "fpA")
		regUser(t, b, "b"+itoa(iter), "fpB")
		a.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{"type": "call", "target": "fpB"}))
		incoming := readUntil(t, b, func(m map[string]interface{}) bool { return m["type"] == "incoming" })
		room, _ := incoming["room"].(string)
		b.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{"type": "accept", "room": room}))
		readUntil(t, a, func(m map[string]interface{}) bool { return m["type"] == "joined" })

		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); a.Close() }()
		go func() { defer wg.Done(); b.Close() }()
		wg.Wait()
	}
}

func itoa(i int) string {
	// avoid pulling strconv just for this
	if i == 0 {
		return "0"
	}
	var b []byte
	for i > 0 {
		b = append([]byte{byte('0' + i%10)}, b...)
		i /= 10
	}
	return string(b)
}

// A frame larger than maxMessageSize must be rejected and tear down the connection.
func TestReadLimitRejectsOversized(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()
	users = map[string]*User{}

	c := dial(t, srv.URL)
	defer c.Close()
	regUser(t, c, "big", "fpBig")

	huge := strings.Repeat("x", 70*1024) // > 64 KiB cap
	c.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{"type": "search_user", "query": huge}))

	// Server closes the connection on read-limit violation.
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := c.ReadMessage(); err == nil {
		t.Fatal("expected connection to be closed after an oversized frame")
	}
}

// Offer/ICE frames sent before the callee joins are queued for replay, but the
// queue is capped so a caller can't exhaust memory by spamming.
func TestRoomQueueCapped(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()
	users = map[string]*User{}

	a := dial(t, srv.URL)
	defer a.Close()
	b := dial(t, srv.URL) // never accepts — callee stays absent so frames queue
	defer b.Close()
	regUser(t, a, "qa", "fpQA")
	regUser(t, b, "qb", "fpQB")
	a.WriteMessage(websocket.BinaryMessage, mkMsg(map[string]interface{}{"type": "call", "target": "fpQB"}))
	created := readUntil(t, a, func(m map[string]interface{}) bool { return m["type"] == "created" })
	room, _ := created["room"].(string)

	ice := mkMsg(map[string]interface{}{"type": "ice", "candidate": "c", "room": room})
	for i := 0; i < 500; i++ {
		a.WriteMessage(websocket.BinaryMessage, ice)
	}

	// Poll until the server has processed the burst (queue caps at maxRoomQueue).
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		roomsMu.Lock()
		n := len(rooms[room].queue)
		roomsMu.Unlock()
		if n == maxRoomQueue {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	roomsMu.Lock()
	n := len(rooms[room].queue)
	roomsMu.Unlock()
	t.Fatalf("queue = %d, want %d (capped)", n, maxRoomQueue)
}

// An idle connection that never sends headers (slowloris) must be dropped by
// the server's ReadHeaderTimeout instead of being held open indefinitely.
func TestSlowlorisMitigated(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := &http.Server{Handler: newRouter(), ReadHeaderTimeout: 200 * time.Millisecond}
	go srv.Serve(ln)
	defer srv.Close()

	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	// Send nothing; the server must close us within ~ReadHeaderTimeout.
	if err := conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}
	start := time.Now()
	if _, err := conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected the idle connection to be closed by the server")
	}
	if d := time.Since(start); d > 1500*time.Millisecond {
		t.Fatalf("slowloris connection was held open for %v; ReadHeaderTimeout not enforced", d)
	}
}
