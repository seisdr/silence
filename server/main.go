package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/vmihailenco/msgpack/v5"
	"golang.org/x/crypto/bcrypt"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

type Client struct {
	conn          *websocket.Conn
	fingerprint   string
	fcmToken      string // Android push
	apnsToken     string // iOS push
	username      string
	authenticated bool
	room          *Room
	send          chan []byte
}

type Room struct {
	id      string
	clients map[*Client]bool
	queue   [][]byte
	mu      sync.Mutex
}

type User struct {
	Username     string `json:"username"`
	PasswordHash string `json:"password_hash"`
	Fingerprint  string `json:"fingerprint"`
	FcmToken     string `json:"fcm_token"`
	ApnsToken    string `json:"apns_token,omitempty"`
}

var (
	users   = map[string]*User{}
	usersMu sync.RWMutex
)

const usersFile = "users.json"

func loadUsers() {
	data, err := os.ReadFile(usersFile)
	if err != nil { return }
	var list []*User
	if err := json.Unmarshal(data, &list); err != nil { return }
	usersMu.Lock()
	for _, u := range list { users[u.Username] = u }
	usersMu.Unlock()
	log.Printf("loaded %d users", len(list))
}

func saveUsers() {
	usersMu.RLock()
	list := make([]*User, 0, len(users))
	for _, u := range users { list = append(list, u) }
	usersMu.RUnlock()
	data, _ := json.MarshalIndent(list, "", "  ")
	os.WriteFile(usersFile, data, 0600)
}

var (
	rooms             = map[string]*Room{}
	roomsMu           sync.Mutex
	registeredClients = map[string]*Client{}
	clientsMu         sync.RWMutex
	fcmServerKey      = os.Getenv("FCM_SERVER_KEY")

	// APNs
	apnsKeyID  = os.Getenv("APNS_KEY_ID")
	apnsTeamID = os.Getenv("APNS_TEAM_ID")
	apnsKeyPath = os.Getenv("APNS_KEY_PATH")
	apnsTopic  = os.Getenv("APNS_TOPIC")
	apnsClient *http.Client
	apnsJWT    string
	apnsJWTExp time.Time
	apnsMu     sync.Mutex
)

func main() {
	loadUsers()
	initAPNs()
	http.HandleFunc("/ws", handleWebSocket)
	http.HandleFunc("/health", handleHealth)
	http.HandleFunc("/api/users", handleUsersAPI)

	port := os.Getenv("PORT")
	if port == "" { port = "8080" }
	addr := ":" + port
	log.Printf("Silence signaling relay listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, nil))
}

func handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil { log.Printf("ws upgrade error: %v", err); return }
	log.Printf("ws connected: %s", r.RemoteAddr)
	client := &Client{conn: conn, send: make(chan []byte, 16)}
	go client.writePump()
	client.readPump()
	log.Printf("ws disconnected: %s", r.RemoteAddr)
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	clientsMu.RLock()
	online := len(registeredClients)
	clientsMu.RUnlock()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	data, _ := json.Marshal(map[string]interface{}{"status": "ok", "clients": online})
	w.Write(data)
}

func handleUsersAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	switch r.Method {
	case "GET":
		usersMu.RLock()
		list := make([]map[string]string, 0, len(users))
		for _, u := range users {
			list = append(list, map[string]string{
				"username": u.Username, "fingerprint": u.Fingerprint,
				"has_fcm": fmt.Sprintf("%v", u.FcmToken != ""),
				"has_apns": fmt.Sprintf("%v", u.ApnsToken != ""),
			})
		}
		usersMu.RUnlock()
		json.NewEncoder(w).Encode(map[string]interface{}{"users": list, "count": len(list)})
	case "POST":
		var req struct {
			Action   string `json:"action"`
			Username string `json:"username"`
			Password string `json:"password"`
		}
		if json.NewDecoder(r.Body).Decode(&req) != nil { w.WriteHeader(400); return }
		switch req.Action {
		case "login":
			usersMu.RLock(); u, ok := users[req.Username]; usersMu.RUnlock()
			if !ok || bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(req.Password)) != nil {
				w.WriteHeader(401); json.NewEncoder(w).Encode(map[string]string{"error": "invalid credentials"}); return
			}
			json.NewEncoder(w).Encode(map[string]string{"status": "ok", "username": u.Username, "fingerprint": u.Fingerprint})
		case "register":
			usersMu.Lock()
			if _, exists := users[req.Username]; exists { usersMu.Unlock(); w.WriteHeader(409); return }
			hash, _ := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
			users[req.Username] = &User{Username: req.Username, PasswordHash: string(hash)}
			usersMu.Unlock()
			go saveUsers()
			json.NewEncoder(w).Encode(map[string]string{"status": "created", "username": req.Username})
		default:
			w.WriteHeader(400)
		}
	default:
		w.WriteHeader(405)
	}
}

