# Instrukcja tworzenia nowego pluginu HAL Service

Dokument referencyjny dla agenta AI. Kompletne, krok po kroku instrukcje tworzenia nowego pluginu w systemie HAL Service.

**Projekt bazowy:** `/Users/kduma/AndroidStudioProjects/HALService`

---

## 1. Okreslenie typu pluginu

Przed rozpoczeciem pracy musisz ustalisc, jaki typ pluginu tworzysz. Istnieja trzy typy:

### Typ A: Vendor-specific plugin library (AAR) -- `plugin-{vendor}-{device}-lib`
- Biblioteka Android (AAR), modul library
- Zawiera jedna klase implementujaca `HalPlugin`
- Obsluguje konkretne urzadzenie od konkretnego producenta
- NIE wymaga `PluginContext` (nie korzysta z komunikacji miedzy-pluginowej)
- Pakiet: `dev.duma.android.hal.plugins.{vendor}.{device}`
- Przyklad: `plugin-sunmi-printer-lib` z klasa `SunmiPrinterPlugin`

**Wybierz ten typ gdy:** dodajesz obsluge konkretnego urzadzenia hardwarowego od konkretnego vendora (np. drukarka Sunmi, skaner Zebra, czytnik RFID Chainway).

### Typ B: Bundle APK -- `plugin-{vendor}-bundle`
- Aplikacja Android (APK), modul application
- Grupuje wiele vendor-specific plugin-lib modulow jednego vendora w jeden APK
- Jeden `Service` na kazdy plugin, kazdy opakowuje `PluginServiceWrapper`
- Pakiet: `dev.duma.android.hal.plugins.{vendor}.bundle`
- Przyklad: `plugin-sunmi-bundle` z `SunmiPrinterService` i `SunmiScannerService`

**Wybierz ten typ gdy:** chcesz wdrozyc pluginy vendora jako oddzielna aplikacje (out-of-process), a nie kompilowac je bezposrednio do hal-service.

### Typ C: Interfejs (definer + provider binding)
- Zunifikowany, autorytatywny kontrakt, pod ktory wiele pluginow podpina sie jako providerzy.
- **Definer** (np. `PrinterInterface`, `ScannerInterface`, `LightInterface` w `plugin-generic-lib`) tylko
  *rejestruje* `InterfaceContract` (`definesInterfaces`) -- nie ma sprzetu ani capabilities.
- **Provider** to zwykly vendor-plugin, ktory dodaje `interfaces = [InterfaceBinding(...)]` i routuje metody
  kontraktu do swoich handlerow. Zero edycji definera przy dodaniu nowego providera (open/closed).
- Rdzen wybiera providera per-wywolanie (`__provider`) albo domyslnego; eventy niosa `source`.
- Zastepuje dawne „generic abstraction" pluginy (owijki z zaszyta lista vendorow).

**Wybierz ten typ gdy:** chcesz zunifikowany interfejs dla danego typu urzadzenia z wieloma wymiennymi
providerami. Pelny opis warstwy: [`interfaces.md`](interfaces.md).

---

## 2. Konwencje nazewnictwa

### Zmienne do zastapienia w szablonach

| Placeholder | Opis | Przyklad |
|---|---|---|
| `{vendor}` | Nazwa producenta, lowercase | `sunmi`, `zebra`, `chainway` |
| `{device}` | Typ urzadzenia, lowercase | `printer`, `scanner`, `rfid` |
| `{Vendor}` | Nazwa producenta, PascalCase | `Sunmi`, `Zebra`, `Chainway` |
| `{Device}` | Typ urzadzenia, PascalCase | `Printer`, `Scanner`, `Rfid` |

### Reguly nazewnictwa

| Element | Format | Przyklad |
|---|---|---|
| pluginId vendor-specific | `{vendor}.{device}` | `sunmi.printer` |
| pluginId definera interfejsu | `interface.{device}` | `interface.printer` |
| interfaceId (kontrakt) | `{device}` | `printer` |
| Capability | identyczny z pluginId (definer: brak) | `sunmi.printer` |
| Metoda | `{capability}.{operacja}` | `sunmi.printer.print` |
| Event | `{capability}.{event}` | `sunmi.scanner.barcode` |
| Modul library | `plugin-{vendor}-{device}-lib` | `plugin-sunmi-printer-lib` |
| Modul bundle | `plugin-{vendor}-bundle` | `plugin-sunmi-bundle` |
| Pakiet library | `dev.duma.android.hal.plugins.{vendor}.{device}` | |
| Pakiet bundle | `dev.duma.android.hal.plugins.{vendor}.bundle` | |
| Klasa pluginu | `{Vendor}{Device}Plugin` | `SunmiPrinterPlugin` |
| Klasa serwisu | `{Vendor}{Device}Service` | `SunmiPrinterService` |

