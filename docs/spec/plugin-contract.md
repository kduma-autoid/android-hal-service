# Kontrakt pluginów

## HalPlugin — interfejs in-process

```kotlin
interface HalPlugin {
    val pluginId: String      // "sunmi.printer", "printer", "scanner"
    val version: Int
    fun getCapabilities(): List<String>
    fun getDescriptor(): PluginDescriptor
    fun initialize(pluginContext: PluginContext)
    suspend fun execute(method: String, params: String): String
    fun setEventCallback(callback: HalPluginEventCallback?)
}

interface HalPluginEventCallback {
    fun onEvent(eventName: String, jsonData: String)
    fun onError(deviceType: String, code: Int, message: String)
}
```

initialize() wywoływane PO rejestracji WSZYSTKICH pluginów — dopiero wtedy
PluginContext widzi pełne capabilities.

## PluginDescriptor — deklaracja metod i eventów

```kotlin
data class PluginDescriptor(
    val pluginId: String,
    val version: Int,
    val capabilities: List<String>,
    val methods: List<MethodDescriptor>,
    val events: List<EventDescriptor>
)

data class MethodDescriptor(
    val name: String,                // "sunmi.printer.print"
    val description: String,         // "Print a receipt"
    val requiredPermission: String,  // "sunmi.printer"
    val superRequired: Boolean = false,
    val exampleParameters: String,   // """{"text":"Hello"}"""
    val exampleOutput: String        // """{"status":"ok"}"""
)

data class EventDescriptor(
    val name: String,                // "sunmi.scanner.barcode"
    val description: String,         // "Barcode scanned"
    val requiredPermission: String,  // "sunmi.scanner"
    val exampleEvent: String         // """{"data":"123","format":"EAN13"}"""
)
```

Używane przez:
- Dashboard — lista metod/eventów per plugin
- Broadcast config — checkboxy per event
- system.describe — klient odpytuje dostępne API
- Przyszłe: auto-generowanie dokumentacji

## PluginContext — komunikacja między pluginami

```kotlin
interface PluginContext {
    suspend fun execute(method: String, params: String): String
    fun getAvailableCapabilities(): List<String>
    fun hasCapability(capability: String): Boolean
    fun emitEvent(eventName: String, jsonData: String)
    fun onEvent(pattern: String, callback: (eventName: String, jsonData: String) -> Unit)
    val applicationContext: android.content.Context
}
```

### execute()
Wywołuje komendę na innym pluginie. Bypass auth — wewnętrzne wywołania zaufane.
HAL Service routuje jak zwykle (findForMethod → plugin.execute).

### emitEvent()
Plugin emituje event. Dociera do:
1. Plugin listenerów (onEvent) — z loop protection
2. Klientów WS/AIDL — przez TransportRegistry
3. Broadcast — jeśli event jest włączony w konfiguracji

### onEvent()
Nasłuchuj eventy z INNYCH pluginów. Wspiera wildcardy:
- "sunmi.scanner.*" — prefix match
- "*" — wszystko

**Loop protection:** plugin NIE dostaje eventów które sam wyemitował.
PluginContextImpl ma ownerPluginId — EventBus filtruje.

### Ograniczenia
- PluginContext dostępny TYLKO dla in-process pluginów
- Out-of-process (AIDL) pluginy NIE dostają PluginContext

## Eventy interfejsu — flow

Interfejs (`scanner`, `light`, …) nie transformuje eventów przez pośrednika. Provider emituje event
interfejsu **bezpośrednio, obok** swojego natywnego, a rdzeń oznacza go `source` = `pluginId` nadawcy.
Zastępuje to dawną transformację `{vendor}.* → {device}.*` w generyku.