func (c *Client) readPump() {
	defer func() { c.unregister(); c.leaveRoom(); c.conn.Close() }()
	for {
		msgType, msg, err := c.conn.ReadMessage()
		if err != nil { break }
		if msgType != websocket.BinaryMessage && msgType != websocket.TextMessage { continue }
		var raw map[string]interface{}
		if err := msgpack.Unmarshal(msg, &raw); err != nil {
			log.Printf("decode error: %v — raw[%d]: %x", err, len(msg), msg[:min(64, len(msg))]); continue
		}
		log.Printf("← msg: %v", raw)
		typ, _ := raw["type"].(string)
		switch typ {
		case "register":
			fp, _ := raw["fingerprint"].(string)
			fcmTok, _ := raw["fcm_token"].(string)
			apnsTok, _ := raw["apns_token"].(string)
			c.handleRegister(fp, fcmTok, apnsTok)
		case "register_user":
			c.handleRegisterUser(raw)
		case "login":
			username, _ := raw["username"].(string)
			password, _ := raw["password"].(string)
			c.handleLogin(username, password)
		case "call_user":
			targetUser, _ := raw["username"].(string)
			c.handleCallUser(targetUser)
		case "search_user":
			query, _ := raw["query"].(string)
			c.handleSearchUser(query)
		case "call":
			c.handleCallRaw(raw)
		case "accept":
			roomID, _ := raw["room"].(string)
			c.handleAccept(roomID)
		case "offer", "answer", "ice", "hangup":
			c.forwardToPeer(msg)
		default:
			c.sendError("unknown type: " + typ)
		}
	}
}

func (c *Client) writePump() {
	for msg := range c.send {
		if err := c.conn.WriteMessage(websocket.BinaryMessage, msg); err != nil { return }
	}
}

// ── Registration ──────────────────────────────────────────────

func (c *Client) handleRegister(fingerprint, fcmToken, apnsToken string) {
	if fingerprint == "" { c.sendError("fingerprint required"); return }
	c.fingerprint = fingerprint
	c.fcmToken = fcmToken
	c.apnsToken = apnsToken

	if c.authenticated && c.username != "" {
		usersMu.Lock()
		if u, ok := users[c.username]; ok {
			u.Fingerprint = fingerprint
			u.FcmToken = fcmToken
			u.ApnsToken = apnsToken
		}
		usersMu.Unlock()
		go saveUsers()
	}

	clientsMu.Lock()
	registeredClients[fingerprint] = c
	clientsMu.Unlock()

	c.sendMsg(map[string]string{"type": "registered"})
	log.Printf("registered: %s", trunc(fingerprint))
	c.deliverPendingCall()
}

func (c *Client) unregister() {
	if c.fingerprint == "" { return }
	clientsMu.Lock()
	delete(registeredClients, c.fingerprint)
	clientsMu.Unlock()
}

// ── Account system ───────────────────────────────────────────

func (c *Client) handleRegisterUser(raw map[string]interface{}) {
	username, _ := raw["username"].(string)
	password, _ := raw["password"].(string)
	fingerprint, _ := raw["fingerprint"].(string)
	fcmToken, _ := raw["fcm_token"].(string)
	apnsToken, _ := raw["apns_token"].(string)

	if username == "" || password == "" { c.sendError("username and password required"); return }
	username = sanitizeUsername(username)

	usersMu.Lock()
	if _, exists := users[username]; exists { usersMu.Unlock(); c.sendError("username taken"); return }
	hash, _ := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	users[username] = &User{Username: username, PasswordHash: string(hash), Fingerprint: fingerprint, FcmToken: fcmToken, ApnsToken: apnsToken}
	usersMu.Unlock()

	c.username = username; c.authenticated = true
	c.fingerprint = fingerprint; c.fcmToken = fcmToken; c.apnsToken = apnsToken

	clientsMu.Lock()
	registeredClients[fingerprint] = c
	clientsMu.Unlock()

	go saveUsers()
	c.sendMsg(map[string]string{"type": "registered", "username": username})
	log.Printf("new user: %s", username)
}