---

## 3. Tworzenie vendor-specific plugin library (Typ A)

### 3.1 Struktura katalogow

```
plugins/{vendor}/plugin-{vendor}-{device}-lib/
  src/main/
    java/dev/duma/android/hal/plugins/{vendor}/{device}/
      {Vendor}{Device}Plugin.kt
    AndroidManifest.xml
  build.gradle.kts
  consumer-rules.pro
  proguard-rules.pro
  .gitignore
```

### 3.2 `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.duma.android.hal.plugins.{vendor}.{device}"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":service:hal-contract"))
    implementation(libs.androidx.core.ktx)
}
```

### 3.3 `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>
```

### 3.4 Pliki `consumer-rules.pro` i `.gitignore`

- `consumer-rules.pro` -- pusty plik
- `.gitignore` -- zawiera `/build`
- `proguard-rules.pro` -- standardowy szablon (skopiuj z istniejacego plugin-lib)

### 3.5 Klasa pluginu -- BEZ eventow

```kotlin
package dev.duma.android.hal.plugins.{vendor}.{device}

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Stub implementation of {Vendor} {device} plugin. Returns hardcoded responses
 * simulating {device} operations. Will be replaced with real {Vendor} SDK
 * integration in production. Accepts optional [Context] for hardware SDK access.
 */
class {Vendor}{Device}Plugin(private val appContext: Context? = null) : HalPlugin {

    override val pluginId = "{vendor}.{device}"
    override val version = 1

    private var callback: HalPluginEventCallback? = null

    override fun getCapabilities(): List<String> = listOf("{vendor}.{device}")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor("{vendor}.{device}.{operacja1}", "Opis operacji 1", "{vendor}.{device}"),
            MethodDescriptor("{vendor}.{device}.{operacja2}", "Opis operacji 2", "{vendor}.{device}")
        ),
        events = emptyList()
    )

    override fun initialize(pluginContext: PluginContext) {
        // Stub -- no PluginContext usage needed
    }

    override suspend fun execute(method: String, params: String): String {
        return when (method) {
            "{vendor}.{device}.{operacja1}" -> """{"status":"ok"}"""
            "{vendor}.{device}.{operacja2}" -> """{"status":"ok"}"""
            else -> """{"error":"unsupported_method","method":"$method"}"""
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }
}
```

**Konstruktor z `Context`:** Vendor-specific pluginy przyjmuja opcjonalny `android.content.Context` w konstruktorze (`Context? = null`). Umozliwia to dostep do Android SDK urzadzenia (bindowanie serwisow, rejestracja BroadcastReceiverow itp.) jeszcze przed wywolaniem `initialize()`. Domyslna wartosc `null` zapewnia kompatybilnosc z konstrukcja bez argumentow.

### 3.6 Klasa pluginu -- Z eventami

Identyczna jak wyzej (wlacznie z konstruktorem `Context? = null`), z roznicami:

W `getDescriptor()` dodaj eventy:
```kotlin
events = listOf(
    EventDescriptor("{vendor}.{device}.{event1}", "Opis eventu", "{vendor}.{device}")
)
```

Emitowanie eventow (w prawdziwej implementacji):
```kotlin
callback?.onEvent("{vendor}.{device}.{event1}", """{"data":"..."}""")
```

### 3.7 Kontrakt wspolbieznosci

`execute()` MUSI byc thread-safe. Dla stubow -- bezstanowe, OK. Dla prawdziwego hardware -- uzyj `Mutex`:

```kotlin
private val mutex = Mutex()

