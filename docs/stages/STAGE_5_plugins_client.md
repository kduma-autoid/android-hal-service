# Etap 5: Pluginy + klient JavaScript

Przeczytaj przed implementacją:
- `docs/spec/plugins.md` — SunmiPrinterPlugin, SunmiScannerPlugin, GenericPrinterPlugin, GenericScannerPlugin, plugin-sunmi-bundle
- `docs/spec/testing.md` — sekcja "Etap 5: plugin testy"

## Zadanie

Zaimplementuj pluginy (stuby Sunmi + generic) i klienta JavaScript.

## Moduły do implementacji

1. **plugin-sunmi-printer-lib:** SunmiPrinterPlugin (stub)
2. **plugin-sunmi-scanner-lib:** SunmiScannerPlugin (stub)
3. **plugin-generic-lib:** GenericPrinterPlugin + GenericScannerPlugin (z event transformation)
4. **plugin-sunmi-bundle:** wrapper Services + manifest

## Klient JavaScript

5. **client/hal-client.js:** requestToken, execute (HTTP), connectWs, wsExecute,
   subscribe/unsubscribe z wildcardami, on(event, callback), auto-reconnect
6. **client/test.html:** UI do testowania wszystkich operacji

## Testy

Patrz `docs/spec/testing.md` → "Etap 5":
- GenericPrinterPlugin: delegation to sunmi, error when no vendor, priority order
- GenericScannerPlugin: event transformation (vendor → unified)

## Kryterium — pełny e2e + testy

1. Uruchom hal-service (sunmi flavor)
2. test.html: token → connect → printer.print → result
3. GET /api/describe → lista metod/eventów
4. Testy plugin delegation i event transformation przechodzą
5. `./gradlew test` — wszystkie testy przechodzą