func (c *Client) handleLogin(username, password string) {
	if username == "" || password == "" { c.sendError("username and password required"); return }
	username = sanitizeUsername(username)
	usersMu.RLock(); u, ok := users[username]; usersMu.RUnlock()
	if !ok || bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil {
		c.sendError("invalid credentials"); return
	}
	c.username = username; c.authenticated = true
	c.sendMsg(map[string]interface{}{"type": "logged_in", "username": username})
	log.Printf("login: %s", username)
}

func (c *Client) handleCallUser(targetUsername string) {
	if !c.authenticated { c.sendError("login required"); return }
	usersMu.RLock(); u, ok := users[targetUsername]; usersMu.RUnlock()
	if !ok || u.Fingerprint == "" { c.sendError("user not found or offline"); return }
	c.handleCall(u.Fingerprint)
}

func (c *Client) handleCallRaw(raw map[string]interface{}) {
	if !c.authenticated { c.sendError("login required"); return }
	target, _ := raw["target"].(string)
	if target == "" { c.sendError("target required"); return }
	c.handleCall(target)
}

func (c *Client) handleSearchUser(query string) {
	if query == "" { c.sendError("query required"); return }
	query = sanitizeUsername(query)
	usersMu.RLock()
	var matches []string
	for name := range users {
		if len(name) >= len(query) && name[:len(query)] == query { matches = append(matches, name) }
		if len(matches) >= 10 { break }
	}
	usersMu.RUnlock()
	c.sendMsg(map[string]interface{}{"type": "search_results", "results": matches})
}

func sanitizeUsername(s string) string {
	result := make([]byte, 0, len(s))
	for i := 0; i < len(s) && len(result) < 32; i++ {
		c := s[i]
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' { result = append(result, c)
		} else if c >= 'A' && c <= 'Z' { result = append(result, c+32) }
	}
	return string(result)
}

// ── Outbound call ─────────────────────────────────────────────

func (c *Client) handleCall(targetFingerprint string) {
	if c.fingerprint == "" { c.sendError("register first"); return }
	clientsMu.RLock()
	target, online := registeredClients[targetFingerprint]
	clientsMu.RUnlock()

	roomID := uuid.New().String()[:8]
	roomsMu.Lock()
	room := &Room{id: roomID, clients: map[*Client]bool{}}
	rooms[roomID] = room
	roomsMu.Unlock()

	room.mu.Lock()
	room.clients[c] = true; c.room = room
	room.mu.Unlock()

	c.sendMsg(map[string]string{"type": "created", "room": roomID})

	if online && target != c {
		target.sendMsg(map[string]string{"type": "incoming", "from": c.fingerprint, "room": roomID})
		log.Printf("call ws: %s → %s (room %s)", trunc(c.fingerprint), trunc(targetFingerprint), roomID)
	} else if target == c {
		c.sendError("cannot call yourself")
	} else {
		// Try FCM (Android), then APNs (iOS)
		fcmTok, apnsTok := c.lookupPushTokens(targetFingerprint)
		if fcmTok != "" {
			go sendFcmPush(fcmTok, roomID, c.fingerprint)
			log.Printf("call fcm: %s → %s (room %s)", trunc(c.fingerprint), trunc(targetFingerprint), roomID)
		} else if apnsTok != "" {
			go sendApnsPush(apnsTok, roomID, c.fingerprint)
			log.Printf("call apns: %s → %s (room %s)", trunc(c.fingerprint), trunc(targetFingerprint), roomID)
		} else {
			log.Printf("call failed: %s has no push token", trunc(targetFingerprint))
			c.sendError("target offline, no push token")
		}
	}
}

func (c *Client) handleAccept(roomID string) {
	roomsMu.Lock(); room, ok := rooms[roomID]; roomsMu.Unlock()
	if !ok { c.sendError("room not found"); return }

	room.mu.Lock()
	room.clients[c] = true; c.room = room
	queue := room.queue; room.queue = nil
	room.mu.Unlock()

	for _, msg := range queue { select { case c.send <- msg: default: {} } }
	c.sendMsg(map[string]string{"type": "joined", "room": roomID})

	room.mu.Lock()
	for peer := range room.clients {
		if peer != c { peer.sendMsg(map[string]string{"type": "joined", "room": roomID}) }
	}
	room.mu.Unlock()
}

func (c *Client) forwardToPeer(msg []byte) {
	if c.room == nil { c.sendError("not in a room"); return }
	c.room.mu.Lock(); defer c.room.mu.Unlock()
	if len(c.room.clients) < 2 { c.room.queue = append(c.room.queue, msg); return }
	for peer := range c.room.clients {
		if peer != c { select { case peer.send <- msg: default: {} } }
	}
}