override suspend fun execute(method: String, params: String): String {
    return mutex.withLock {
        when (method) { ... }
    }
}
```

---

## 4. Tworzenie Bundle APK (Typ B)

Bundle APK tworzy sie TYLKO jesli istnieja juz vendor-specific plugin-lib moduly. Jesli bundle dla vendora juz istnieje, przejdz do sekcji 4.6.

### 4.1 `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.duma.android.hal.plugins.{vendor}.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.plugins.{vendor}"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":service:hal-contract"))
    implementation(project(":plugins:{vendor}:plugin-{vendor}-{device1}-lib"))
    implementation(project(":plugins:{vendor}:plugin-{vendor}-{device2}-lib"))
}
```

### 4.2 `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true">

        <service
            android:name=".{Vendor}{Device1}Service"
            android:exported="true">
            <intent-filter>
                <action android:name="dev.duma.android.hal.HARDWARE_PLUGIN" />
            </intent-filter>
            <meta-data android:name="plugin.id" android:value="{vendor}.{device1}" />
            <meta-data android:name="plugin.label" android:value="{Vendor} {Device1}" />
        </service>

    </application>

</manifest>
```

Krytyczne elementy kazdego `<service>`:
- `android:exported="true"`
- intent-filter: `dev.duma.android.hal.HARDWARE_PLUGIN`
- meta-data `plugin.id` -- MUSI zgadzac sie z `pluginId` w klasie pluginu
- meta-data `plugin.label` -- czytelna etykieta

### 4.3 Klasa serwisu

```kotlin
package dev.duma.android.hal.plugins.{vendor}.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.{vendor}.{device}.{Vendor}{Device}Plugin

class {Vendor}{Device}Service : Service() {
    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper({Vendor}{Device}Plugin(applicationContext))
    }
}
```

### 4.4 Zasoby

- `res/values/strings.xml` z `app_name`
- Pliki ikon -- skopiuj z `plugins/sunmi/plugin-sunmi-bundle/src/main/res/`

### 4.5 Plik `.gitignore`

Zawiera `/build`

### 4.6 Dodawanie nowego pluginu do istniejacego bundle

1. Dodaj zaleznosc w `build.gradle.kts`: `implementation(project(":plugins:{vendor}:plugin-{vendor}-{nowyDevice}-lib"))`
2. Dodaj klase serwisu (szablon z 4.3)
3. Dodaj `<service>` w `AndroidManifest.xml` (szablon z 4.2)

---

## 5. Tworzenie interfejsu (Typ C)

Interfejs = **definer** (rejestruje kontrakt) + jeden lub wiele **providerow** (wiaza sie z nim). Definer
dodajesz jako klase do istniejacego modulu `plugin-generic-lib` (NIE tworzysz nowego modulu Gradle).
Provider to zwykly vendor-plugin (Typ A/B), ktory tylko dodaje binding i routing. Pelny opis warstwy:
[`interfaces.md`](interfaces.md).

### 5.1 Definer -- rejestruje `InterfaceContract`

Plik: `plugins/generic/plugin-generic-lib/src/main/java/dev/duma/android/hal/plugins/generic/{Device}Interface.kt`

```kotlin
package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.InterfaceFeature
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/** Definer interfejsu `{device}` -- tylko rejestruje kontrakt, nie ma sprzetu ani capabilities. */
class {Device}Interface : HalPlugin {

    override val pluginId = "interface.{device}"     // definer, nie provider
    override val version = 1

    override fun isSupported(): Boolean = true
    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] {Device}",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf({DEVICE}_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Nigdy nie routowane tutaj -- metody interfejsu wykonuje rozwiazany provider.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "{device}"

        val {DEVICE}_CONTRACT = InterfaceContract(
            interfaceId = "{device}",
            version = 1,
            methods = listOf(
                MethodDescriptor("{device}.{operacja1}", "Opis", PERMISSION,
                    exampleParameters = "{}", exampleOutput = "{}"),
                MethodDescriptor("{device}.{operacja2}", "Opis", PERMISSION,
                    exampleParameters = "{}", exampleOutput = "{}")
            ),
            // Event opcjonalny -- providerzy emituja go z automatycznym `source` = pluginId.
            events = listOf(
                EventDescriptor("{device}.{event1}", "Opis (unified)", PERMISSION, exampleEvent = "{}")
            ),
            // Cecha method-level bramkuje cala metode; param-level (pusta `methods`) egzekwuje provider.
            features = listOf(
                InterfaceFeature("{cecha}", "Opis cechy", methods = listOf("{device}.{operacja2}"))
            )
        )
    }
}
```

### 5.2 Provider -- opt-in `InterfaceBinding` + routing

W istniejacym vendor-pluginie (Typ A/B) dodaj binding do deskryptora i gałąź routingu w `execute()`:

```kotlin
// Deskryptor providera -- oprocz natywnych metod/groups:
interfaces = listOf(
    InterfaceBinding("{device}", priority = 100, features = listOf("{cecha}"))
)

