# TODO

## Plugin Config Descriptor

Pluginy moga udostepniac konfiguracje bez importowania bibliotek UI. Plugin deklaruje schemat, HAL Service renderuje UI generycznie.

### Nowe typy w `hal-contract`:

```kotlin
data class ConfigDescriptor(
    val fields: List<ConfigField>
)

data class ConfigField(
    val key: String,
    val label: String,
    val description: String? = null,
    val type: ConfigFieldType, // BOOLEAN, STRING, INT, ENUM, TEXT
    val defaultValue: String? = null,
    val options: List<ConfigOption>? = null
)

data class ConfigOption(
    val value: String,
    val label: String
)

enum class ConfigFieldType { BOOLEAN, STRING, INT, ENUM, TEXT }
```

### Nowe metody w `HalPlugin`:

```kotlin
fun getConfigDescriptor(): ConfigDescriptor?   // null = brak konfiguracji
fun getConfig(): Map<String, String>            // aktualne wartosci
fun setConfig(key: String, value: String)       // zmiana wartosci
```

### Zmiany:

**hal-contract**:
- Dodac ConfigDescriptor, ConfigField, ConfigOption, ConfigFieldType
- Dodac metody do HalPlugin (z domyslnymi implementacjami: null, emptyMap, no-op)
- Rozszerzyc IHardwarePlugin.aidl o getConfigDescriptorJson(), getConfigJson(), setConfig()
- Zaktualizowac AidlPluginAdapter i PluginServiceWrapper

**hal-service UI**:
- PluginDetailActivity: sekcja "Configuration" renderowana z ConfigDescriptor
  - BOOLEAN → Switch
  - STRING/TEXT → EditText
  - INT → EditText (inputType=number)
  - ENUM → Spinner/RadioGroup
- Zapis przez plugin.setConfig(key, value)

**Persystencja**: plugin sam zarzadza storage (SharedPreferences via PluginContext.applicationContext). HAL Service tylko renderuje UI i wywoluje get/set.

**API**: opcjonalnie system methods `system.plugin.config.get` / `system.plugin.config.set` do zdalnej konfiguracji.

---

## AIDL stabilizacja: generyczny `call()` zamiast typed methods

Obecne `IHardwarePlugin.aidl` ma konkretne metody (`execute`, `getDescriptorJson`, `getCapabilities` itd.). Kazda zmiana AIDL (np. dodanie `getConfigDescriptorJson()`) lamie kompatybilnosc ze starymi external pluginami (bundle APK).

**Rozwiazanie:** Zredukowac AIDL do minimalnego stabilnego interfejsu:

```aidl
interface IHardwarePlugin {
    String getPluginId();
    int getVersion();
    boolean isSupported();
    String call(String method, String jsonParams);
    void registerEventCallback(IPluginEventCallback callback);
    void unregisterEventCallback(IPluginEventCallback callback);
}
```

Cala reszta przechodzi przez `call()` jako konwencje:

| call method | zastepuje | params | zwraca |
|-------------|-----------|--------|--------|
| `__describe` | getDescriptorJson() | `{}` | PluginDescriptor JSON |
| `__capabilities` | getCapabilities() | `{}` | `["cap1","cap2"]` |
| `__config.descriptor` | getConfigDescriptorJson() | `{}` | ConfigDescriptor JSON |
| `__config.get` | getConfig() | `{}` | `{"key":"value"}` |
| `__config.set` | setConfig() | `{"key":"k","value":"v"}` | `{"status":"ok"}` |
| `<method>` | execute() | params | wynik |

### Zmiany:

**IHardwarePlugin.aidl** (`hal-contract`):
- Usunac wszystkie metody oprocz `getPluginId()`, `getVersion()`, `isSupported()`, `call()`, `register/unregisterEventCallback()`

**AidlPluginAdapter** (`hal-contract`):
- `getDescriptor()` → `call("__describe", "{}")` → parse JSON
- `getCapabilities()` → `call("__capabilities", "{}")` → parse JSON
- `execute(method, params)` → `call(method, params)`
- Nowe features (config etc.) → kolejne `call("__config.*", ...)` bez zmian AIDL

**PluginServiceWrapper** (`hal-contract`):
- `call()` dispatchuje po method name: `__describe`, `__capabilities`, `__config.*` → wewnetrzne metody HalPlugin
- Domyslne → `plugin.execute(method, params)`

**Kompatybilnosc**: AIDL sie nigdy wiecej nie zmienia. Nowe features to nowe konwencje na `call()`. Stary plugin nie obslugujacy `__config.descriptor` zwroci blad — AidlPluginAdapter traktuje to jako brak configu (graceful fallback).

---

## hal-activity-proxy — generyczny mechanizm Activity dla pluginow

Niektore SDK (np. PrinterX `startSettings(Activity, SettingItem)`) wymagaja Activity context. Pluginy zyja w Service — nie maja Activity.

**Cel:** Reusable modul `hal-activity-proxy` z `ActivityProxyManager` + transparentna `ProxyActivity`. Dowolny plugin moze zadac startowania Activity z `FLAG_ACTIVITY_NEW_TASK`.

**Uwaga:** Android 10+ ogranicza startowanie Activity z tla. Dziala gdy wywolanie pochodzi z interakcji uzytkownika. Fallback: zwroc Intent jako JSON, klient z Activity sam go uruchomi.

---

## Routing: method-level dispatch

Obecny routing jest capability-based — `capabilityToPlugin` mapuje capability (np. `sunmi.printer`) na plugin. Nie pozwala na rozdzielenie metod w ramach jednej capability miedzy rozne pluginy (np. `sunmi.printer.print` w pluginie A, `sunmi.printer.feed` w pluginie B).

**Rozwiazanie:** Dodac `methodToPlugin: ConcurrentHashMap<String, HalPlugin>` indeksowany po nazwach metod z `getDescriptor().methods`. W `findForMethod()` najpierw exact match po metodzie, potem fallback na capability prefix.

Zmiany w `PluginRegistry`:
- Dodac pole `methodToPlugin`
- W `tryRegister()` po rejestracji pluginu indeksowac jego metody
- W `onServiceDisconnected()` czyscic metody odlaczonego pluginu
- W `findForMethod()` sprawdzac `methodToPlugin[method]` przed capability prefix matching
- W `disconnectAll()` czyscic `methodToPlugin`
