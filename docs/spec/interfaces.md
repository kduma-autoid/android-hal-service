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

Provider identyfikowany jest wszędzie przez **`pluginId`** (sufiks `@provider` w nazwie metody,
`source` eventu, filtr subskrypcji) — bez osobnego aliasu.

## Rejestracja (brama)

Interfejs istnieje tylko wtedy, gdy jakiś plugin go **definiuje** (`definesInterfaces`). Bez definera
jego metody są niewołalne — `executeInterface(...)` zwraca `not_found`, nawet jeśli obecni są
providerzy. Definer może być zewnętrznym pluginem. Gdy definer się rozłącza, interfejs znika z
rejestru (metody znów niewołalne).

## Wybór providera per-wywołanie — `metoda@providerId`

Providera dla danego wywołania wskazuje sufiks w **nazwie metody**, tą samą składnią, którą filtruje
się eventy po źródle (`event@source`):
```
light.on@sunmi.tms.led   params: { "color": "green" }
```
Rdzeń odcina sufiks przed wyszukaniem deskryptora i routingiem, więc params pozostają wyłącznie
ładunkiem użytkownika — żaden klucz nie jest zarezerwowany. Pominięcie sufiksu → provider
**domyślny**. Wskazanie providera niedostępnego/wyłączonego/niepodpiętego → `unavailable`. Sufiks na
metodzie natywnej (spoza interfejsu) → `bad_request`, bo taka metoda ma dokładnie jednego
właściciela.

### Uprawnienia a sufiks

Wymagane uprawnienie bierze się z **deskryptora** (`MethodDescriptor.requiredPermission`) — z tego
samego pola, którym `system.describe` filtruje widoczność, więc katalog i egzekwowanie nie mogą się
rozjechać. Nie jest wyprowadzane z nazwy metody, a więc sufiks go nie dotyczy.

Bramki `super` i `experimental` przeciwnie — na poziomie metody używają **pełnej nazwy razem
z sufiksem**, dzięki czemu można je nadać dla konkretnego providera:

| uprawnienie | zakres |
|---|---|
| `super` / `experimental` | globalnie |
| `light.super` | wszystkie metody o uprawnieniu `light` |
| `light.on.super` | `light.on` na domyślnym providerze |
| `light.on@sunmi.statuslight.super` | `light.on` wyłącznie na FLEX-ie |

### Kolejność / domyślny provider

`getInterfaceProviders(interfaceId)` to jeden punkt sterowania — zwraca **dostępnych i włączonych**
providerów, posortowanych:

1. kolejność ustawiona przez użytkownika (jeśli jest),
2. `priority` malejąco,
3. external przed built-in,
4. `version` malejąco.

Pierwszy na liście = `isDefault` = provider dla wywołań bez sufiksu. Zmiana kolejności przez
użytkownika automatycznie zmienia default.

## Experimental i super w warstwie interfejsów

Znaczniki występują na trzech poziomach i **kontrakt jest ich właścicielem**:

- **Interfejs** — `InterfaceContract.experimental` oznacza cały interfejs; każda jego metoda i event
  wymaga dostępu experimental. Odpowiednik `PluginDescriptor.experimental` dla pluginu.
- **Metoda interfejsu** — `MethodDescriptor.superRequired` / `.experimental` jak dla metod natywnych.
- **Provider** — plugin implementujący może być eksperymentalny jako całość.

Dostęp experimental daje token (`experimental`, `<uprawnienie>.experimental`, `<metoda>.experimental`)
**albo** włączenie pluginu przez użytkownika w ustawieniach. Dla interfejsu kluczem w ustawieniach
jest plugin **definiujący**, nie provider.

### Implementacja nie nadpisuje bramek

Provider implementuje metody kontraktu we własnym `execute()`, ale nie redeklaruje ich deskryptorów,
a rdzeń rozwiązuje deskryptor metody interfejsowej **wyłącznie z kontraktu** (`getMethodDescriptor`
przegląda zarejestrowane interfejsy przed deskryptorami pluginów). Provider nie może więc ani
poluzować, ani zaostrzyć `superRequired`/`experimental` metody, którą udostępnia.

### Eksperymentalny provider jest wykluczony, a nie odrzucany

