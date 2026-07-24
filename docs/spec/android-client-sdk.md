# Android Client SDK

**Status: PRZYSZŁA IMPLEMENTACJA.**

Natywna biblioteka kliencka dla Android — wrapper ułatwiający integrację
z HAL Service z poziomu React Native, Capacitor, i natywnych apek Android.

## Moduły

```
hal-client-core/        ← AAR, wspólne interfejsy i modele
hal-client-aidl/        ← AAR, klient AIDL (bind + callbacks)
hal-client-ws/          ← AAR, klient WebSocket
hal-client-http/        ← AAR, klient HTTP
hal-client-intent/      ← AAR, klient Intent (fire-and-forget)
hal-client-auto/        ← AAR, automatyczny wybór najlepszego kanału
```

## hal-client-core — wspólny interfejs

```kotlin
interface HalClient {
    // Autoryzacja
    suspend fun requestToken(serviceKey: String? = null, clientId: String): TokenResult
    suspend fun authenticate(token: String): Boolean

    // Komendy
    suspend fun execute(method: String, params: String = "{}"): String

    // Subskrypcje (nie wszystkie kanały wspierają)
    suspend fun subscribe(events: List<String>): SubscribeResult
    suspend fun unsubscribe(events: List<String>)
    fun onEvent(callback: (eventName: String, jsonData: String, source: String) -> Unit): Disposable  // source = pluginId nadawcy

    // System
    suspend fun ping(): Boolean
    suspend fun status(): String
    suspend fun describe(): String

    // Lifecycle
    fun connect()
    fun disconnect()
    val isConnected: Boolean
}

data class TokenResult(
    val token: String?,
    val permissions: List<String>,
    val error: String?
)

data class SubscribeResult(
    val subscribed: List<String>,
    val denied: List<String>   // Brak uprawnień
)

fun interface Disposable {
    fun dispose()
}
```

## hal-client-aidl

```kotlin
class AidlHalClient(context: Context) : HalClient {
    // bindService → IHalService
    // authenticate → sesja per Binder
    // onEvent → IHalCallback registered
    // subscribe/unsubscribe → IHalService.subscribe()
    // Auto-rebind on disconnect
}
```

Najszybszy kanał. Wymaga bycia na tym samym urządzeniu.
React Native: native module wrappujący AidlHalClient.
Capacitor: plugin Javy/Kotlina wrappujący AidlHalClient.

## hal-client-ws

```kotlin
class WsHalClient(url: String = "ws://localhost:8400/ws") : HalClient {
    // WebSocket connection
    // JSON-RPC-like protocol
    // Auto-reconnect z exponential backoff
    // Event push przez onMessage
}
```

Działa zarówno z tego samego urządzenia jak i zdalnie (w sieci lokalnej).
Pełne wsparcie eventów i subskrypcji.

## hal-client-http

```kotlin
class HttpHalClient(baseUrl: String = "http://localhost:8400/api") : HalClient {
    // OkHttp / Ktor client
    // Bearer token per request
    // subscribe/unsubscribe → UnsupportedOperationException
    // onEvent → UnsupportedOperationException (stateless)
}
```

Najprostszy. Bez eventów. Dobry do jednorazowych operacji.

## hal-client-intent

```kotlin
class IntentHalClient(context: Context) : HalClient {
    // startActivityForResult z actions dev.duma.hal.*
    // Bez subscribe/onEvent (jednorazowy)
    // Najprostszy, zero setup, działa z Tasker
}
```

## hal-client-auto

Automatyczny wybór najlepszego kanału:

```kotlin
class AutoHalClient(context: Context) : HalClient {
    // Priorytet: AIDL → WS → HTTP → Intent
    // 1. Próbuj bind AIDL (najszybszy, pełne eventy)
    // 2. Jeśli AIDL niedostępny → WS (eventy OK)
    // 3. Jeśli WS niedostępny → HTTP (bez eventów)
    // 4. Fallback: Intent (jednorazowy)
    //
    // Auto-fallback: jeśli AIDL disconnect → przełącz na WS
    // Transparent dla klienta — ten sam interfejs
}
```

## Użycie w React Native

```kotlin
// Android native module
class HalModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val client = AutoHalClient(reactContext)

    @ReactMethod
    fun requestToken(serviceKey: String?, clientId: String, promise: Promise) {
        scope.launch {
            val result = client.requestToken(serviceKey, clientId)
            promise.resolve(result.toWritableMap())
        }
    }

    @ReactMethod
    fun execute(method: String, params: String, promise: Promise) {
        scope.launch {
            val result = client.execute(method, params)
            promise.resolve(result)
        }
    }

    @ReactMethod
    fun subscribe(events: ReadableArray) {
        scope.launch {
            client.subscribe(events.toStringList())
        }
    }

    // Eventy → RN EventEmitter
    init {
        client.onEvent { event, data, source ->
            sendEvent(reactContext, "HalEvent", Arguments.createMap().apply {
                putString("event", event)
                putString("data", data)
                putString("source", source)
            })
        }
    }
}
```

## Użycie w Capacitor

```kotlin
@CapacitorPlugin(name = "HalService")
class HalServicePlugin : Plugin() {
    private lateinit var client: HalClient

    override fun load() {
        client = AutoHalClient(context)
    }

    @PluginMethod
    fun execute(call: PluginCall) {
        scope.launch {
            val result = client.execute(
                call.getString("method")!!,
                call.getString("params") ?: "{}"
            )
            call.resolve(JSObject(result))
        }
    }
}
```

## Kolejność implementacji (przyszła)

1. hal-client-core — interfejsy
2. hal-client-aidl — najbardziej potrzebny (natywne apki)
3. hal-client-ws — dla web-based frameworks
4. hal-client-auto — convenience wrapper
5. hal-client-http, hal-client-intent — na żądanie
6. React Native module, Capacitor plugin — na żądanie