```
1. Sunmi plugin:   emitEvent("sunmi.scanner.inner.barcode", {"data":"590...","rawData":"..."})
                   emitEvent("scanner.onScan", {"data":"590...","format":"EAN13"})   // event interfejsu
2. EventBus:       → oba do klientów WS/AIDL; source = "sunmi.scanner.inner" (ustawiany automatycznie)
3. Klient:         subscribe("scanner.onScan")                    → zunifikowany event z każdego skanera
                   subscribe("scanner.onScan@sunmi.scanner.inner") → tylko z wbudowanego skanera
```

Klient może subskrybować zarówno `scanner.onScan` (interfejs), jak i `sunmi.scanner.inner.barcode` (natywny).
Szczegóły: [`interfaces.md`](interfaces.md).

## Naming conventions

- Vendor-specific capabilities: prefixowane vendorem — `sunmi.printer`, `sunmi.scanner.inner`
- Interfejs: `interfaceId` bez prefixu — `printer`, `scanner`, `light`; definer ma `pluginId` `interface.{id}`
- Metody: `{capability}.{operation}` — `sunmi.printer.print`; metody interfejsu `{interfaceId}.{operation}` — `printer.printEscPos`
- Eventy: `{capability}.{event}` — `sunmi.scanner.inner.barcode`; event interfejsu `scanner.onScan` (z `source`)

## AIDL pluginów (out-of-process)

IHardwarePlugin.aidl:
```
interface IHardwarePlugin {
    String getPluginId();
    int getVersion();
    List<String> getCapabilities();
    String execute(String method, String jsonParams);
    String getDescriptorJson();     // JSON serialized PluginDescriptor
    void registerEventCallback(IPluginEventCallback callback);
    void unregisterEventCallback(IPluginEventCallback callback);
}
```

IPluginEventCallback.aidl:
```
interface IPluginEventCallback {
    void onEvent(String eventName, String jsonData);
    void onError(String deviceType, int code, String message);
}
```

## AidlPluginAdapter

Opakowuje IHardwarePlugin (Binder) → HalPlugin:
- execute() przenosi na Dispatchers.IO
- getDescriptor() parsuje JSON z getDescriptorJson()
- initialize() — rejestruje IPluginEventCallback
- Out-of-process pluginy NIE dostają PluginContext

## PluginServiceWrapper

Opakowuje HalPlugin → IHardwarePlugin.Stub:
Używany przez bundle APK (plugin-sunmi-bundle). Może żyć w hal-contract.

## EventBus

```kotlin
class EventBus {
    data class EventEnvelope(val eventName: String, val jsonData: String, val sourcePluginId: String)
    data class PluginListener(val listenerPluginId: String, val pattern: String, val callback: ...)

    fun emit(eventName: String, jsonData: String, sourcePluginId: String)
    fun addPluginListener(listenerPluginId: String, pattern: String, callback: ...)
    val events: SharedFlow<EventEnvelope>   // dla TransportRegistry

    companion object {
        fun matchesPattern(pattern: String, eventName: String): Boolean
        // "*" → true, "rfid.*" → prefix, exact → exact
    }
}
```

Loop protection: emit() iteruje plugin listeners, pomija te gdzie
listenerPluginId == sourcePluginId.

## PluginContextImpl

Per-plugin instancja:
```kotlin
class PluginContextImpl(
    private val ownerPluginId: String,
    private val registry: PluginRegistry,
    private val eventBus: EventBus,
    override val applicationContext: Context
) : PluginContext
```

emitEvent → eventBus.emit(sourcePluginId = ownerPluginId)
onEvent → eventBus.addPluginListener(listenerPluginId = ownerPluginId)

## PluginRegistry

- registerBuiltIn(plugin: HalPlugin)
- discoverExternal() → PackageManager → bindService → AidlPluginAdapter
- initializeAll(appContext) → PluginContextImpl per plugin → initialize()
- findForMethod(method) → mapuje prefix na capability → plugin
- allCapabilities(), getAllDescriptors()
- Kolejność: vendor-specific → generic → external → initializeAll()
