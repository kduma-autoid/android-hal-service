# Warstwa interfejsów

Interfejs to **jawnie zarejestrowany, autorytatywny kontrakt**, pod który wiele pluginów może się
podpiąć jako providerzy. Klient woła kanoniczne metody interfejsu (np. `light.on`), a rdzeń routuje
je do wskazanego albo domyślnego providera. Zastępuje to „generyczne" pluginy-owijki z zaszytą listą
backendów — nowy provider = deklaracja, zero edycji kodu interfejsu.

## Model (`service/hal-contract/`)

- **`InterfaceContract`** (własność *definera*, „oznaczenie że to interfejs"):
  `interfaceId`, `version`, `methods: List<MethodDescriptor>`, `events: List<EventDescriptor>`,
  `features: List<InterfaceFeature>`, `defaultProviderPolicy`.
- **`InterfaceFeature`**: `key`, `description`, `methods: List<String>` — metody bramkowane cechą
  (gdy pusta lista, cecha jest param-level, patrz niżej).
- **`InterfaceBinding`** (jawny opt-in *providera*): `interfaceId`, `priority`, `features: List<String>`
  — które opcjonalne cechy ten provider wspiera.
- **`PluginDescriptor`**: `definesInterfaces: List<InterfaceContract>` (interfejsy, które plugin
  **definiuje**) oraz `interfaces: List<InterfaceBinding>` (interfejsy, które plugin **udostępnia**).

Provider identyfikowany jest wszędzie przez **`pluginId`** (`__provider`, `source` eventu, filtr
subskrypcji) — bez osobnego aliasu.

## Rejestracja (brama)

Interfejs istnieje tylko wtedy, gdy jakiś plugin go **definiuje** (`definesInterfaces`). Bez definera
jego metody są niewołalne — `executeInterface(...)` zwraca `not_found`, nawet jeśli obecni są
providerzy. Definer może być zewnętrznym pluginem. Gdy definer się rozłącza, interfejs znika z
rejestru (metody znów niewołalne).

## Wybór providera per-wywołanie — `__provider`

Zarezerwowany parametr `__provider` w params wskazuje providera dla danego wywołania:
```json
{ "__provider": "sunmi.tms.led", "color": "green" }
```
Rdzeń wyłuskuje i **usuwa** `__provider` przed przekazaniem params do pluginu. Pominięcie → provider
**domyślny**. Wskazanie providera niedostępnego/wyłączonego/niepodpiętego → `unavailable`.

### Kolejność / domyślny provider

`getInterfaceProviders(interfaceId)` to jeden punkt sterowania — zwraca **dostępnych i włączonych**
providerów, posortowanych:

1. kolejność ustawiona przez użytkownika (jeśli jest),
2. `priority` malejąco,
3. external przed built-in,
4. `version` malejąco.

Pierwszy na liście = `isDefault` = provider dla wywołań bez `__provider`. Zmiana kolejności przez
użytkownika automatycznie zmienia default.

## Handler w nagłówku odpowiedzi

Odpowiedź na wywołanie interfejsu niesie `provider` — `pluginId` handlera, który je obsłużył
(rozwiązany default albo wskazany) — **w nagłówku, nie w ciele**:
- WS: pole `provider` w ramce `response` (obok `result`).
- HTTP: nagłówek odpowiedzi `X-Hal-Provider`.
- AIDL: `CommandResult.Success.provider` (Parcelable).
- Intent: extra `provider` w zwrotnym Intent (obok `result`).
- Klient TS: `execute(method, params, { onMeta })` → `onMeta({ provider })` — jednolicie dla WS i HTTP
  (samo `execute` dalej zwraca ciało).

Metody natywne/systemowe nie mają handlera interfejsu → `provider` jest wtedy pusty.

## Flagi cech (features)

Providerzy tego samego interfejsu różnią się opcjonalnymi funkcjami (np. `multiFlash`, `timeout`).
Każdy provider ogłasza swoje w `InterfaceBinding.features`; interfejs ogłasza wszystkie w
`InterfaceContract.features`. Są dwa rodzaje:

- **Method-level** (`InterfaceFeature.methods` niepuste, np. `multiFlash` → `light.multiFlash`):
  bramkują **całą metodę**. Egzekwuje rdzeń (`executeInterface` odrzuca metodę, jeśli provider nie
  ma cechy) **oraz** UI (metoda wyszarzona/oznaczona).
- **Param-level** (`InterfaceFeature.methods` puste, np. `timeout` = opcja `timeoutMs`): bramkują
  **parametr**, nie metodę. Rdzeń przekazuje params nieprzezroczyście, więc **nie** egzekwuje ich —
  robi to provider (ignoruje/odrzuca param) i UI/klient (ukrywa pole na podstawie `features`).

## Lista implementatorów

- **`getInterfaceProviders(id)`** — dostępni + włączeni, w efektywnej kolejności (API i UI online).
- **`getAllInterfaceImplementors(id)`** — pełna lista dla Dashboardu: available + unavailable
  (`available=false`) + unsupported (plugin ma binding, ale nie przeszedł `isSupported`), z flagami
  `available`/`supported`/`enabled`.

## `system.describe` — sekcja `interfaces`

Obok `plugins`, `describe` zwraca `interfaces: [InterfaceDescriptor]` (filtrowane uprawnieniami):
```jsonc
{
  "kind": "interface",
  "interfaceId": "light",
  "version": 1,
  "features": [{ "key": "timeout", "description": "…", "methods": [] }],
  "methods": [ /* MethodDescriptor */ ],
  "events":  [ /* EventDescriptor  */ ],
  "providers": [
    { "pluginId": "sunmi.tms.led", "source": "built_in", "priority": 100,
      "isDefault": true, "enabled": true, "features": ["timeout"] }
  ]
}
```
API listuje tylko available (ale pokazuje **wyłączonych** z `enabled:false`, żeby dało się je
włączyć). Każdy wpis pluginu w `plugins` ma też `providesInterfaces` / `definesInterfaces` (id-ki do
cross-referencji).

## Konfiguracja kolejności / enable (API)

Token-gated, jak `system.status`:

- `system.interface.setOrder { "interfaceId": "light", "order": ["sunmi.statuslight", "sunmi.tms.led"] }`
- `system.interface.setEnabled { "interfaceId": "light", "pluginId": "sunmi.tms.led", "enabled": false }`

Persystowane (`InterfacePreferenceConfig`, SharedPreferences). Zmiana emituje
`system.interfaces.changed`. Ta sama ścieżka steruje i API, i domyślnym providerem.

## Eventy interfejsu

Event interfejsu = zwykły event z `source` = `pluginId` providera-nadawcy (bez osobnej szyny).
Subskrypcja może filtrować po źródle: wpis `nazwa[@źródło]`, np. `demo.notice@demo.beta` albo
`scanner.*@sunmi.*` (obie połowy z wildcardami; brak `@` = dowolny nadawca). Szczegóły w
[`protocol-ws.md`](protocol-ws.md).

## Dynamic availability / hot-plug

Lista providerów i wybór domyślnego filtrują po dostępności (`available`). Provider, który zniknął
(`setPluginAvailability(false)` — np. odpięty FLEX albo brak sprzętowego LED), wypada z listy;
powiadomienie idzie istniejącym `system.plugins.changed`. Reużywa mechanizm hot-plug — bez nowej
szyny.

## Przykłady

- **`light`** (definer `LightInterface` w `plugin-generic-lib`): `light.on/off/flash/multiFlash`;
  providerzy `sunmi.tms.led` (CPad, `priority` wyższy, feature `timeout`) i `sunmi.statuslight`
  (FLEX, feature `multiFlash`). `multiFlash` woła się tylko na FLEX; `timeout` działa tylko na CPad.
- **`printer`** (definer `PrinterInterface`): `printer.printEscPos/printTspl/printZpl/printImage/cut`,
  każda metoda bramkowana cechą (`escpos`/`tspl`/`zpl`/`image`/`cut`). Provider `sunmi.printerx.printer`
  ogłasza `escpos, tspl, image, cut` (SDK nie ma ZPL — `printer.printZpl` → `unavailable`); `printer.cut`
  mapuje na sprzętowe `autoOut` (feed+cut). Zastępuje dawny `GenericPrinterPlugin`.
- **`barcodeScanner`** (definer `BarcodeScannerInterface`): `barcodeScanner.trigger/stop`, event `barcodeScanner.onScan
  {data, format, rawData?}`. Providerzy `sunmi.scanner.inner` (wbudowany, `priority` najwyższy, default),
  `sunmi.scanner.external` i `sunmi.scanner.camera` (dev-only). Każdy emituje `barcodeScanner.onScan` obok
  swojego natywnego eventu — `source` = `pluginId`, więc `barcodeScanner.onScan@sunmi.scanner.inner` filtruje
  konkretny skaner. Zastępuje dawny `GenericScannerPlugin`.
- **`demo`** (definer `DemoInterface`): bezsprzętowy interfejs testowy — providerzy `demo.alpha`
  (uppercase, default) i `demo.beta` (reverse), metody `demo.echo/ping/emit` i event `demo.notice`.

## Klient (TS)

Surowo, przez `client.execute` / `client.on`:

```ts
// domyślny provider:
await client.execute('light.on', { color: 'green' });
// konkretny provider + podgląd handlera:
await client.execute('light.on', { color: 'green', __provider: 'sunmi.statuslight' },
  { onMeta: (m) => console.log(m.provider) });
// providerzy interfejsu:
const { interfaces } = await client.getDescribe();
// event tylko od jednego providera:
await client.on('demo.notice@demo.beta', (name, data, meta) => { /* meta.source */ });
```

Albo przez typowane fasady (`I<Interfejs>` + `Sunmi<Interfejs>Client`), które same wykrywają
providerów przez `system.describe`, wstrzykują `__provider` dla nie-domyślnego backendu i wystawiają
cechy jako flagi/opcjonalne metody:

```ts
// printer — metody bramkowane cechą są obecne tylko gdy backend je wspiera:
const printer = await SunmiPrinterClient.create(client);   // domyślny provider `printer`
if (printer.printEscPos) await printer.printEscPos(escposBase64);
if (printer.cut) await printer.cut();
printer.printZpl;   // undefined na sunmi.printerx.printer (brak cechy `zpl`)

// scanner — onScan filtruje po źródle (tylko ten backend):
const scanner = await SunmiBarcodeScannerClient.create(client);   // domyślny provider `barcodeScanner`
const off = await barcodeScanner.onScan(({ data, format }) => console.log(data, format));
await barcodeScanner.trigger();

// lista backendów (do pickera) + pinowanie konkretnego:
const backends = await SunmiBarcodeScannerClient.listBackends(client);   // InterfaceProvider[]
const cam = await SunmiBarcodeScannerClient.forBackend(client, 'sunmi.scanner.camera');
```

Demo (`clients/ts/example`) ma widoki oparte o te fasady: **Printer** (karta per wspierany format —
nieobsługiwane są wypisane jako brakujące, bo interfejs po prostu nie ma tych metod) i **Scanner**
(trigger/stop + żywa lista skanów z widocznym filtrem `barcodeScanner.onScan@<backend>`); oba z pickerem
providera. Generyczny `InterfacesView` dalej pokazuje surowo każdy zarejestrowany interfejs.

## Kluczowe pliki

- Kontrakt: `InterfaceContract.kt`, `InterfaceBinding.kt`, `PluginDescriptor.kt`.
- Rdzeń: `plugin/PluginRegistry.kt` (rejestr, `executeInterface`, `getInterfaceProviders`,
  `getAllInterfaceImplementors`, `setInterfaceOrder`/`setInterfaceEnabled`),
  `core/ServiceCommandHandler.kt` (`__provider`, `system.interface.*`, sekcja `interfaces` w describe),
  `config/InterfacePreferenceConfig.kt`.
- Definery/providerzy: `plugin-generic-lib` (`LightInterface`, `PrinterInterface`, `BarcodeScannerInterface`,
  `DemoInterface`, `DemoProviders`), `SunmiTmsLedPlugin`, `SunmiStatusLightPlugin`,
  `SunmiPrinterXPrinterPlugin` (interfejs `printer`), `SunmiInnerScannerPlugin`/
  `SunmiExternalScannerPlugin`/`SunmiCameraScannerPlugin` (interfejs `barcodeScanner`).
- Klient: `common` (`InterfaceDescriptor`, `PROVIDER_PARAM_KEY`, `matchSubscription`, interfejsy
  `ILight`/`IPrinter`/`IBarcodeScanner`), fasady `sunmi-light-facade` (`ILight` na `light`),
  `sunmi-printer-facade` (`IPrinter` na `printer`), `sunmi-barcode-scanner-facade` (`IBarcodeScanner` na `barcodeScanner`).
