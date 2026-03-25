# Koordynacja startu Ktor

## Problem

transport-ws i transport-http oba używają Ktor, ale powinny współdzielić
jeden serwer na jednym porcie. Kto go tworzy, kto startuje, kto stopuje?

## Rozwiązanie: KtorServerManager w transport-ktor-core

KtorServerManager jest singletonem zarządzanym przez hal-service.
Transporty rejestrują swoje moduły, hal-service startuje serwer.

```
hal-service (posiada KtorServerManager)
    │
    ├─ tworzy KtorServerManager
    ├─ przekazuje go do transport-ws i transport-http przy start()
    ├─ po zarejestrowaniu modułów: manager.start(port)
    └─ przy shutdown: manager.stop()
```

## KtorServerManager API

```kotlin
// transport-ktor-core
class KtorServerManager {
    private var server: ApplicationEngine? = null
    private val modules = mutableListOf<Application.() -> Unit>()
    private var started = false

    /** Transport rejestruje swój routing/WS module */
    fun addModule(module: Application.() -> Unit) {
        check(!started) { "Cannot add modules after server started" }
        modules.add(module)
    }

    /** hal-service wywołuje po zarejestrowaniu wszystkich modułów */
    fun start(port: Int) {
        check(!started) { "Server already started" }
        server = embeddedServer(Netty, port = port) {
            // Wspólne features
            install(ContentNegotiation) { json() }

            // Moduły z transportów
            modules.forEach { it(this) }
        }.start(wait = false)
        started = true
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
        server = null
        started = false
    }

    val isRunning: Boolean get() = started
}
```

## TransportConfig rozszerzony

```kotlin
data class TransportConfig(
    val port: Int = 8400,
    val context: android.content.Context,
    val enabledBroadcastEvents: Set<String> = emptySet(),
    val ktorServerManager: KtorServerManager? = null  // Dla WS i HTTP
)
```

## Implementacja w transportach

### transport-ws

```kotlin
class WsTransport : CommandTransport, EventTransport {
    private var manager: KtorServerManager? = null

    override fun start(handler: CommandHandler, config: TransportConfig) {
        manager = config.ktorServerManager
            ?: throw IllegalStateException("WsTransport requires KtorServerManager")

        manager!!.addModule {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(30)
            }
            routing {
                webSocket("/ws") {
                    handleWsSession(handler, this)
                }
            }
        }
        // NIE wywołuje manager.start() — to robi hal-service
    }
}
```

### transport-http

```kotlin
class HttpTransport : CommandTransport {
    override fun start(handler: CommandHandler, config: TransportConfig) {
        val manager = config.ktorServerManager
            ?: throw IllegalStateException("HttpTransport requires KtorServerManager")

        manager.addModule {
            routing {
                post("/api/token") { handleToken(handler, call) }
                post("/api/execute") { handleExecute(handler, call) }
                get("/api/health") { handleHealth(handler, call) }
                get("/api/status") { handleStatus(handler, call) }
                get("/api/describe") { handleDescribe(handler, call) }
            }
        }
    }
}
```

## Sekwencja w HalService.onCreate

```kotlin
override fun onCreate() {
    super.onCreate()

    // 1. Utwórz shared KtorServerManager
    val ktorManager = KtorServerManager()

    // 2. Utwórz TransportConfig
    val config = TransportConfig(
        port = 8400,
        context = applicationContext,
        enabledBroadcastEvents = broadcastConfig.loadEnabledEvents(),
        ktorServerManager = ktorManager
    )

    // 3. Odkryj i zarejestruj transporty
    val transportRegistry = TransportRegistry()
    transportBootstrap.registerTransports(transportRegistry)

    // 4. Start transportów — każdy dodaje moduły do ktorManager
    transportRegistry.startAll(commandHandler, config)

    // 5. TERAZ start Ktor serwera (po zarejestrowaniu wszystkich modułów)
    if (ktorManager.hasModules) {
        ktorManager.start(config.port)
    }
}
```

## Gdy transport-ws lub transport-http nie są wkompilowane

KtorServerManager nie dostaje żadnych modułów → nie startuj serwera.
transport-aidl, transport-intent, transport-broadcast nie używają Ktor.

```kotlin
// W hal-service po startAll:
if (ktorManager.hasModules) {
    ktorManager.start(config.port)
    Log.i(TAG, "Ktor server started on port ${config.port}")
} else {
    Log.i(TAG, "No Ktor transports registered, skipping server start")
}
```

## Gdy tylko transport-ws jest wkompilowany (bez HTTP)

Działa bez zmian — WS transport rejestruje moduł, Ktor startuje, HTTP routes
nie istnieją. Analogicznie w drugą stronę.

## transport-ktor-core a ContentNegotiation

ContentNegotiation (JSON serialization) jest instalowane w KtorServerManager.start()
jako wspólny feature. transport-ws i transport-http nie muszą go instalować osobno.
Ale to oznacza że transport-ktor-core potrzebuje zależności na
ktor-server-content-negotiation + ktor-serialization-kotlinx-json.

Alternatywnie: każdy transport instaluje features w swoim module.
Ktor jest tolerancyjny na podwójne install() tego samego feature.
