# Architektura HAL Service

## Przegląd

Hardware Abstraction Layer (HAL) Service — Foreground Service na Androidzie
udostępniający sprzęt (Sunmi, Zebra, Chainway itp.) innym aplikacjom.
Architektura pluginowa z modularnymi kanałami komunikacji.

## Moduły Gradle

### service/ — kontrakt + główna usługa
- `service/hal-contract` — AAR, interfejsy pluginów + PluginContext + PluginDescriptor
- `service/hal-service` — APK, Foreground Service z build flavors per urządzenie

### service/transport/ — kanały komunikacji (osobne AAR)
- `service/transport/transport-core` — interfejsy TransportChannel, CommandHandler, CallerContext
- `service/transport/transport-ktor-core` — zarządzanie lifecycle jednego shared Ktor serwera
- `service/transport/transport-aidl` — kanał AIDL (Binder IPC, komendy + eventy)
- `service/transport/transport-ws` — kanał WebSocket (komendy + eventy, via Ktor)
- `service/transport/transport-http` — kanał HTTP REST (komendy, via Ktor)
- `service/transport/transport-intent` — kanał Android Intent (komendy, Activity gateway)
- `service/transport/transport-broadcast` — kanał Android Broadcast (eventy push, publiczny)

### plugins/generic/ — pluginy abstrakcji
- `plugins/generic/plugin-generic-lib` — AAR, pluginy abstrakcji (printer, scanner)

### plugins/sunmi/ — pluginy Sunmi
- `plugins/sunmi/plugin-sunmi-printer-lib` — AAR, plugin drukarki Sunmi (stub)
- `plugins/sunmi/plugin-sunmi-scanner-lib` — AAR, plugin skanera Sunmi (stub)
- `plugins/sunmi/plugin-sunmi-bundle` — APK, standalone wrapper bundlujący sunmi pluginy

## Zależności

```
hal-contract                 ← brak
transport-core               ← hal-contract
transport-ktor-core          ← transport-core + ktor-server-core + ktor-server-netty
transport-aidl               ← transport-core (+ aidl=true)
transport-ws                 ← transport-core + transport-ktor-core + ktor-websockets + serialization
transport-http               ← transport-core + transport-ktor-core + ktor-content-negotiation + serialization
transport-intent             ← transport-core + appcompat
transport-broadcast          ← transport-core
plugin-generic-lib           ← hal-contract
plugin-sunmi-printer-lib     ← hal-contract + core-ktx
plugin-sunmi-scanner-lib     ← hal-contract + core-ktx
plugin-sunmi-bundle          ← hal-contract + plugin-sunmi-printer-lib + plugin-sunmi-scanner-lib
hal-service                  ← hal-contract + transport-core + transport-ktor-core
                               + all transport-* (opcjonalne)
                               + plugin-generic-lib (zawsze)
                               + vendor plugins per flavor
                               + Room + serialization + coroutines + nimbus-jose-jwt
```

## Build flavors hal-service

Dwa niezależne wymiary flavorów:

- **`device`** — które vendor-pluginy trafiają na classpath (`generic` bez vendor-pluginów,
  `sunmi` + sunmi plugins).
- **`stability`** — czy metody experimental są wkompilowane (`stable` = wycięte, `development` =
  obecne, plus opcje developerskie jak konfigurowalny adres/port nasłuchu).

Wymiar `stability` jest **wspólny z modułami pluginów**, więc wybór jednego Build Variantu w
Android Studio przełącza aplikację i wszystkie pluginy naraz (AGP dopasowuje warianty po nazwie
wymiaru — bez `missingDimensionStrategy`). Cztery warianty: `genericStable`, `genericDevelopment`,
`sunmiStable`, `sunmiDevelopment`.

```kotlin
flavorDimensions += listOf("device", "stability")
productFlavors {
    create("generic") { dimension = "device" }   // bez vendor pluginów
    create("sunmi")   { dimension = "device" }    // + sunmi plugins
    create("stable")  { dimension = "stability" } // experimental wycięte
    create("development") {                        // pełny build + opcje dev
        dimension = "stability"
        buildConfigField("boolean", "DEVELOPMENT", "true")
    }
}
dependencies {
    implementation(project(":plugins:generic:plugin-generic-lib"))  // zawsze
    // Podzbiór produkcyjny — w sunmiStable i sunmiDevelopment:
    "sunmiImplementation"(project(":plugins:sunmi:plugin-sunmi-scanner-lib"))
    // Tylko w pełnym buildzie (kombinacja sunmi+development):
    "sunmiDevelopmentImplementation"(project(":plugins:sunmi:tms:plugin-sunmi-system-lib"))
}
```

Metody experimental są wycinane na etapie kompilacji modułu pluginu (wariant `stable` →
`stripExperimental()` w `getDescriptor()` na podstawie `BuildConfig.WITH_EXPERIMENTAL`), a deskryptor
jest jedynym źródłem prawdy o tym, co wywoływalne (guard w `BaseHalPlugin` i `PluginRegistry`).

