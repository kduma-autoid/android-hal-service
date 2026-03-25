# Autoryzacja

## Ujednolicony model tokenów

Jeden flow autoryzacji dla WSZYSTKICH kanałów (AIDL, WS, HTTP, Intent).
Wynik: session token przechowywany w Room database.

## Flow requestToken

```
Klient                          HAL Service
  │                                  │
  ├─── requestToken(devKey?) ───────►│
  │                                  │
  │    [devKey podany?]              │
  │    ├─ TAK → weryfikuj JWT:       │
  │    │   ├─ Podpis OK?            │
  │    │   │  ├─ NIE → błąd invalid_developer_key
  │    │   │  └─ TAK ↓              │
  │    │   ├─ Wygasł?              │
  │    │   │  ├─ TAK → błąd developer_key_expired
  │    │   │  └─ NIE ↓              │
  │    │   ├─ Restrictions pasują?  │
  │    │   │  ├─ NIE → błąd restriction_mismatch
  │    │   │  └─ TAK → token ◄──────┤ uprawnienia z JWT
  │    │   │                         │
  │    ├─ NIE (brak devKey) ────────►│ pokaż dialog użytkownikowi
  │    │   [użytkownik decyduje]     │
  │    │   ├─ Na stałe → token ◄────┤ grant_duration="permanent"
  │    │   ├─ Na dzień → token ◄────┤ grant_duration="day"
  │    │   └─ Odmówił → błąd ◄──────┤ user_denied
  │                                  │
  │    [token otrzymany]             │
  │                                  │
  ├─── authenticate(token) ─────────►│  (AIDL / WS — autoryzuje sesję)
  │    lub Bearer token ─────────────►│  (HTTP — per request)
  │                                  │
  ├─── execute(method, params) ──────►│  (autoryzowana komenda)
  │◄── result ───────────────────────┤
```

**Ważne:** dialog zgody pojawia się WYŁĄCZNIE gdy klient nie podał devKey.
Nieprawidłowy JWT (podpis, expiry, restrictions) → zawsze konkretny błąd, nigdy dialog.

## Developer Key (JWT)

Podpisany JWT wydawany przez dewelopera. HAL Service weryfikuje podpis
kluczem publicznym ED25519 lub RS256 wkompilowanym w APK (resources/raw/).

### Claims

```json
{
  "iss": "hal-developer-portal",
  "sub": "com.partner.posapp",
  "iat": 1700000000,
  "exp": 1731536000,
  "client_type": "android",
  "restrictions": {
    "package_name": "com.partner.posapp",
    "cert_sha256": "A1:B2:C3:...",
    "origins": null
  },
  "permissions": ["printer", "scanner", "rfid"]
}
```

### client_type

- `"android"` — restrictions.package_name i/lub restrictions.cert_sha256
- `"web"` — restrictions.origins (lista dozwolonych originów)
- `"unrestricted"` — brak ograniczeń (testy / zaufani partnerzy)

### Weryfikacja

1. Sprawdź podpis (klucz publiczny z resources)
2. Sprawdź exp (czy nie wygasł)
3. Sprawdź restrictions:
   - android: porównaj package_name / cert_sha256 z CallerContext
   - web: porównaj Origin z listą origins
   - unrestricted: pomiń
4. OK → token z uprawnieniami z JWT
5. NIE → konkretny błąd (invalid_developer_key / developer_key_expired / restriction_mismatch)

## Token sesyjny

```json
{
  "token": "random-64-hex-chars",
  "client_id": "com.partner.posapp",
  "client_type": "android",
  "permissions": ["printer", "scanner"],
  "granted_by": "developer_key",
  "granted_at": 1700000000,
  "expires_at": 1700086400,
  "grant_duration": "permanent"
}
```

### grant_duration

- `"permanent"` — ważny do odwołania (devKey lub dialog "na stałe")
- `"day"` — wygasa po 24h (dialog "na dzień")
- `"session"` — wygasa po zamknięciu połączenia

## Token binding

Każdy token powiązany z kontekstem w którym został wydany:

| Kanał | Pola binding | Weryfikacja |
|-------|-------------|-------------|
| AIDL  | boundPackageName + boundCertHash | Binder.getCallingUid() → packageManager |
| WS    | boundOrigin | Origin z WS handshake |
| HTTP  | boundOrigin | Origin z HTTP request |
| Intent | boundPackageName + boundCertHash | getCallingPackage() |

Przy każdym użyciu tokenu: sprawdź binding vs CallerContext.
Token kradziony (inna apka/origin) → unauthorized.
Przeinstalowana apka (inny cert) → token nieważny.
Token z unrestricted devKey → brak binding, działa z dowolnego kontekstu.

## Token storage (Room)

### TokenEntity

| Pole | Typ | Opis |
|------|-----|------|
| id | Long (PK, autoGenerate) | |
| token | String (unique index) | Random 64 hex |
| clientId | String | Identyfikator klienta |
| clientType | String | android / web / unrestricted |
| permissions | String | JSON array ["printer","scanner"] |
| grantedBy | String | developer_key / user_permanent / user_day |
| grantedAt | Long | Timestamp |
| expiresAt | Long? | Nullable — permanent nie wygasa |
| boundPackageName | String? | Binding AIDL/Intent |
| boundCertHash | String? | Binding AIDL/Intent |
| boundOrigin | String? | Binding WS/HTTP |
| clientInfo | String | JSON — dodatkowe metadane |

### TokenDao

- getByToken(token): TokenEntity?
- getAll(): List<TokenEntity>
- insert(entity): Long
- deleteByToken(token)
- deleteByClientId(clientId)
- deleteExpired(now: Long)

### TokenManager

- generateToken(): String — SecureRandom 32 bytes → hex
- createToken(clientId, permissions, grantedBy, duration, binding): TokenEntity
- validateToken(token, callerContext): TokenEntity? — existence + expiry + binding
- revokeToken(token), revokeAllForClient(clientId)
- Na starcie: deleteExpired()

## Dialog zgody użytkownika

GrantPermissionActivity — przeźroczysta (Theme.Translucent.NoTitleBar), excludeFromRecents.

Wyświetla:
- Nazwa aplikacji / clientId / origin
- Dla Android: packageName, fragment cert hash
- Dla Web: origin URL
- Żądane uprawnienia lub "pełny dostęp"

Opcje:
- "Zezwól na stałe" → grant_duration="permanent"
- "Zezwól na dzień" → grant_duration="day", expires_at = now + 24h
- "Odmów" → error user_denied

Wynik przez CompletableDeferred<GrantDecision> w companion object.

## Kody błędów autoryzacji

- `unauthorized` — brak tokenu / wygasł / binding mismatch / sesja nieautoryzowana
- `forbidden` — brak uprawnienia do operacji lub eventu
- `invalid_developer_key` — nieprawidłowy podpis JWT
- `developer_key_expired` — JWT wygasł
- `restriction_mismatch` — JWT restrictions nie pasują (zły package/origin/cert)
- `user_denied` — użytkownik odmówił w dialogu
- `timeout` — timeout oczekiwania na dialog (60s)
