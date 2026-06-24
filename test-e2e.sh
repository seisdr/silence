#!/bin/bash
set -euo pipefail
RELAY_PORT="${PORT:-8088}"

# Start relay
pkill -f "silence-signaling.*:$RELAY_PORT" 2>/dev/null || true
sleep 0.3
cd server && PORT=$RELAY_PORT nohup ./silence-signaling > /tmp/silence.log 2>&1 &
cd .. && sleep 1

PASS=0; FAIL=0
pass() { PASS=$((PASS+1)); echo "  ✅ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }

echo "=== Silence Relay Test ==="

# Health
curl -sf http://localhost:$RELAY_PORT/health > /dev/null && pass "Health endpoint" || fail "Health endpoint"

# REST
r=$(curl -s -X POST http://localhost:$RELAY_PORT/api/users -d '{"action":"register","username":"e2e_check","password":"p"}')
echo "$r" | grep -q '"created"' && pass "REST register" || fail "REST register"
r=$(curl -s -X POST http://localhost:$RELAY_PORT/api/users -d '{"action":"login","username":"e2e_check","password":"p"}')
echo "$r" | grep -q '"ok"' && pass "REST login" || fail "REST login"
r=$(curl -s -X POST http://localhost:$RELAY_PORT/api/users -d '{"action":"login","username":"e2e_check","password":"wrong"}')
echo "$r" | grep -q '"invalid"' && pass "REST bad login rejected" || fail "REST bad login rejected"

# Protocol
python3 -c "
import asyncio, struct, msgpack, time
HOST, PORT = '127.0.0.1', $RELAY_PORT
uid = str(int(time.time()))[-4:]

def ws_frame(op, payload):
    mask = b'\xAA\xBB\xCC\xDD'
    masked = bytes(b ^ mask[i%4] for i,b in enumerate(payload))
    f = bytearray([0x80|op]); n = len(payload)
    if n < 126: f.append(0x80|n)
    f.extend(mask); f.extend(masked)
    return bytes(f)

def send(w, d): w.write(ws_frame(2, msgpack.packb(d)))

async def recv(r, t=5):
    h = await asyncio.wait_for(r.readexactly(2), t)
    n = h[1] & 0x7F
    if n == 126: n = struct.unpack('!H', await asyncio.wait_for(r.readexactly(2), t))[0]
    return msgpack.unpackb(await asyncio.wait_for(r.readexactly(n), t))

async def client():
    r, w = await asyncio.open_connection(HOST, PORT)
    k = 'dGhlIHNhbXBsZSBub25jZQ=='
    req = f'GET /ws HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {k}\r\nSec-WebSocket-Version: 13\r\n\r\n'
    w.write(req.encode()); await w.drain()
    await r.readuntil(b'\r\n\r\n')
    return r, w

async def test():
    ua, ub = f'a{uid}', f'b{uid}'
    ar, aw = await client(); br, bw = await client()
    
    send(aw, {'type':'register','fingerprint':ua})
    await recv(ar)
    send(bw, {'type':'register','fingerprint':ub})
    await recv(br)
    send(aw, {'type':'call','target':ub})
    room = (await recv(ar))['room']
    assert (await recv(br))['type'] == 'incoming'
    send(bw, {'type':'accept','room':room})
    assert (await recv(br))['type'] == 'joined'
    assert (await recv(ar))['type'] == 'joined'
    send(aw, {'type':'offer','sdp':'v=0','room':room})
    assert (await recv(br))['type'] == 'offer'
    send(bw, {'type':'answer','sdp':'v=0','room':room})
    assert (await recv(ar))['type'] == 'answer'
    send(aw, {'type':'ice','sdpMid':'0','sdpMLineIndex':0,'candidate':'c:1','room':room})
    assert (await recv(br))['type'] == 'ice'
    send(aw, {'type':'hangup','room':room})
    assert (await recv(br))['type'] == 'hangup'
    print('PROTOCOL_OK')
    aw.close(); bw.close()

asyncio.run(test())
" 2>&1 | grep -q "PROTOCOL_OK" && pass "7-message protocol" || fail "7-message protocol"

echo ""
echo "Results: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ] && echo "ALL SYSTEMS GO ✅" || echo "FAILURES DETECTED ❌"
