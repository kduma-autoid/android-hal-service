# Etap 3: Autoryzacja + plugin system + transport-ktor-core

Przeczytaj przed implementacją:
- `docs/spec/auth.md` — pełny flow, JWT, token binding, dialog, Room entity
- `docs/spec/plugin-contract.md` — sekcje EventBus, PluginContextImpl, PluginRegistry
- `docs/spec/transport-contract.md` — sekcja KtorServerManager
- `docs/spec/ktor-coordination.md` — kto tworzy/startuje Ktor serwer
- `docs/spec/system-methods.md` — system.ping/status/describe w CommandRouter
- `docs/spec/concurrency.md` — thread safety, dispatcher strategy
- `docs/spec/testing.md` — sekcja "Etap 3: hal-service/auth testy"

## Zadanie

Zaimplementuj serce hal-service: autoryzację, routing, plugin system, i KtorServerManager.

## transport-ktor-core

KtorServerManager — zarządza jednym Ktor embeddedServer. transport-ws i transport-http
rejestrują swoje handlery. hal-service startuje serwer PO zarejestrowaniu wszystkich modułów.
Patrz spec/ktor-coordination.md.

## hal-service/auth

1. Room: TokenEntity (z bound* polami), TokenDao, TokenDatabase
2. TokenManager: CRUD tokenów z walidacją binding
3. DeveloperKeyVerifier: JWT weryfikacja (wygeneruj testową parę kluczy)
4. AuthManager: orchestracja requestToken flow
5. GrantPermissionActivity: dialog zgody użytkownika

## hal-service/plugin

1. EventBus: SharedFlow z loop protection, wildcard matching, thread-safe emission
2. PluginContextImpl: per-plugin instancje z ownerPluginId
3. PluginRegistry: in-process + external discovery + initializeAll

## hal-service/core

1. ServiceCommandHandler: impl CommandHandler → bridge auth + routing + system methods
   (system.ping bez auth, system.status/describe z auth)
2. TransportBootstrap: odkrywanie transportów przez refleksję

## Testy (hal-service)

Patrz `docs/spec/testing.md` → "Etap 3":
- TokenManager: create/validate, binding rejection (wrong package, wrong origin),
  expired token, revoke, unrestricted token
- DeveloperKeyVerifier: valid JWT, expired, wrong signature, restriction mismatch,
  unrestricted, web origin check
- AuthManager: valid devKey → token, invalid devKey → error (no dialog),
  no devKey → dialog

Użyj in-memory Room database w testach TokenManager.

## Kryterium

Projekt się builduje. Auth flow kompletny. PluginRegistry gotowy.
System methods zaimplementowane. Testy auth przechodzą.
