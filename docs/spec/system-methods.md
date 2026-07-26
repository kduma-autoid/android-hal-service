# Metody systemowe

Metody obsługiwane bezpośrednio przez CommandRouter, bez routowania do pluginów.
Dostępne na WSZYSTKICH kanałach (AIDL, WS, HTTP, Intent).

## system.ping

Sprawdzenie czy usługa działa. Bez autoryzacji.

```
→ execute("system.ping", "{}")
← {"pong":true,"timestamp":1700000000}
```

HTTP: `GET /api/health` (alias, bez Bearer)

## system.status

Status usługi. Wymaga tokenu.

```
→ execute("system.status", "{}")
← {
    "uptime": 3600,
    "plugins": {
      "sunmi.printer": {"version":1,"capabilities":["sunmi.printer"],"type":"built_in","connected":true},
      "sunmi.scanner.inner": {"version":1,"capabilities":["sunmi.scanner.inner"],"type":"built_in","connected":true}
    },
    "transports": {
      "aidl": {"running":true,"toggleable":false},
      "ws": {"running":true,"toggleable":false,"sessions":3},
      "http": {"running":true,"toggleable":false},
      "intent": {"running":true,"toggleable":true,"enabled":true},
      "broadcast": {"running":true,"toggleable":true,"enabled":true}
    },
    "tokens": {"active":5,"expired_cleaned":12}
  }
```

HTTP: `GET /api/status` (z Bearer)

## system.describe

Opis dostępnego API — agreguje PluginDescriptor ze wszystkich pluginów.
Wymaga tokenu. Klient widzi tylko metody/eventy do których ma uprawnienia.

```
→ execute("system.describe", "{}")
← {
    "plugins": [
      {
        "pluginId": "sunmi.printer",
        "version": 1,
        "capabilities": ["sunmi.printer"],
        "methods": [
          {"name":"sunmi.printer.print","description":"Print receipt","permission":"sunmi.printer"},
          {"name":"sunmi.printer.status","description":"Get status","permission":"sunmi.printer"}
        ],
        "events": []
      },
      {
        "pluginId": "sunmi.scanner.inner",
        "version": 1,
        "capabilities": ["sunmi.scanner.inner"],
        "providesInterfaces": ["barcodeScanner"],
        "methods": [
          {"name":"sunmi.scanner.inner.trigger","description":"Trigger scan","permission":"sunmi.scanner.inner"},
          {"name":"sunmi.scanner.inner.stop","description":"Stop scanning","permission":"sunmi.scanner.inner"}
        ],
        "events": [
          {"name":"sunmi.scanner.inner.barcode","description":"Barcode scanned","permission":"sunmi.scanner.inner"},
          {"name":"barcodeScanner.onScan","description":"Unified barcode scanner event","permission":"barcodeScanner"}
        ]
      }
    ]
  }
```

HTTP: `GET /api/describe` (z Bearer)

Obok `plugins`, `system.describe` zwraca `interfaces: [...]` (warstwa interfejsów — m.in. `printer`,
`barcodeScanner`, `light`) oraz — dla każdego pluginu — `providesInterfaces` / `definesInterfaces`. Skaner
`sunmi.scanner.inner` udostępnia interfejs `barcodeScanner` (metoda `barcodeScanner.trigger`, event `barcodeScanner.onScan`),
a `sunmi.printerx.printer` — interfejs `printer`. Szczegóły: [`interfaces.md`](interfaces.md).

## system.interface.setOrder / system.interface.setEnabled

Konfiguracja kolejności i włączenia providerów interfejsu (persystowane; wymaga tokenu). Zmiana
emituje event `system.interfaces.changed`.

```
→ execute("system.interface.setOrder",
          "{\"interfaceId\":\"light\",\"order\":[\"sunmi.statuslight\",\"sunmi.tms.led\"]}")
→ execute("system.interface.setEnabled",
          "{\"interfaceId\":\"light\",\"pluginId\":\"sunmi.tms.led\",\"enabled\":false}")
```

Kolejność steruje też domyślnym providerem (pierwszy dostępny+włączony). Wybór providera w pojedynczym
wywołaniu: zarezerwowany param `__provider`. Handler zwracany jest w nagłówku odpowiedzi (`provider`).
Pełny opis: [`interfaces.md`](interfaces.md).

## Filtrowanie per uprawnienia

system.describe filtruje wynik na podstawie uprawnień tokenu:
- Token z permissions=["printer"] → widzi tylko metody/eventy z permission "printer"
  oraz ich vendor-specific warianty (sunmi.printer, zebra.printer)
- Token z permissions=["*"] lub unrestricted → widzi wszystko

## Routing w CommandRouter

```kotlin
suspend fun execute(token: String?, method: String, params: String, callerContext: CallerContext): String {
    return when (method) {
        "system.ping" -> handlePing()                    // Bez tokenu
        "system.status" -> {
            requireToken(token, callerContext)
            handleStatus()
        }
        "system.describe" -> {
            val tokenEntity = requireToken(token, callerContext)
            handleDescribe(tokenEntity.permissions)      // Filtruj per uprawnienia
        }
        else -> {
            val tokenEntity = requireToken(token, callerContext)
            routeToPlugin(tokenEntity, method, params)   // Normalne routowanie
        }
    }
}
```
