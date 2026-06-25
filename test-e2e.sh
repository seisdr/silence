#!/bin/bash
set -euo pipefail
RELAY_PORT="${PORT:-8088}"

# Clean state from previous runs
rm -f server/users.json server/users.json.bak
# Start relay
pkill -f "silence-signaling.*:$RELAY_PORT" 2>/dev/null || true
sleep 0.3
cd server && PORT=$RELAY_PORT nohup ./silence-signaling > /tmp/silence.log 2>&1 &
cd .. && sleep 1

echo "=== Silence Relay Test ==="

# 1. Health
curl -sf http://localhost:$RELAY_PORT/health > /dev/null && echo "✅ Health" || echo "❌ Health"

# 2. REST register
curl -sf -X POST http://localhost:$RELAY_PORT/api/users -d '{"action":"register","username":"e2e","password":"p"}' > /dev/null && echo "✅ REST register" || echo "❌ REST register"

# 3. REST login
curl -sf -X POST http://localhost:$RELAY_PORT/api/users -d '{"action":"login","username":"e2e","password":"p"}' > /dev/null && echo "✅ REST login" || echo "❌ REST login"

# 4. Bad login (expects 401)
r=$(curl -s -w "%{http_code}" -X POST http://localhost:$RELAY_PORT/api/users -d '{"action":"login","username":"e2e","password":"wrong"}')
echo "$r" | grep -q "401" && echo "✅ Bad login rejected" || echo "❌ Bad login"

# 5. Full protocol
python3 -c "
import asyncio, struct, msgpack, time, os
HOST, PORT = '127.0.0.1', $RELAY_PORT
uid = str(int(time.time()))[-4:]
ua, ub = f'a{uid}', f'b{uid}'

# Clean users.json
if os.path.exists('server/users.json'): os.remove('server/users.json')

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
    ar, aw = await client(); br, bw = await client()
    
    # register_user auto-authenticates
    send(aw, {'type':'register_user','username':ua,'password':'p','fingerprint':ua})
    await recv(ar)
    send(bw, {'type':'register_user','username':ub,'password':'p','fingerprint':ub})
    await recv(br)
    
    # call_user (authenticated)
    send(aw, {'type':'call_user','username':ub})
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
    print('OK')
    aw.close(); bw.close()

asyncio.run(test())
" 2>&1 | grep -q "OK" && echo "✅ 7-message protocol" || echo "❌ Protocol"

echo ""
echo "All tests complete."
