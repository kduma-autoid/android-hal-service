# Standalone & SDK — bezpośrednie użycie pluginów bez HAL Service

**Status: PRZYSZŁA IMPLEMENTACJA — nie implementuj w obecnych etapach.**

## Cel

Umożliwić użycie vendor-specific plugin libs (np. plugin-sunmi-printer-lib)
bezpośrednio w aplikacji końcowej, bez HAL Service. Dla prostych apek
na jednym typie urządzenia, gdzie narzut service jest niepotrzebny.

## Warstwa 1: StandalonePluginContext (w hal-contract)

Minimalna implementacja PluginContext pozwalająca zainicjalizować plugin
poza HAL Service. Vendor-specific pluginy nie używają PluginContext
(nie delegują do innych pluginów), ale interfejs HalPlugin wymaga
initialize(PluginContext) — potrzebny jest stub.

```kotlin
class StandalonePluginContext(
    override val applicationContext: Context
) : PluginContext {
    // Lokalne callbacki na eventy (zastępuje EventBus)
    private val eventCallbacks = mutableListOf<(String, String) -> Unit>()

    override suspend fun execute(method: String, params: String): String {
        throw UnsupportedOperationException(
            "Inter-plugin calls not available in standalone mode"
        )
    }

    override fun getAvailableCapabilities() = emptyList<String>()
    override fun hasCapability(cap: String) = false

    override fun emitEvent(name: String, data: String) {
        eventCallbacks.forEach { it(name, data) }
    }

    override fun onEvent(pattern: String, callback: (String, String) -> Unit) {
        eventCallbacks.add(callback)  // Uproszczone — brak wildcard matching
    }

    /** Rejestruj listener na eventy (uproszczone API dla standalone) */
    fun addEventListener(callback: (eventName: String, jsonData: String) -> Unit) {
        eventCallbacks.add(callback)
    }
}
```

### Użycie

```kotlin
// W aplikacji końcowej — zero HAL Service, zero konfiguracji
val printer = SunmiPrinterPlugin(context)
printer.initialize(StandalonePluginContext(context))

val result = printer.execute("sunmi.printer.print", """{"template":"receipt"}""")
// → {"jobId":"job_123","status":"queued"}

// Eventy (skaner)
val scanner = SunmiScannerPlugin(context)
val standaloneCtx = StandalonePluginContext(context)
standaloneCtx.addEventListener { event, data ->
    // event = "sunmi.scanner.barcode", data = {"data":"590..."}
}
scanner.initialize(standaloneCtx)
scanner.execute("sunmi.scanner.trigger", "{}")
```

### Ograniczenia trybu standalone

- Brak inter-plugin communication (execute() rzuca UnsupportedOperationException)
- Brak warstwy interfejsów (routing `executeInterface` wymaga pełnego PluginRegistry)
- Brak autoryzacji — aplikacja ma bezpośredni dostęp
- Brak wildcard matching w onEvent (uproszczona implementacja)
- Jedno urządzenie / jeden vendor na raz

## Warstwa 2: Typed SDK (osobne moduły AAR)

Typowana fasada nad JSON-owym API pluginu. Działa w dwóch trybach:
bezpośrednio z pluginem (standalone) LUB przez HAL Service (remote).

### Moduły

```
sunmi-printer-sdk/          ← typed API dla drukarki Sunmi
  depends on: plugin-sunmi-printer-lib, hal-contract
  optional: hal-client (dla trybu remote)

sunmi-scanner-sdk/          ← typed API dla skanera Sunmi
  depends on: plugin-sunmi-scanner-lib, hal-contract
  optional: hal-client
```

### Interfejs (wspólny dla obu trybów)

```kotlin
// sunmi-printer-sdk

data class PrintResult(val jobId: String, val status: String)
data class PrinterStatus(val status: String, val paperLevel: String)

interface PrinterApi {
    suspend fun print(template: String, data: Map<String, Any> = emptyMap()): PrintResult
    suspend fun getStatus(): PrinterStatus
    fun onStateChanged(callback: (PrinterStatus) -> Unit)
}
```