Provider, którego **plugin** jest eksperymentalny i nie został włączony przez użytkownika ani
dopuszczony tokenem, **nie należy do interfejsu** dla tego wywołującego:

- nie bierze udziału w wyborze domyślnego providera (nie zostanie cichym defaultem),
- wskazanie go sufiksem `@providerId` daje `unavailable` — tak samo jak wskazanie pluginu, który
  nigdy nie zadeklarował bindingu,
- nie pojawia się na liście `providers` w `system.describe`.

To różni się od bramki na metodzie: eksperymentalna **metoda** bez dostępu daje `forbidden`,
natomiast eksperymentalny **provider** bez dostępu po prostu nie istnieje w rozwiązywaniu. Dashboard
widzi go nadal (z odznaką), bo to jedyne miejsce, z którego można go włączyć.

### Buildy `stable`

`stripExperimental()` obejmuje też `definesInterfaces`: eksperymentalny kontrakt znika w całości,
a ze stabilnego wycinane są eksperymentalne metody i eventy. Bez tego build `stable` usuwałby
eksperymentalną metodę natywną, ale zostawiał eksperymentalną metodę interfejsu.

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
  "experimental": true,          // tylko gdy kontrakt jest eksperymentalny
  "experimentalActive": false,   // czy wywołujący faktycznie ma do niego dostęp
  "features": [{ "key": "timeout", "description": "…", "methods": [] }],
  "methods": [ /* MethodDescriptor — z superRequired / experimental gdy ustawione */ ],
  "events":  [ /* EventDescriptor  — z experimental gdy ustawione */ ],
  "providers": [
    { "pluginId": "sunmi.tms.led", "source": "built_in", "priority": 100,
      "isDefault": true, "enabled": true, "features": ["timeout"] }
  ]
}
```
API listuje tylko available (ale pokazuje **wyłączonych** z `enabled:false`, żeby dało się je
włączyć). Każdy wpis pluginu w `plugins` ma też `providesInterfaces` / `definesInterfaces` (id-ki do
cross-referencji).

Filtrowanie działa tak samo jak w sekcji `plugins`: metody `super` znikają bez `withSuper:true`,
a treść eksperymentalna bez dostępu (token lub ustawienie) albo bez `withExperimental:true`.
Eksperymentalny kontrakt bez dostępu nie pojawia się wcale. Eksperymentalny provider jest ukryty
przed wywołującym, który nie może go użyć — bo routing i tak by go pominął.

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
await client.execute('light.on@sunmi.statuslight', { color: 'green' },
  { onMeta: (m) => console.log(m.provider) });
// providerzy interfejsu:
const { interfaces } = await client.getDescribe();
// event tylko od jednego providera:
await client.on('demo.notice@demo.beta', (name, data, meta) => { /* meta.source */ });
```

Albo przez fasady `Sunmi<Interfejs>Client`, które same wykrywają providerów przez
`system.describe`, dokleją sufiks `@provider` dla nie-domyślnego backendu i wystawiają cechy jako
flagi/opcjonalne metody:

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
  `core/ServiceCommandHandler.kt` (sufiks `@provider`, `system.interface.*`, sekcja `interfaces`
  w describe),
  `config/InterfacePreferenceConfig.kt`.
- Definery/providerzy: `plugin-generic-lib` (`LightInterface`, `PrinterInterface`, `BarcodeScannerInterface`,
  `DemoInterface`, `DemoProviders`), `SunmiTmsLedPlugin`, `SunmiStatusLightPlugin`,
  `SunmiPrinterXPrinterPlugin` (interfejs `printer`), `SunmiInnerScannerPlugin`/
  `SunmiExternalScannerPlugin`/`SunmiCameraScannerPlugin` (interfejs `barcodeScanner`).
- Klient: `common` (`InterfaceDescriptor`, `PROVIDER_SELECTOR`/`methodForProvider`,
  `matchSubscription`), fasady `sunmi-light-facade` (`light`), `sunmi-printer-facade` (`printer`),
  `sunmi-barcode-scanner-facade` (`barcodeScanner`). Powierzchnię opisują same klasy fasad — nie ma
  osobnych typów `I<Interfejs>`.
