# Pluginy — implementacja

## plugin-sunmi-printer-lib: SunmiPrinterPlugin

Stub — drukarka Sunmi.

```kotlin
pluginId = "sunmi.printer"
version = 1
capabilities = ["sunmi.printer"]
```

**execute():**
| Metoda | Odpowiedź |
|--------|-----------|
| sunmi.printer.print | `{"jobId":"job_{timestamp}","status":"queued"}` |
| sunmi.printer.status | `{"status":"idle","paperLevel":"ok"}` |
| inne | `{"error":"unsupported_method","method":"..."}` |

**getDescriptor():**
```kotlin
PluginDescriptor(
    pluginId = "sunmi.printer", version = 1,
    capabilities = listOf("sunmi.printer"),
    methods = listOf(
        MethodDescriptor("sunmi.printer.print", "Print receipt using Sunmi printer", "sunmi.printer"),
        MethodDescriptor("sunmi.printer.status", "Get Sunmi printer status", "sunmi.printer")
    ),
    events = emptyList()
)
```

Nie wymaga PluginContext. initialize(ctx) — zapisuje ale nie używa.

## plugin-sunmi-scanner-lib: SunmiScannerPlugin

Stub — skaner Sunmi.

```kotlin
pluginId = "sunmi.scanner"
version = 1
capabilities = ["sunmi.scanner"]
```

**execute():**
| Metoda | Odpowiedź |
|--------|-----------|
| sunmi.scanner.trigger | `{"status":"scanning"}` |
| sunmi.scanner.stop | `{"status":"idle"}` |
| inne | `{"error":"unsupported_method","method":"..."}` |

**getDescriptor():**
```kotlin
PluginDescriptor(
    pluginId = "sunmi.scanner", version = 1,
    capabilities = listOf("sunmi.scanner"),
    methods = listOf(
        MethodDescriptor("sunmi.scanner.trigger", "Trigger barcode scan", "sunmi.scanner"),
        MethodDescriptor("sunmi.scanner.stop", "Stop scanning", "sunmi.scanner")
    ),
    events = listOf(
        EventDescriptor("sunmi.scanner.barcode", "Barcode scanned by Sunmi scanner", "sunmi.scanner")
    )
)
```

Nie wymaga PluginContext.

## plugin-sunmi-bundle: standalone APK

Bundluje oba plugin libs. Osobny Service per plugin.

```kotlin
class SunmiPrinterService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiPrinterPlugin(this))
}

class SunmiScannerService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiScannerPlugin(this))
}
```

AndroidManifest: dwa Service z intent-filter `dev.duma.android.hal.HARDWARE_PLUGIN`
i meta-data plugin.id / plugin.label.

## plugin-generic-lib: GenericPrinterPlugin

Abstrakcja drukarki — deleguje do vendor-specific.

```kotlin
pluginId = "printer"
version = 1
capabilities = ["printer"]
```

WYMAGA PluginContext (musi być in-process).

**execute():**
| Metoda | Działanie |
|--------|-----------|
| printer.print | Szukaj vendor → ctx.execute("sunmi.printer.print", params) |
| printer.status | Szukaj vendor → ctx.execute("sunmi.printer.status", params) |
| inne | `{"error":"unsupported_method"}` |

Priorytet vendorów: `["sunmi.printer", "zebra.printer", "chainway.printer"]`
Brak vendora: `{"error":"no_printer_backend","message":"No vendor printer plugin available"}`

```kotlin
private fun findVendorMethod(operation: String): String? {
    val vendors = listOf("sunmi.printer", "zebra.printer", "chainway.printer")
    for (vendor in vendors) {
        if (ctx.hasCapability(vendor)) return "$vendor.$operation"
    }
    return null
}
```

**getDescriptor():**
```kotlin
PluginDescriptor(
    pluginId = "printer", version = 1,
    capabilities = listOf("printer"),
    methods = listOf(
        MethodDescriptor("printer.print", "Print using available printer", "printer"),
        MethodDescriptor("printer.status", "Get printer status", "printer")
    ),
    events = emptyList()
)
```

## plugin-generic-lib: GenericScannerPlugin

Abstrakcja skanera — deleguje komendy + transformuje eventy.

```kotlin
pluginId = "scanner"
version = 1
capabilities = ["scanner"]
```

WYMAGA PluginContext.

**initialize(ctx):**
Rejestruje event listenery dla transformacji vendor → unified:
```kotlin
val vendors = listOf("sunmi", "zebra", "chainway")
for (vendor in vendors) {
    ctx.onEvent("$vendor.scanner.*") { event, data ->
        // "sunmi.scanner.barcode" → "scanner.barcode"
        val unifiedEvent = event.replaceFirst("$vendor.", "")
        ctx.emitEvent(unifiedEvent, data)
    }
}
```

**execute():**
| Metoda | Działanie |
|--------|-----------|
| scanner.trigger | Szukaj vendor → ctx.execute("sunmi.scanner.trigger", params) |
| scanner.stop | Szukaj vendor → ctx.execute("sunmi.scanner.stop", params) |
| inne | `{"error":"unsupported_method"}` |

Priorytet: `["sunmi.scanner", "zebra.scanner", "chainway.scanner"]`

**getDescriptor():**
```kotlin
PluginDescriptor(
    pluginId = "scanner", version = 1,
    capabilities = listOf("scanner"),
    methods = listOf(
        MethodDescriptor("scanner.trigger", "Trigger barcode scan", "scanner"),
        MethodDescriptor("scanner.stop", "Stop scanning", "scanner")
    ),
    events = listOf(
        EventDescriptor("scanner.barcode", "Barcode scanned (unified)", "scanner")
    )
)
```
