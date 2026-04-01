# Kody błędów

Wspólne dla wszystkich kanałów komunikacji.

## Format błędu

JSON:
```json
{"error": "error_code", "message": "Human-readable description"}
```

W WS: opakowane w `{"id":"...","type":"error","error":{...}}`
W HTTP: odpowiedni status code + body
W AIDL: JSON string zwracany z metody
W Intent: JSON string w extra "result"

## Lista kodów

### Parsowanie / walidacja
| Kod | HTTP | Opis |
|-----|------|------|
| `parse_error` | 400 | Nieprawidłowy JSON |
| `invalid_method` | 400 | Nieznana metoda |
| `invalid_params` | 400 | Nieprawidłowe parametry |

### Autoryzacja
| Kod | HTTP | Opis |
|-----|------|------|
| `unauthorized` | 401 | Brak tokenu / wygasł / binding mismatch / sesja nieautoryzowana |
| `forbidden` | 403 | Brak uprawnienia do operacji lub eventu |
| `invalid_key` | 400 | Nieprawidłowy podpis JWT (service key lub device key) |
| `key_expired` | 400 | JWT wygasł |
| `restriction_mismatch` | 400 | JWT restrictions nie pasują (zły package/origin/cert) |
| `user_denied` | 403 | Użytkownik odmówił w dialogu |
| `timeout` | 408 | Timeout oczekiwania na dialog (60s) |

### Urządzenia / pluginy
| Kod | HTTP | Opis |
|-----|------|------|
| `device_unavailable` | 404 | Brak pluginu obsługującego metodę |
| `device_busy` | 409 | Urządzenie zajęte |
| `plugin_error` | 500 | Błąd wewnętrzny pluginu |

### Rate limiting
| Kod | HTTP | Opis |
|-----|------|------|
| `rate_limited` | 429 | Za dużo requestów |