### Implementacja standalone (bezpośrednia)

```kotlin
class StandaloneSunmiPrinter(context: Context) : PrinterApi {
    private val plugin = SunmiPrinterPlugin(context).also {
        it.initialize(StandalonePluginContext(context))
    }

    override suspend fun print(template: String, data: Map<String, Any>): PrintResult {
        val params = buildJsonObject { put("template", template) }.toString()
        val json = plugin.execute("sunmi.printer.print", params)
        return Json.decodeFromString<PrintResult>(json)
    }

    override suspend fun getStatus(): PrinterStatus {
        val json = plugin.execute("sunmi.printer.status", "{}")
        return Json.decodeFromString<PrinterStatus>(json)
    }

    override fun onStateChanged(callback: (PrinterStatus) -> Unit) {
        // Rejestruj listener na plugin event callback
    }
}
```

### Implementacja remote (przez HAL Service)

```kotlin
class RemotePrinter(private val hal: HalClient) : PrinterApi {
    override suspend fun print(template: String, data: Map<String, Any>): PrintResult {
        // Używa generic "printer.print" — HAL Service routuje do właściwego vendora
        val json = hal.execute("printer.print", buildParams(template, data))
        return Json.decodeFromString<PrintResult>(json)
    }

    override suspend fun getStatus(): PrinterStatus {
        val json = hal.execute("printer.status", "{}")
        return Json.decodeFromString<PrinterStatus>(json)
    }

    override fun onStateChanged(callback: (PrinterStatus) -> Unit) {
        hal.subscribe(listOf("printer.stateChanged"))
        hal.on("printer.stateChanged") { data ->
            callback(Json.decodeFromString<PrinterStatus>(data))
        }
    }
}
```

### Użycie w aplikacji

```kotlin
// Prosta apka na Sunmi — bezpośrednio, bez HAL Service
val printer: PrinterApi = StandaloneSunmiPrinter(context)
val result = printer.print("receipt")
println(result.jobId)  // Typowane!

// Duża apka multi-device — przez HAL Service
val hal = HalClient("ws://localhost:8400").also {
    it.requestToken(serviceKey = "eyJ...", clientId = "pos-app")
    it.connectWs()
}
val printer: PrinterApi = RemotePrinter(hal)
val result = printer.print("receipt")  // Ten sam interfejs!

// Migracja: zmiana jednej linii, reszta kodu bez zmian
```

### Hierarchia SDK

```
PrinterApi (interface)
├── StandaloneSunmiPrinter     ← bezpośrednio z pluginem
├── StandaloneZebraPrinter     ← bezpośrednio z pluginem Zebra
└── RemotePrinter              ← przez HAL Service (vendor-agnostic)
```

RemotePrinter używa generic "printer.print" — nie wie o vendorze.
Standalone* wie o vendorze — używa "sunmi.printer.print" bezpośrednio.

## Scenariusze użycia

| Scenariusz | Warstwa | Moduły w build.gradle |
|------------|---------|----------------------|
| Prosta apka Sunmi, bez HAL | Plugin lib + StandalonePluginContext | plugin-sunmi-printer-lib |
| Prosta apka Sunmi, typed | SDK standalone | sunmi-printer-sdk |
| Multi-device z HAL Service | SDK remote | sunmi-printer-sdk + hal-client |
| Debug / prototyp | Plugin lib bezpośrednio | plugin-sunmi-printer-lib |

## Kolejność implementacji (przyszła)

1. StandalonePluginContext w hal-contract (mała zmiana, warto zrobić wcześnie)
2. SDK per vendor-capability gdy API pluginów się ustabilizuje
3. RemotePrinter/RemoteScanner gdy hal-client (JS/Kotlin) będzie gotowy

## Wpływ na istniejącą architekturę

Żaden. StandalonePluginContext to dodatkowa klasa w hal-contract.
SDK to osobne moduły AAR. Nic nie zmienia w HAL Service ani pluginach.