Jeśli po wycięciu w pluginie nie zostaje nic — tak jest dla pluginu experimental jako całości, gdzie
`stripExperimental()` zwraca pusty deskryptor — `PluginRegistry` go **nie rejestruje**. Klasa jest
wprawdzie dalej na classpath, ale plugin bez metod i bez eventów nie ma czego oferować, więc nie
pojawia się ani na liście w Dashboardzie, ani w `system.describe`. Tak samo traktowany jest plugin
zewnętrzny, który przyszedł pusty ze swojego builda `stable` — bez tego stable hal-service z
zewnętrznym bundlem pokazywał puste wpisy pluginów.

Runtime'owe bramkowanie experimental (uprawnienie `experimental` z tokenu, ręczne włączenie per
plugin w Dashboardzie, filtr w `system.describe`) jest niezależne od tego, jak zbudowano usługę, i
działa tak samo dla pluginów wbudowanych i zewnętrznych.

Transporty NIE są flavorami — są compile-time dependencies.
Wyłączenie kanału = zakomentowanie linii w dependencies.

Kod wykrywa wkompilowane transporty i pluginy przez refleksję:
```kotlin
fun tryRegister(className: String) {
    try { Class.forName(className)... } catch (e: ClassNotFoundException) { /* skip */ }
}
```

## Wzorzec pluginów

Granulacja: jeden moduł AAR per plugin (nie per vendor).

```
plugins/{vendor}/plugin-xxx-yyy-lib/    ← AAR z logiką jednego pluginu (HalPlugin)
plugins/{vendor}/plugin-xxx-bundle/     ← APK wrapper bundlujący wiele plugin-xxx-*-lib
```

Bundle APK ma osobny Service per plugin:
```xml
<service android:name=".SunmiPrinterService" android:exported="true">
    <intent-filter><action android:name="dev.duma.android.hal.HARDWARE_PLUGIN" /></intent-filter>
    <meta-data android:name="plugin.id" android:value="sunmi.printer" />
</service>
<service android:name=".SunmiScannerService" android:exported="true">
    <intent-filter><action android:name="dev.duma.android.hal.HARDWARE_PLUGIN" /></intent-filter>
    <meta-data android:name="plugin.id" android:value="sunmi.scanner" />
</service>
```

## Scenariusze bundlowania

```
A: Wkompiluj wszystko (sunmi flavor)
   hal-service ← plugin-sunmi-printer-lib + plugin-sunmi-scanner-lib

B: Zewnętrzny bundle APK (generic flavor)
   hal-service (bez vendor pluginów)
   plugin-sunmi-bundle.apk ← oba plugin libs

C: Mix — drukarka wkompilowana, skaner jako APK
   hal-service ← plugin-sunmi-printer-lib
   plugin-sunmi-scanner.apk ← plugin-sunmi-scanner-lib
```

## Kanały komunikacji

### 4 kanały komend (request → response)

| Kanał | Transport | Autoryzacja | Wymaga połączenia |
|-------|-----------|-------------|-------------------|
| AIDL  | Binder IPC | authenticate(token) na sesję | Tak (bind) |
| WS    | WebSocket JSON | authenticate(token) po connect | Tak (persistent) |
| HTTP  | REST JSON | Bearer token per request | Nie |
| Intent | Android Intent | token w extra | Nie |

### 3 kanały eventów (push)

| Kanał | Transport | Mechanizm | Wymaga połączenia |
|-------|-----------|-----------|-------------------|
| AIDL callback | Binder IPC | RemoteCallbackList + subskrypcje | Tak |
| WS stream | WebSocket JSON | Server push + subskrypcje | Tak |
| Broadcast | Android Intent | sendBroadcast, bez autoryzacji | Nie |

Plugin nie wie skąd przyszła komenda. Format zawsze: `(method, params) → result`.

## Struktura pakietów hal-service

```
service/hal-service/src/main/java/.../
├── auth/                 — TokenManager, ServiceKeyVerifier, AuthManager, GrantPermissionActivity
├── core/                 — ServiceCommandHandler, TransportBootstrap
├── plugin/               — PluginRegistry, PluginContextImpl, EventBus
├── config/               — BroadcastConfig, TransportConfig
└── service/              — HalService, BootReceiver, DashboardActivity
```

## Kolejność inicjalizacji w HalService.onCreate

1. Auth (TokenManager, ServiceKeyVerifier, AuthManager)
2. EventBus
3. PluginRegistry
4. Rejestruj vendor-specific pluginy (refleksja per flavor)
5. Odkryj external pluginy (PackageManager)
6. Rejestruj definery interfejsów + pluginy współdzielone (zawsze)
7. initializeAll na pluginach (PluginContext per plugin)
8. Transporty (refleksja) → TransportRegistry
9. Start transportów
10. Bridge: EventBus.events → TransportRegistry.pushEvent()
11. Foreground notification