// ── Push notifications ────────────────────────────────────────

func (c *Client) lookupPushTokens(fp string) (fcm, apns string) {
	clientsMu.RLock(); defer clientsMu.RUnlock()
	if client, ok := registeredClients[fp]; ok {
		return client.fcmToken, client.apnsToken
	}
	// Fallback: look up in user store
	usersMu.RLock(); defer usersMu.RUnlock()
	for _, u := range users {
		if u.Fingerprint == fp { return u.FcmToken, u.ApnsToken }
	}
	return "", ""
}

func (c *Client) deliverPendingCall() {}

func sendFcmPush(token, roomID, fromFingerprint string) {
	if fcmServerKey == "" { log.Printf("FCM_SERVER_KEY not set"); return }
	payload := fmt.Sprintf(`{"to":"%s","priority":"high","data":{"type":"incoming_call","room":"%s","from":"%s"}}`, token, roomID, fromFingerprint)
	req, _ := http.NewRequest("POST", "https://fcm.googleapis.com/fcm/send", bytes.NewBufferString(payload))
	req.Header.Set("Authorization", "key="+fcmServerKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil { log.Printf("fcm: %v", err); return }
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	log.Printf("fcm: %d — %s", resp.StatusCode, string(body))
}

func initAPNs() {
	if apnsKeyID == "" || apnsTeamID == "" || apnsKeyPath == "" { return }
	apnsClient = &http.Client{}
	log.Printf("APNs configured: topic=%s", apnsTopic)
}

func getAPNsJWT() (string, error) {
	apnsMu.Lock(); defer apnsMu.Unlock()
	if apnsJWT != "" && time.Now().Before(apnsJWTExp) { return apnsJWT, nil }

	keyBytes, err := os.ReadFile(apnsKeyPath)
	if err != nil { return "", err }
	block, _ := pem.Decode(keyBytes)
	if block == nil { return "", fmt.Errorf("invalid pem") }
	privKey, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil { return "", err }
	ecKey, ok := privKey.(*ecdsa.PrivateKey)
	if !ok { return "", fmt.Errorf("not ec key") }

	now := time.Now()
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"ES256","kid":"` + apnsKeyID + `"}`))
	claims := base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"iss":"%s","iat":%d}`, apnsTeamID, now.Unix())))
	signingInput := header + "." + claims
	hash := sha256.Sum256([]byte(signingInput))
	r, s, err := ecdsa.Sign(rand.Reader, ecKey, hash[:])
	if err != nil { return "", err }

	sig := make([]byte, 64)
	r.FillBytes(sig[:32]); s.FillBytes(sig[32:])
	apnsJWT = signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
	apnsJWTExp = now.Add(50 * time.Minute)
	return apnsJWT, nil
}

func sendApnsPush(token, roomID, fromFingerprint string) {
	if apnsClient == nil { return }
	jwt, err := getAPNsJWT()
	if err != nil { log.Printf("apns jwt: %v", err); return }

	payload := fmt.Sprintf(`{"aps":{"alert":{"title":"Incoming Call","body":"Call from %s"},"sound":"default"},"type":"incoming_call","room":"%s","from":"%s"}`,
		trunc(fromFingerprint), roomID, fromFingerprint)

	req, _ := http.NewRequest("POST", "https://api.push.apple.com/3/device/"+token, strings.NewReader(payload))
	req.Header.Set("authorization", "bearer "+jwt)
	req.Header.Set("apns-topic", apnsTopic)
	req.Header.Set("apns-push-type", "alert")
	req.Header.Set("apns-priority", "10")

	resp, err := apnsClient.Do(req)
	if err != nil { log.Printf("apns: %v", err); return }
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	log.Printf("apns: %d — %s", resp.StatusCode, string(body))
}

// ── Cleanup ───────────────────────────────────────────────────

func (c *Client) leaveRoom() {
	if c.room == nil { return }
	room := c.room
	room.mu.Lock(); delete(room.clients, c); remaining := len(room.clients); room.mu.Unlock()
	c.room = nil
	for peer := range room.clients { peer.sendMsg(map[string]string{"type": "hangup", "room": room.id}) }
	if remaining == 0 {
		roomsMu.Lock(); delete(rooms, room.id); roomsMu.Unlock()
	}
}

func (c *Client) sendMsg(v interface{}) {
	data, _ := msgpack.Marshal(v)
	select { case c.send <- data: default: {} }
}

func (c *Client) sendError(msg string) { c.sendMsg(map[string]string{"type": "error", "message": msg}) }
func trunc(s string) string { return s[:min(16, len(s))] }