// execute()/handleExecute() -- metody kontraktu do wlasnych handlerow:
if (method.startsWith("{device}.")) return when (method) {
    "{device}.{operacja1}" -> /* -> wlasny handler / SDK */
    "{device}.{operacja2}" -> /* -> wlasny handler / SDK */
    else -> CommandResult.unsupportedMethod(method)
}
```

Jesli interfejs ma event, provider emituje go **obok** swojego natywnego eventu (`source` ustawiany
automatycznie przez `emitEvent`/`setEventCallback`), np. skaner:

```kotlin
emitEvent("{vendor}.{device}.barcode", payload)   // natywny
emitEvent("{device}.{event1}", payload)            // unified -- source = pluginId
```

---

## 6. Rejestracja w projekcie

### 6.1 `settings.gradle.kts`

Dodaj `include()`:

```kotlin
include(":plugins:{vendor}:plugin-{vendor}-{device}-lib")  // dla vendor lib
include(":plugins:{vendor}:plugin-{vendor}-bundle")         // dla bundle APK
// Generic: NIE DODAWAJ -- modul juz istnieje
```

### 6.2 `service/hal-service/build.gradle.kts`

#### Nowy vendor (nowy flavor):

```kotlin
// W productFlavors:
create("{vendor}") { dimension = "device" }

// W dependencies:
"{vendor}Implementation"(project(":plugins:{vendor}:plugin-{vendor}-{device}-lib"))
```

#### Istniejacy vendor (np. sunmi):

```kotlin
// Tylko zaleznosc:
"sunmiImplementation"(project(":plugins:sunmi:plugin-sunmi-{nowyDevice}-lib"))
```

#### Definer interfejsu: nic nie dodawaj (`plugin-generic-lib` juz jest zaleznoscia).
#### Bundle APK: nic nie dodawaj (osobna aplikacja).

### 6.3 `HalService.kt`

Plik: `service/hal-service/src/main/java/dev/duma/android/hal/service/service/HalService.kt`

#### Vendor-specific -- dodaj w sekcji krok 5 (vendor-specific plugins):
```kotlin
tryRegisterPlugin("dev.duma.android.hal.plugins.{vendor}.{device}.{Vendor}{Device}Plugin")
```

`tryRegisterPlugin()` automatycznie probuje najpierw konstruktor z `Context` (przekazuje `applicationContext`), a jesli taki nie istnieje -- uzywa konstruktora bezargumentowego. Dzieki temu pluginy z konstruktorem `(Context? = null)` otrzymaja `Context` automatycznie.

#### Definer interfejsu -- dodaj w sekcji krok 7 (interface definers):
```kotlin
tryRegisterPlugin("dev.duma.android.hal.plugins.generic.{Device}Interface")
```
Provider (vendor-plugin) rejestrujesz normalnie w sekcji krok 5 -- binding dziala z jego deskryptora.

#### Bundle APK: nic nie dodawaj -- discovery automatyczne przez `discoverExternal()`.

### 6.4 Podpiecie nowego providera pod istniejacy interfejs

Nie edytujesz definera. Nowy vendor-plugin, ktory ma udostepnic istniejacy interfejs (np. `printer`),
tylko **deklaruje binding** i routuje metody kontraktu do swoich handlerow:

```kotlin
// W deskryptorze vendor-pluginu:
interfaces = listOf(
    InterfaceBinding("printer", priority = 50, features = listOf("escpos", "image"))
)

