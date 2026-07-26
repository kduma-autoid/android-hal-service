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

## plugin-generic-lib: PrinterInterface (definer interfejsu `printer`)

Abstrakcja drukarki jako **interfejs**, nie owijka. `PrinterInterface` (pluginId `interface.printer`)
tylko *rejestruje* kontrakt `printer` — nie ma sprzętu ani capabilities. Providerzy podpinają się
jawnie przez `InterfaceBinding("printer", …)` i sami routują metody kontraktu. Zastępuje dawny
`GenericPrinterPlugin` (owijkę z zaszytą listą vendorów). Szczegóły warstwy: [`interfaces.md`](interfaces.md).

```kotlin
pluginId = "interface.printer"      // definer
version = 1
capabilities = []                    // definer nie udostępnia sprzętu
definesInterfaces = [InterfaceContract("printer", …)]
```

**Metody kontraktu** (każda bramkowana cechą — provider musi ogłosić cechę w `binding.features`):

| Metoda | Cecha | Params |
|--------|-------|--------|
| printer.printEscPos | `escpos` | `{"data": base64}` |
| printer.printTspl | `tspl` | `{"data": base64}` |
| printer.printZpl | `zpl` | `{"data": base64}` |
| printer.printImage | `image` | `{"bitmap": base64, "style"?: {…}}` |
| printer.cut | `cut` | `{}` |

**Provider:** `sunmi.printerx.printer` (`SunmiPrinterXPrinterPlugin`, oba buildy sunmi) —
`InterfaceBinding("printer", priority = 100, features = ["escpos","tspl","image","cut"])`. Nie ma `zpl`
(SDK go nie wspiera), więc `printer.printZpl` → `unavailable`. `printer.cut` mapuje na sprzętowe
`autoOut` (feed+cut). Flavor `generic` nie ma providera → `printer.*` = `unavailable`.

## plugin-generic-lib: ScannerInterface (definer interfejsu `scanner`)

Abstrakcja skanera jako **interfejs**. `ScannerInterface` (pluginId `interface.scanner`) rejestruje
kontrakt `scanner`; providerzy podpinają się przez `InterfaceBinding("scanner", …)`, obsługują
`scanner.trigger/stop` i emitują znormalizowany event `scanner.onScan` **obok** swojego natywnego
eventu. Zastępuje dawny `GenericScannerPlugin` (transformację eventów z zaszytej listy vendorów).

```kotlin
pluginId = "interface.scanner"      // definer
version = 1
capabilities = []
definesInterfaces = [InterfaceContract("scanner", …)]
```

**Metody:** `scanner.trigger {}` → `{"status":"scanning"}`, `scanner.stop {}`.
**Event:** `scanner.onScan {data, format, rawData?}` — `source` = `pluginId` skanera-nadawcy, więc
subskrypcja `scanner.onScan@sunmi.scanner.inner` filtruje konkretny skaner.

**Providerzy** (bez features):

| plugin | priority | build |
|--------|----------|-------|
| `sunmi.scanner.inner` (`SunmiInnerScannerPlugin`) | 100 (default) | sunmi stable + development |
| `sunmi.scanner.external` (`SunmiExternalScannerPlugin`) | 50 | sunmi development (experimental) |
| `sunmi.scanner.camera` (`SunmiCameraScannerPlugin`) | 40 | sunmi development (experimental) |

Flavor `generic` nie ma providera → `scanner.*` = `unavailable`.
