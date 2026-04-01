# Protokół HTTP

Bazowy URL: `http://localhost:8400/api`

## Endpointy

### POST /api/token — requestToken

Z developerKey:
```
POST /api/token
Content-Type: application/json

{"developerKey":"eyJhbG...","clientId":"my-pos"}
→ 200 {"token":"abc123...","permissions":["printer","scanner"],"expiresAt":...}
→ 400 {"error":"invalid_key","message":"..."}
→ 400 {"error":"key_expired","message":"..."}
→ 400 {"error":"restriction_mismatch","message":"..."}
```

Bez developerKey (triggeruje dialog, czeka max 60s):
```
POST /api/token
Content-Type: application/json

{"clientId":"my-pos"}
→ 200 {"token":"abc123...","permissions":[...],...}
→ 403 {"error":"user_denied","message":"..."}
→ 408 {"error":"timeout","message":"User did not respond"}
```

### POST /api/execute — komenda

```
POST /api/execute
Authorization: Bearer abc123...
Content-Type: application/json

{"method":"printer.print","params":{"template":"receipt"}}
→ 200 {"result":{"jobId":"job_123","status":"queued"}}
→ 401 {"error":"unauthorized","message":"..."}
→ 403 {"error":"forbidden","message":"No permission: printer"}
→ 404 {"error":"device_unavailable","message":"..."}
```

### GET /api/health — publiczny

```
GET /api/health
→ 200 {"status":"ok","plugins":{...},"transports":["aidl","ws","http"]}
```

Bez autoryzacji.

### GET /api/status — wymaga Bearer

```
GET /api/status
Authorization: Bearer abc123...
→ 200 {"plugins":{"sunmi.printer":{"capabilities":["sunmi.printer"],"connected":true},...}}
```

### GET /api/describe — wymaga Bearer

```
GET /api/describe
Authorization: Bearer abc123...
→ 200 {
    "plugins": [
      {
        "pluginId": "sunmi.printer",
        "methods": [{"name":"sunmi.printer.print","description":"...","permission":"sunmi.printer"}],
        "events": []
      },
      ...
    ]
  }
```

Agreguje PluginDescriptor ze wszystkich pluginów.

## Uwagi

- HTTP nie ma kanału eventów (stateless)
- Bearer token walidowany z binding (Origin header) per request
- Współdzieli Ktor serwer z transport-ws (ten sam port 8400)
