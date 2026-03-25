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
      "scanner": {"version":1,"capabilities":["scanner"],"type":"built_in","connected":true}
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
        "pluginId": "scanner",
        "version": 1,
        "capabilities": ["scanner"],
        "methods": [
          {"name":"scanner.trigger","description":"Trigger scan","permission":"scanner"},
          {"name":"scanner.stop","description":"Stop scanning","permission":"scanner"}
        ],
        "events": [
          {"name":"scanner.barcode","description":"Barcode scanned","permission":"scanner"}
        ]
      }
    ]
  }
```

HTTP: `GET /api/describe` (z Bearer)

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