// W execute()/handleExecute() -- gałąź dla metod kontraktu:
if (method.startsWith("printer.")) return when (method) {
    "printer.printEscPos" -> /* -> wlasny handler */
    "printer.printImage"  -> /* -> wlasny handler */
    else -> CommandResult.unsupportedMethod(method)
}
```

`features` deklaruje, ktore method-level cechy ten provider wspiera (np. brak `zpl` → `printer.printZpl`
odrzucone przez rdzen). `priority` decyduje o domyslnym providerze. Szczegoly: [`interfaces.md`](interfaces.md).

---

## 7. Testy

### 7.1 Test interfejsu (WYMAGANY dla definera/providera)

Interfejsy testuje sie na poziomie **rejestru** (`PluginRegistry`) -- rejestracja kontraktu, rozwiazanie
providera (domyslny vs `__provider`), bramkowanie cech, filtr dostepnosci. Wzor:
`service/hal-service/src/test/java/dev/duma/android/hal/service/plugin/PluginRegistryInterfaceTest.kt`.

```kotlin
@Test
fun `interface resolves to highest-priority provider and gates features`() = runTest {
    val registry = PluginRegistry()
    registry.registerBuiltIn({Device}Interface())          // definer rejestruje kontrakt
    registry.registerBuiltIn(
        FakeProvider("{vendor}.{device}",
            InterfaceBinding("{device}", priority = 100, features = listOf("{cecha}")))
    )

    // Domyslny provider (bez __provider) -> najwyzszy priorytet, zwracany w naglowku:
    val ok = registry.executeInterface("{device}", null, "{device}.{operacjaWspierana}", "{}")
    assertEquals("{vendor}.{device}", (ok as CommandResult.Success).provider)

    // Metoda bramkowana cecha, ktorej provider nie ma -> unavailable:
    val gated = registry.executeInterface("{device}", null, "{device}.{operacjaBezCechy}", "{}")
    assertEquals("unavailable", (gated as CommandResult.Failure).code)
}
```

Providerzy sprzetowi (Sunmi) sa weryfikowani przez CI (oba flavory) -- rejestracja bindingu i routing
metod kontraktu, bez realnego SDK, sprawdza test rejestru z `FakeProvider` o tym samym `pluginId`.

Uruchomienie: `./gradlew :service:hal-service:test`

---

## 8. Pelna procedura -- kolejnosc krokow

### Scenariusz A: Nowy vendor-specific plugin (in-process)
1. Utworz modul `plugin-{vendor}-{device}-lib` (sekcja 3)
2. `settings.gradle.kts` -- dodaj `include()` (sekcja 6.1)
3. `service/hal-service/build.gradle.kts` -- flavor + zaleznosc (sekcja 6.2)
4. `HalService.kt` -- dodaj `tryRegisterPlugin()` w sekcji vendor (sekcja 6.3)
5. (Opcjonalnie) Podepnij provider pod istniejacy interfejs (sekcja 6.4)

### Scenariusz B: Nowy vendor-specific plugin (out-of-process / bundle)
1. Utworz modul `plugin-{vendor}-{device}-lib` (sekcja 3)
2. Utworz/zaktualizuj `plugin-{vendor}-bundle` (sekcja 4)
3. `settings.gradle.kts` -- dodaj oba moduly (sekcja 6.1)
4. NIE dodawaj do `service/hal-service/build.gradle.kts`
5. NIE dodawaj `tryRegisterPlugin()` -- discovery automatyczne
6. (Opcjonalnie) Podepnij provider pod istniejacy interfejs (sekcja 6.4)

### Scenariusz C: Nowy interfejs
1. Utworz definer w `plugins/generic/plugin-generic-lib` (sekcja 5.1)
2. `HalService.kt` -- dodaj `tryRegisterPlugin()` w sekcji interface definers (sekcja 6.3)
3. Napisz testy rejestru (sekcja 7)

### Scenariusz D: Interfejs + pierwszy provider
1-2 ze Scenariusza C, potem w vendor-pluginie dodaj `InterfaceBinding` + routing (sekcja 5.2)

---

## 9. Wazne reguly

1. **Kolejnosc rejestracji:** vendor-specific PRZED interface definers PRZED initializeAll()
2. **Definer interfejsu** nie ma capabilities ani sprzetu -- tylko `definesInterfaces`
3. **Out-of-process NIE otrzymuja PluginContext**
4. **`execute()` musi byc thread-safe**
5. **Nazwy metod MUSZA odpowiadac descriptorowi**
6. **pluginId musi byc unikalny** w calym systemie
7. **Capabilities musza byc unikalne** -- ta sama capability nie moze byc w dwoch pluginach
8. **Konstruktor pluginu:** Vendor-specific pluginy MUSZA miec konstruktor z opcjonalnym `Context` parametrem: `(appContext: Context? = null)`. `tryRegisterPlugin()` uzywa refleksji -- probuje najpierw konstruktor `(Context)`, potem bezargumentowy. Bundle serwisy przekazuja `applicationContext` wprost. Definery interfejsu moga miec bezargumentowy konstruktor (nie potrzebuja `Context`)
9. **Kazdy plugin-lib MUSI zalezec od `:service:hal-contract`**

---

## 10. Checklist

### Vendor-specific plugin-lib:
- [ ] Modul istnieje z `build.gradle.kts`, `AndroidManifest.xml`, `consumer-rules.pro`, `proguard-rules.pro`
- [ ] Klasa implementuje `HalPlugin` z wszystkimi metodami
- [ ] `pluginId` = `"{vendor}.{device}"`, `getCapabilities()` zwraca to samo
- [ ] `getDescriptor()` zawiera wszystkie metody i eventy
- [ ] `execute()` obsluguje wszystkie metody z descriptora + zwraca error dla nieznanych
- [ ] `setEventCallback()` zapisuje callback
- [ ] Konstruktor z opcjonalnym `Context`: `(appContext: Context? = null)`
- [ ] `settings.gradle.kts` -- `include()`
- [ ] `service/hal-service/build.gradle.kts` -- flavor + zaleznosc (jesli in-process)
- [ ] `HalService.kt` -- `tryRegisterPlugin()` (jesli in-process)

### Bundle APK:
- [ ] `build.gradle.kts` z `applicationId`, zaleznosci na `:service:hal-contract` + plugin-libs
- [ ] `AndroidManifest.xml` -- `<service>` per plugin: `exported=true`, intent-filter, meta-data
- [ ] Klasa serwisu uzywa `PluginServiceWrapper` i przekazuje `applicationContext` do konstruktora pluginu
- [ ] `settings.gradle.kts` -- `include()`

### Interfejs (definer):
- [ ] Definer w `plugins/generic/plugin-generic-lib`, `pluginId` = `interface.{device}`
- [ ] `capabilities` puste; `definesInterfaces` = `[InterfaceContract(...)]`
- [ ] Kontrakt: metody (kanoniczne nazwy), eventy, `features` (method-level vs param-level)
- [ ] `execute()` zwraca `unsupportedMethod` (nigdy nie routowane tutaj)
- [ ] `HalService.kt` -- `tryRegisterPlugin()` w sekcji interface definers
- [ ] Test rejestru (sekcja 7.1)

### Provider interfejsu (w vendor-pluginie):
- [ ] Deskryptor: `interfaces = [InterfaceBinding("{device}", priority, features)]`
- [ ] `execute()` routuje metody kontraktu (`{device}.*`) do wlasnych handlerow
- [ ] (Z eventami) emituje event interfejsu obok natywnego (`source` automatyczny)
- [ ] Testy napisane i przechodza

---

## 11. Kluczowe pliki

| Plik | Rola |
|---|---|
| `service/hal-contract/src/main/java/dev/duma/android/hal/contract/HalPlugin.kt` | Interfejs kontraktowy |
| `service/hal-contract/src/main/java/dev/duma/android/hal/contract/PluginContext.kt` | Kontekst inter-plugin |
| `service/hal-contract/src/main/java/dev/duma/android/hal/contract/PluginDescriptor.kt` | Deskryptor pluginu |
| `service/hal-contract/src/main/java/dev/duma/android/hal/contract/PluginServiceWrapper.kt` | Wrapper HalPlugin -> AIDL |
| `service/hal-service/src/main/java/.../service/HalService.kt` | Rejestracja pluginow |
| `service/hal-service/build.gradle.kts` | Flavory i zaleznosci |
| `settings.gradle.kts` | Moduly Gradle |
| `plugins/sunmi/plugin-sunmi-printer-lib/.../SunmiPrinterPlugin.kt` | Wzorzec vendor plugin |
| `plugins/sunmi/plugin-sunmi-scanner-lib/.../SunmiScannerPlugin.kt` | Wzorzec vendor plugin z eventami |
| `plugins/generic/plugin-generic-lib/.../PrinterInterface.kt` | Wzorzec definera interfejsu |
| `plugins/generic/plugin-generic-lib/.../ScannerInterface.kt` | Wzorzec definera interfejsu z eventem |
| `service/hal-contract/.../contract/InterfaceContract.kt` | Kontrakt + binding + feature |
| `plugins/sunmi/plugin-sunmi-bundle/.../SunmiPrinterService.kt` | Wzorzec serwisu bundle |
