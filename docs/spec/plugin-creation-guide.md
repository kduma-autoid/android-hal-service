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

### Typ C: Generic abstraction plugin -- klasa w `plugin-generic-lib`
- Klasa dodawana do istniejacego modulu `plugin-generic-lib`
- Abstrakcja nad vendor-specific pluginami -- deleguje do dostepnego vendora
- WYMAGA `PluginContext` (musi byc in-process)
- Uzywa `ctx.hasCapability()`, `ctx.execute()`, `ctx.onEvent()`, `ctx.emitEvent()`
- Pakiet: `dev.duma.android.hal.plugins.generic`
- Przyklad: `GenericPrinterPlugin`, `GenericScannerPlugin`

**Wybierz ten typ gdy:** chcesz stworzyc zunifikowany interfejs dla danego typu urzadzenia, ktory automatycznie deleguje do dostepnego vendora.

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
| pluginId generic | `{device}` | `printer` |
| Capability | identyczny z pluginId | `sunmi.printer` / `printer` |
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

    override fun initialize(context: PluginContext) {
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
        versionCode = 1
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

## 5. Tworzenie Generic abstraction plugin (Typ C)

Generic plugin to klasa dodawana do istniejacego modulu `plugin-generic-lib`. NIE tworzy sie nowego modulu Gradle.

### 5.1 Wariant BEZ transformacji eventow

Plik: `plugins/generic/plugin-generic-lib/src/main/java/dev/duma/android/hal/plugins/generic/Generic{Device}Plugin.kt`

```kotlin
package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

class Generic{Device}Plugin : HalPlugin {

    override val pluginId = "{device}"
    override val version = 1

    private var ctx: PluginContext? = null

    companion object {
        // Kolejnosc = priorytet. Pierwszy dostepny vendor zostanie uzyty.
        private val VENDOR_{DEVICE_UPPER}S = listOf(
            "{vendor1}.{device}", "{vendor2}.{device}", "{vendor3}.{device}"
        )
    }

    override fun getCapabilities(): List<String> = listOf("{device}")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor("{device}.{operacja1}", "Opis", "{device}"),
            MethodDescriptor("{device}.{operacja2}", "Opis", "{device}")
        ),
        events = emptyList()
    )

    override fun initialize(context: PluginContext) {
        this.ctx = context
    }

    override suspend fun execute(method: String, params: String): String {
        val context = ctx ?: return """{"error":"not_initialized","message":"Plugin not initialized"}"""
        val operation = method.removePrefix("{device}.")
        return when (operation) {
            "{operacja1}", "{operacja2}" -> {
                val vendorMethod = findVendorMethod(context, operation)
                    ?: return """{"error":"no_{device}_backend","message":"No vendor {device} plugin available"}"""
                context.execute(vendorMethod, params)
            }
            else -> """{"error":"unsupported_method","method":"$method"}"""
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) { }

    private fun findVendorMethod(context: PluginContext, operation: String): String? {
        for (vendor in VENDOR_{DEVICE_UPPER}S) {
            if (context.hasCapability(vendor)) return "$vendor.$operation"
        }
        return null
    }
}
```

### 5.2 Wariant Z transformacja eventow

Roznice wzgledem 5.1:

W `companion object` dodaj prefiksy vendorow:
```kotlin
private val VENDOR_PREFIXES = listOf("{vendor1}", "{vendor2}", "{vendor3}")
```

W `getDescriptor()` dodaj eventy:
```kotlin
events = listOf(
    EventDescriptor("{device}.{event1}", "Opis (unified)", "{device}")
)
```

W `initialize()` zarejestruj listenery transformacji:
```kotlin
override fun initialize(context: PluginContext) {
    this.ctx = context
    for (vendor in VENDOR_PREFIXES) {
        context.onEvent("$vendor.{device}.*") { event, data ->
            // "{vendor}.{device}.{event}" -> "{device}.{event}"
            val unifiedEvent = event.replaceFirst("$vendor.", "")
            context.emitEvent(unifiedEvent, data)
        }
    }
}
```

W `setEventCallback`:
```kotlin
override fun setEventCallback(callback: HalPluginEventCallback?) {
    // Events are emitted via PluginContext, not direct callback
}
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

#### Generic: nic nie dodawaj (juz jest).
#### Bundle APK: nic nie dodawaj (osobna aplikacja).

### 6.3 `HalService.kt`

Plik: `service/hal-service/src/main/java/dev/duma/android/hal/service/service/HalService.kt`

#### Vendor-specific -- dodaj w sekcji krok 5 (vendor-specific plugins):
```kotlin
tryRegisterPlugin("dev.duma.android.hal.plugins.{vendor}.{device}.{Vendor}{Device}Plugin")
```

`tryRegisterPlugin()` automatycznie probuje najpierw konstruktor z `Context` (przekazuje `applicationContext`), a jesli taki nie istnieje -- uzywa konstruktora bezargumentowego. Dzieki temu pluginy z konstruktorem `(Context? = null)` otrzymaja `Context` automatycznie.

#### Generic -- dodaj w sekcji krok 7 (generic plugins):
```kotlin
tryRegisterPlugin("dev.duma.android.hal.plugins.generic.Generic{Device}Plugin")
```

#### Bundle APK: nic nie dodawaj -- discovery automatyczne przez `discoverExternal()`.

### 6.4 Aktualizacja istniejacych generic pluginow

Jesli dodajesz nowego vendora dla typu urzadzenia z istniejacym generic pluginem, dodaj vendora do listy:

```kotlin
// W GenericPrinterPlugin/GenericScannerPlugin/etc:
private val VENDOR_{DEVICE}S = listOf(
    "sunmi.{device}", "zebra.{device}", "chainway.{device}",
    "{nowyVendor}.{device}"  // DODAJ
)
// I jesli sa eventy:
private val VENDOR_PREFIXES = listOf(
    "sunmi", "zebra", "chainway",
    "{nowyVendor}"  // DODAJ
)
```

---

## 7. Testy

### 7.1 Test generic pluginu (WYMAGANY)

Plik: `plugins/generic/plugin-generic-lib/src/test/java/dev/duma/android/hal/plugins/generic/Generic{Device}PluginTest.kt`

```kotlin
package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.PluginContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class Generic{Device}PluginTest {
    private val mockContext = mockk<PluginContext>(relaxed = true)
    private val plugin = Generic{Device}Plugin().also { it.initialize(mockContext) }

    @Test
    fun `delegates to first available vendor`() = runTest {
        every { mockContext.hasCapability("{vendor1}.{device}") } returns true
        coEvery { mockContext.execute("{vendor1}.{device}.{operacja1}", any()) } returns """{"status":"ok"}"""
        val result = plugin.execute("{device}.{operacja1}", "{}")
        assertTrue(result.contains("status"))
        coVerify { mockContext.execute("{vendor1}.{device}.{operacja1}", any()) }
    }

    @Test
    fun `returns error when no vendor available`() = runTest {
        every { mockContext.hasCapability(any()) } returns false
        val result = plugin.execute("{device}.{operacja1}", "{}")
        assertTrue(result.contains("no_{device}_backend"))
    }

    @Test
    fun `tries vendors in priority order`() = runTest {
        every { mockContext.hasCapability("{vendor1}.{device}") } returns false
        every { mockContext.hasCapability("{vendor2}.{device}") } returns true
        coEvery { mockContext.execute("{vendor2}.{device}.{operacja1}", any()) } returns "{}"
        plugin.execute("{device}.{operacja1}", "{}")
        coVerify { mockContext.execute("{vendor2}.{device}.{operacja1}", any()) }
    }
}
```

Dla wariantu z eventami dodaj test transformacji:
```kotlin
@Test
fun `transforms vendor event to unified`() = runTest {
    val mockContext = mockk<PluginContext>(relaxed = true)
    val plugin = Generic{Device}Plugin()
    plugin.initialize(mockContext)

    val callbackSlot = slot<(String, String) -> Unit>()
    verify { mockContext.onEvent("{vendor1}.{device}.*", capture(callbackSlot)) }
    callbackSlot.captured("{vendor1}.{device}.{event1}", """{"data":"test"}""")
    verify { mockContext.emitEvent("{device}.{event1}", """{"data":"test"}""") }
}
```

Uruchomienie: `./gradlew :plugins:generic:plugin-generic-lib:test`

---

## 8. Pelna procedura -- kolejnosc krokow

### Scenariusz A: Nowy vendor-specific plugin (in-process)
1. Utworz modul `plugin-{vendor}-{device}-lib` (sekcja 3)
2. `settings.gradle.kts` -- dodaj `include()` (sekcja 6.1)
3. `service/hal-service/build.gradle.kts` -- flavor + zaleznosc (sekcja 6.2)
4. `HalService.kt` -- dodaj `tryRegisterPlugin()` w sekcji vendor (sekcja 6.3)
5. (Opcjonalnie) Aktualizuj generic plugin (sekcja 6.4)

### Scenariusz B: Nowy vendor-specific plugin (out-of-process / bundle)
1. Utworz modul `plugin-{vendor}-{device}-lib` (sekcja 3)
2. Utworz/zaktualizuj `plugin-{vendor}-bundle` (sekcja 4)
3. `settings.gradle.kts` -- dodaj oba moduly (sekcja 6.1)
4. NIE dodawaj do `service/hal-service/build.gradle.kts`
5. NIE dodawaj `tryRegisterPlugin()` -- discovery automatyczne
6. (Opcjonalnie) Aktualizuj generic plugin (sekcja 6.4)

### Scenariusz C: Nowy generic abstraction
1. Utworz klase w `plugins/generic/plugin-generic-lib` (sekcja 5)
2. `HalService.kt` -- dodaj `tryRegisterPlugin()` w sekcji generic (sekcja 6.3)
3. Napisz testy (sekcja 7)

### Scenariusz D: Kompletna para (vendor + generic)
1-5 z Scenariusza A, potem 1-3 z Scenariusza C

---

## 9. Wazne reguly

1. **Kolejnosc rejestracji:** vendor-specific PRZED generic PRZED initializeAll()
2. **Generic MUSZA byc in-process** -- wymagaja PluginContext
3. **Out-of-process NIE otrzymuja PluginContext**
4. **`execute()` musi byc thread-safe**
5. **Nazwy metod MUSZA odpowiadac descriptorowi**
6. **pluginId musi byc unikalny** w calym systemie
7. **Capabilities musza byc unikalne** -- ta sama capability nie moze byc w dwoch pluginach
8. **Konstruktor pluginu:** Vendor-specific pluginy MUSZA miec konstruktor z opcjonalnym `Context` parametrem: `(appContext: Context? = null)`. `tryRegisterPlugin()` uzywa refleksji -- probuje najpierw konstruktor `(Context)`, potem bezargumentowy. Bundle serwisy przekazuja `applicationContext` wprost. Generic pluginy moga miec bezargumentowy konstruktor (nie potrzebuja `Context` w konstruktorze -- dostaja go przez `PluginContext.applicationContext`)
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

### Generic plugin:
- [ ] Klasa w `plugins/generic/plugin-generic-lib`
- [ ] `pluginId` BEZ prefixu vendora
- [ ] Lista vendorow z priorytetem
- [ ] `initialize()` zapisuje PluginContext
- [ ] `execute()` sprawdza `ctx != null`
- [ ] `execute()` deleguje przez `findVendorMethod()`
- [ ] (Z eventami) `initialize()` rejestruje `onEvent()` listenery
- [ ] (Z eventami) transformacja `{vendor}.{device}.{event}` -> `{device}.{event}`
- [ ] `HalService.kt` -- `tryRegisterPlugin()` w sekcji generic
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
| `plugins/generic/plugin-generic-lib/.../GenericPrinterPlugin.kt` | Wzorzec generic plugin |
| `plugins/generic/plugin-generic-lib/.../GenericScannerPlugin.kt` | Wzorzec generic plugin z eventami |
| `plugins/sunmi/plugin-sunmi-bundle/.../SunmiPrinterService.kt` | Wzorzec serwisu bundle |
