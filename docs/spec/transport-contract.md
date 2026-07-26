# Kontrakt transportów

## CommandTransport — kanał komend

```kotlin
interface CommandTransport {
    val transportId: String        // "aidl", "ws", "http", "intent"
    val displayName: String        // "WebSocket", "HTTP REST"
    fun start(handler: CommandHandler, config: TransportConfig)
    fun stop()
    val isRunning: Boolean
}
```

## EventTransport — kanał eventów

```kotlin
interface EventTransport {
    val transportId: String        // "aidl_callback", "ws_stream", "broadcast"
    val displayName: String
    fun start(config: TransportConfig)
    fun stop()
    val isRunning: Boolean
    val isToggleable: Boolean      // true dla broadcast, intent
    var isEnabled: Boolean         // Runtime toggle w Dashboard
    fun pushEvent(eventName: String, jsonData: String, source: String)  // source = pluginId nadawcy (nagłówek)
}
```

## CommandHandler — implementowany przez hal-service

```kotlin
interface CommandHandler {
    suspend fun requestToken(request: String, callerContext: CallerContext): String
    suspend fun execute(token: String, method: String, params: String, callerContext: CallerContext): String
    suspend fun subscribe(token: String, events: String, callerContext: CallerContext): String
    suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext): String
    fun getStatus(): String
    fun describeApi(): String
}
```

## CallerContext

```kotlin
data class CallerContext(
    val transport: String,              // "aidl", "ws", "http", "intent"
    val packageName: String? = null,    // AIDL, Intent
    val certHash: String? = null,       // AIDL, Intent
    val origin: String? = null,         // WS, HTTP
    val remoteAddress: String? = null,  // WS, HTTP
    val callingUid: Int? = null         // AIDL, Intent
)
```

## TransportConfig

```kotlin
data class TransportConfig(
    val port: Int = 8400,
    val context: android.content.Context,
    val enabledBroadcastEvents: Set<String> = emptySet()
)
```

## TransportRegistry

```kotlin
class TransportRegistry {
    fun registerCommand(transport: CommandTransport)
    fun registerEvent(transport: EventTransport)
    fun startAll(handler: CommandHandler, config: TransportConfig)
    fun stopAll()
    fun getCommandTransports(): List<CommandTransport>
    fun getEventTransports(): List<EventTransport>
    fun pushEvent(eventName: String, jsonData: String, source: String)
    // → iteruje enabled EventTransports, wywołuje pushEvent na każdym (source = pluginId nadawcy)
}
```

## KtorServerManager (transport-ktor-core)

```kotlin
class KtorServerManager {
    fun addModule(module: Application.() -> Unit)
    fun start(port: Int)
    fun stop()
}
```

transport-ws i transport-http rejestrują swoje routing/WS handlery
w jednym shared serwerze zamiast stawiać osobne.

## transport-core nie zależy od Ktor

transport-core zawiera tylko interfejsy. Ktor jest w transport-ktor-core.
Transporty nie-Ktorowe (AIDL, Intent, Broadcast) nie zależą od Ktor.
