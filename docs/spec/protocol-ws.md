# Protokół WebSocket

Połączenie: `ws://localhost:8400/ws`

## Typy wiadomości

### Od klienta

**Request token:**
```json
{"id":"1","type":"requestToken","serviceKey":"eyJhbG...","clientId":"my-pos"}
```
Bez klucza (triggeruje dialog):
```json
{"id":"1","type":"requestToken","clientId":"my-pos"}
```

**Authenticate (po otrzymaniu tokenu):**
```json
{"id":"2","type":"authenticate","token":"abc123..."}
```

**Command:**
```json
{"id":"3","type":"command","method":"printer.print","params":{"template":"receipt"}}
```

**Subscribe (wymagane aby otrzymywać eventy):**
```json
{"id":"4","type":"subscribe","events":["scanner.barcode","rfid.*"]}
```

**Unsubscribe:**
```json
{"id":"5","type":"unsubscribe","events":["rfid.*"]}
```

### Od serwera

**Response (sukces):**
```json
{"id":"3","type":"response","result":{"jobId":"job_123","status":"queued"}}
```

**Error:**
```json
{"id":"1","type":"error","error":{"code":"unauthorized","message":"..."}}
```

**Event (push):**
```json
{"type":"event","event":"rfid.tag","source":"sunmi.rfid","data":{"epc":"E200...","rssi":-45}}
```

`source` to `pluginId` nadawcy eventu — w nagłówku ramki, obok `data` (nie w środku payloadu),
aby konsument mógł rozpoznać providera bez polegania na treści. Nieobecny na starszych serwerach.

## Subskrypcje eventów

Klient MUSI jawnie zasubskrybować eventy. Bez subskrypcji — zero eventów.

### Wildcardy

- `"scanner.barcode"` — konkretny event
- `"rfid.*"` — wszystkie z prefixem rfid. (rfid.tag, rfid.batch, rfid.stateChanged)
- `"*"` — wszystkie eventy (ograniczone uprawnieniami tokenu)

### Podwójne filtrowanie

1. **Pattern match** — czy event pasuje do subskrybowanego wzorca
2. **Permission check** — czy token ma uprawnienie do tego event typu
   (np. rfid.tag wymaga uprawnienia "rfid")

Subskrypcja eventu bez uprawnień → błąd `forbidden` przy subscribe.

## Sesja WS

- sessionId (generowany przy connect)
- token (null do authenticate)
- subscribedEvents: Set<String> (wzorce z wildcardami)

Przed authenticate: tylko requestToken dozwolone.
Po authenticate: command, subscribe, unsubscribe.

## Ping/pong

Ktor WebSocket ping/pong co 15s, timeout 30s.

## Kody błędów

Patrz: docs/spec/error-codes.md (wspólne dla wszystkich kanałów)
