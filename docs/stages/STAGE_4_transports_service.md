# Etap 4: Transporty + HalService + Dashboard

Przeczytaj przed implementacją:
- `docs/spec/protocol-ws.md` — typy wiadomości, subskrypcje, sesje
- `docs/spec/protocol-http.md` — endpointy REST
- `docs/spec/protocol-aidl.md` — IHalService, IHalCallback, flow
- `docs/spec/protocol-intent.md` — actions, extras, IntentGatewayActivity
- `docs/spec/protocol-broadcast.md` — format, konfiguracja per-event
- `docs/spec/error-codes.md` — wspólne kody błędów
- `docs/spec/ktor-coordination.md` — sekwencja startu Ktor w HalService
- `docs/spec/concurrency.md` — thread safety sesji, dispatcher strategy
- `docs/spec/testing.md` — sekcja "Etap 4: transport testy"

## Zadanie

Zaimplementuj wszystkie kanały komunikacji, główny Service i Dashboard.

## Moduły do implementacji

1. **transport-aidl:** AidlTransport + AIDL kliencki (IHalService.aidl, IHalCallback.aidl)
2. **transport-ws:** WsTransport (rejestruje WS routing w KtorServerManager — NIE startuje serwera)
3. **transport-http:** HttpTransport (rejestruje REST routes w KtorServerManager — NIE startuje serwera)
4. **transport-intent:** IntentGatewayActivity + IntentTransport (isToggleable=true)
5. **transport-broadcast:** BroadcastTransport (isToggleable=true, per-event config)

## hal-service/service

6. **HalService:** Foreground Service — sekwencja z spec/ktor-coordination.md
7. **BootReceiver:** autostart
8. **DashboardActivity:** status, transport toggles, broadcast config, pluginy, tokeny
9. **AndroidManifest:** permissions, service, activities, receiver

## Testy

Patrz `docs/spec/testing.md` → "Etap 4":
- WsProtocol: parse/serialize wszystkich typów wiadomości, invalid JSON
- Subscription filtering: exact match, wildcard, global, permission validation

## Kryterium

HAL Service uruchamia się. WS + HTTP na porcie 8400. AIDL binding działa.
requestToken zwraca token. execute zwraca "device_unavailable".
system.ping/status/describe działają. Testy protocol przechodzą.
