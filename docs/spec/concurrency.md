# Współbieżność i thread safety

## Źródła współbieżności

HAL Service obsługuje wielu klientów jednocześnie z różnych kanałów:
- AIDL: każde wywołanie Binder przychodzi na losowym wątku Binder thread pool
- WS: każda sesja ma coroutine, Ktor obsługuje N sesji równolegle
- HTTP: każdy request na osobnym coroutine (Ktor/Netty thread pool)
- Intent: Activity na main thread
- Plugin events: mogą być emitowane z dowolnego wątku (hardware callbacks)
- External plugins: AIDL Binder callbacks na Binder thread pool

## Zasady

### 1. CommandRouter — thread-safe, concurrent execute

Wielu klientów może wywołać execute() jednocześnie. CommandRouter musi być bezpieczny:
- TokenManager.validateToken() → Room DAO (thread-safe, Room obsługuje concurrent reads)
- PluginRegistry.findForMethod() → ConcurrentHashMap (thread-safe)
- plugin.execute() → odpowiedzialność pluginu (patrz niżej)

```kotlin
class CommandRouter {
    // Brak mutable state — deleguje do thread-safe komponentów
    suspend fun execute(token: String, method: String, params: String,
                        callerContext: CallerContext): String {
        // Każdy krok jest thread-safe
        val tokenEntity = tokenManager.validateToken(token, callerContext) ?: ...
        val plugin = pluginRegistry.findForMethod(method) ?: ...
        return plugin.execute(method, params)  // suspend — nie blokuje
    }
}
```

### 2. Plugin execute() — kontrakt współbieżności

Plugin MUSI obsługiwać concurrent calls do execute(). HAL Service nie serializuje
wywołań — dwa klienty mogą jednocześnie wywołać "printer.print".

Dla stubów: bezstanowe, thread-safe z natury.
Dla prawdziwych pluginów: jeśli sprzęt wymaga serializacji (np. drukarka),
plugin wewnętrznie używa Mutex:

```kotlin
class SunmiPrinterPlugin : HalPlugin {
    private val printMutex = Mutex()

    override suspend fun execute(method: String, params: String): String {
        return when (method) {
            "sunmi.printer.print" -> printMutex.withLock {
                // Tylko jeden print naraz
                actualPrint(params)
            }
            "sunmi.printer.status" -> {
                // Status można czytać concurrent — bez lock
                getStatus()
            }
            else -> errorJson("unsupported_method", method)
        }
    }
}
```

### 3. EventBus — thread-safe emission i delivery

```kotlin
class EventBus {
    // CopyOnWriteArrayList — safe for concurrent iteration + modification
    private val pluginListeners = CopyOnWriteArrayList<PluginListener>()

    // MutableSharedFlow — thread-safe, suspend-based
    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 256)

    fun emit(eventName: String, jsonData: String, sourcePluginId: String) {
        // Iteracja po CopyOnWriteArrayList — thread-safe
        for (listener in pluginListeners) { ... }
        // tryEmit — non-blocking, thread-safe
        _events.tryEmit(EventEnvelope(eventName, jsonData, sourcePluginId))
    }
}
```

### 4. TokenManager — Room handles concurrency

Room z WAL (Write-Ahead Logging, domyślne) obsługuje:
- Concurrent reads — bez problemu
- Single writer — Room serializuje writes wewnętrznie
- Suspend DAO methods — nie blokują main thread

### 5. PluginRegistry — ConcurrentHashMap

```kotlin
class PluginRegistry {
    private val plugins = ConcurrentHashMap<String, HalPlugin>()
    // registerBuiltIn, discoverExternal — wywoływane przy starcie (single-threaded)
    // findForMethod — wywoływane concurrent z wielu kanałów (read-only, safe)
}
```

### 6. Transport sesje

**AIDL:** `sessionTokens = ConcurrentHashMap<Int, String>` (uid → token)
**WS:** `sessions = ConcurrentHashMap<String, WsSession>` (sessionId → session)
Każda WsSession ma własny `subscribedEvents: CopyOnWriteArraySet<String>`.

### 7. BroadcastConfig

`enabledBroadcastEvents` odczytywane z SharedPreferences przy każdym pushEvent.
SharedPreferences jest thread-safe (Android gwarantuje).
Zmiany z Dashboard: `apply()` (asynchroniczny, non-blocking).

## Dispatcher strategy

| Komponent | Dispatcher | Powód |
|-----------|-----------|-------|
| CommandRouter.execute() | Caller's dispatcher | suspend, non-blocking |
| Plugin.execute() (in-process) | Caller's dispatcher | suspend |
| Plugin.execute() (AIDL, via adapter) | Dispatchers.IO | Binder call blokujący |
| Room DAO | Dispatchers.IO (Room robi to automatycznie) | Disk I/O |
| EventBus.emit() | Caller's thread | Non-blocking (tryEmit) |
| WS send | Ktor's dispatcher | Ktor zarządza |
| AIDL callback broadcast | Binder thread pool | RemoteCallbackList |
| Broadcast sendBroadcast | Caller's thread | Non-blocking |

## Graceful shutdown

HalService.onDestroy:
1. TransportRegistry.stopAll() — zamknij WS sesje, stop Ktor, unbind AIDL
2. PluginRegistry.disconnectAll() — unbind external plugins
3. EventBus — SharedFlow automatycznie zakończy collectors
4. CoroutineScope.cancel() — cleanup
