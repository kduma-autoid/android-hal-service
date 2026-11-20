# Pluginy — implementacja

Urządzenia emulujące klawiaturę (skanery, czytniki kart) zgłaszają się do wspólnego filtra klawiatury HAL:
[`keyboard-filter.md`](keyboard-filter.md).

## plugin-sunmi-printer-lib: SunmiPrinterPlugin

Stub — drukarka Sunmi.

```kotlin
pluginId = "sunmi.printer"
version = 1
capabilities = ["sunmi.printer"]
```

**execute():**
| Metoda | Odpowiedź |
|--------|-----------|
| sunmi.printer.print | `{"jobId":"job_{timestamp}","status":"queued"}` |
| sunmi.printer.status | `{"status":"idle","paperLevel":"ok"}` |
| inne | `{"error":"unsupported_method","method":"..."}` |

**getDescriptor():**
```kotlin
PluginDescriptor(
    pluginId = "sunmi.printer", version = 1,
    capabilities = listOf("sunmi.printer"),
    methods = listOf(
        MethodDescriptor("sunmi.printer.print", "Print receipt using Sunmi printer", "sunmi.printer"),
        MethodDescriptor("sunmi.printer.status", "Get Sunmi printer status", "sunmi.printer")
    ),
    events = emptyList()
)
```

Nie wymaga PluginContext. initialize(ctx) — zapisuje ale nie używa.

## plugin-sunmi-scanner-lib: SunmiScannerPlugin

Stub — skaner Sunmi.

```kotlin
pluginId = "sunmi.scanner"
version = 1
capabilities = ["sunmi.scanner"]
```

**execute():**
| Metoda | Odpowiedź |
|--------|-----------|
| sunmi.scanner.trigger | `{"status":"scanning"}` |
| sunmi.scanner.stop | `{"status":"idle"}` |
| inne | `{"error":"unsupported_method","method":"..."}` |

**getDescriptor():**
```kotlin
PluginDescriptor(
    pluginId = "sunmi.scanner", version = 1,
    capabilities = listOf("sunmi.scanner"),
    methods = listOf(
        MethodDescriptor("sunmi.scanner.trigger", "Trigger barcode scan", "sunmi.scanner"),
        MethodDescriptor("sunmi.scanner.stop", "Stop scanning", "sunmi.scanner")
    ),
    events = listOf(
        EventDescriptor("sunmi.scanner.barcode", "Barcode scanned by Sunmi scanner", "sunmi.scanner")
    )
)
```

Nie wymaga PluginContext.

## plugin-sunmi-bundle: standalone APK

Bundluje oba plugin libs. Osobny Service per plugin.

```kotlin
class SunmiPrinterService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiPrinterPlugin(this))
}

class SunmiScannerService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiScannerPlugin(this))
}
```

AndroidManifest: dwa Service z intent-filter `dev.duma.android.hal.HARDWARE_PLUGIN`
i meta-data plugin.id / plugin.label.

## plugin-generic-lib: PrinterInterface (definer interfejsu `printer`)

Abstrakcja drukarki jako **interfejs**, nie owijka. `PrinterInterface` (pluginId `interface.printer`)
tylko *rejestruje* kontrakt `printer` — nie ma sprzętu ani capabilities. Providerzy podpinają się
jawnie przez `InterfaceBinding("printer", …)` i sami routują metody kontraktu. Zastępuje dawny
`GenericPrinterPlugin` (owijkę z zaszytą listą vendorów). Szczegóły warstwy: [`interfaces.md`](interfaces.md).

```kotlin
pluginId = "interface.printer"      // definer
version = 1
capabilities = []                    // definer nie udostępnia sprzętu
definesInterfaces = [InterfaceContract("printer", …)]
```

**Metody kontraktu** (każda bramkowana cechą — provider musi ogłosić cechę w `binding.features`):

| Metoda | Cecha | Params |
|--------|-------|--------|
| printer.printEscPos | `escpos` | `{"data": base64}` |
| printer.printTspl | `tspl` | `{"data": base64}` |
| printer.printZpl | `zpl` | `{"data": base64}` |
| printer.printImage | `image` | `{"bitmap": base64, "style"?: {…}}` |
| printer.cut | `cut` | `{}` |

**Provider:** `sunmi.printerx.printer` (`SunmiPrinterXPrinterPlugin`, oba buildy sunmi) —
`InterfaceBinding("printer", priority = 100, features = ["escpos","tspl","image","cut"])`. Nie ma `zpl`
(SDK go nie wspiera), więc `printer.printZpl` → `unavailable`. `printer.cut` mapuje na sprzętowe
`autoOut` (feed+cut). Flavor `generic` nie ma providera → `printer.*` = `unavailable`.

## plugin-generic-lib: BarcodeScannerInterface (definer interfejsu `barcodeScanner`)

Abstrakcja skanera jako **interfejs**. `BarcodeScannerInterface` (pluginId `interface.barcodeScanner`) rejestruje
kontrakt `barcodeScanner`; providerzy podpinają się przez `InterfaceBinding("barcodeScanner", …)`, obsługują
`barcodeScanner.trigger/stop` i emitują znormalizowany event `barcodeScanner.onScan` **obok** swojego natywnego
eventu. Zastępuje dawny `GenericScannerPlugin` (transformację eventów z zaszytej listy vendorów).

```kotlin
pluginId = "interface.barcodeScanner"      // definer
version = 1
capabilities = []
definesInterfaces = [InterfaceContract("barcodeScanner", …)]
```

**Metody:** `barcodeScanner.trigger {}` → `{"status":"scanning"}`, `barcodeScanner.stop {}`.
**Event:** `barcodeScanner.onScan {data, format, rawData?}` — `source` = `pluginId` skanera-nadawcy, więc
subskrypcja `barcodeScanner.onScan@sunmi.scanner.inner` filtruje konkretny skaner.

**Providerzy** (bez features):

| plugin | priority | build |
|--------|----------|-------|
| [`sunmi.scanner.ss2222`](sunmi-ss2222.md) (`SunmiSs2222ScannerPlugin`) | 110 (gdy USB jest gotowe) | sunmi stable + development, Sunmi bundle, AAR |
| `sunmi.scanner.inner` (`SunmiInnerScannerPlugin`) | 100 | sunmi stable + development |
| `sunmi.scanner.external` (`SunmiExternalScannerPlugin`) | 50 | sunmi development (experimental) |
| `sunmi.scanner.camera` (`SunmiCameraScannerPlugin`) | 40 | sunmi development (experimental) |

Flavor `generic` nie ma providera → `scanner.*` = `unavailable`.

## plugin-generic-lib: MagneticCardReaderInterface (definer interfejsu `magneticCardReader`)

Abstrakcja czytnika kart magnetycznych. `MagneticCardReaderInterface` (pluginId `interface.magneticCardReader`)
rejestruje kontrakt `magneticCardReader`. Pasują pod niego dwa rodzaje providerów: **pasywne** czytniki,
które tylko raportują przeciągnięcia kart (event), i **aktywne** czytniki/nagrywarki, którym można kazać czekać
na kartę i ją zapisać.

Dane ścieżek są zawsze **bez sentineli** (`%`/`;` … `?`). Ścieżka to string, `""` gdy jest pusta, albo `null`
przy błędzie odczytu.

| Metoda | Cecha | Params | Wynik |
|--------|-------|--------|-------|
| magneticCardReader.getInfo | — | `{}` | `{name, connected}` |
| magneticCardReader.read | `read` | `{timeoutMs?}` | `{track1, track2, track3}` |
| magneticCardReader.cancel | `read` | `{}` | `{cancelled}` |
| magneticCardReader.write | `write` | `{track1?, track2?, track3?, timeoutMs?}` | `{status}` |

**Event:** `magneticCardReader.onSwipe {track1, track2, track3}` — `source` = `pluginId` czytnika.

**Providerzy:**

| plugin | priority | features | build |
|--------|----------|----------|-------|
| `msr605x` (`Msr605xPlugin`) | 100 (default) | `read`, `write` | wbudowany w flavor `sunmi`; inne buildy — osobne APK `plugin-msr605x-bundle` |
| `sunmi.card` (`SunmiCardPlugin`) | 50 | — (pasywny) | sunmi development (experimental) |

`magneticCardReader.read` bez sufiksu trafia do providera z cechą `read` nawet gdy defaultem jest pasywny
czytnik; `magneticCardReader.read@sunmi.card` → `unavailable`.

## plugin-generic-lib: ScaleInterface (definer interfejsu `scale`)

Abstrakcja wagi. `ScaleInterface` (pluginId `interface.scale`) rejestruje kontrakt `scale` (bez cech).

| Metoda | Params | Wynik |
|--------|--------|-------|
| scale.getInfo | `{}` | `{name, connected, reading \| null}` |
| scale.getWeight | `{}` | odczyt; przed pierwszym odczytem → `unavailable` |

**Odczyt:** `{grams, value, unit, status, stable}`:

| pole | znaczenie |
|------|-----------|
| `grams` | waga w gramach niezależnie od jednostki ustawionej na wadze (dla oz: `oz × 28.3495`, do 0.1 g), ujemna poniżej zera — główne pole dla klientów |
| `value` + `unit` (`"g"`/`"oz"`) | waga dokładnie tak, jak pokazuje wyświetlacz wagi |
| `status` | surowy stan zgłaszany przez wagę: `zero`, `inMotion`, `stable`, `underZero`, `overWeight`, `fault`, `needCalibration`, `needZeroing`, `unknown` — tylko informacyjnie |
| `stable` | liczone przez providera: waga ustabilizowana i bez statusu błędu — tego używają klienci |

**Eventy:** `scale.onWeight` (odczyt) i `scale.onStatus {status, previous}` — `source` = `pluginId` wagi.
`scale.onStatus` może zgłosić też `offline` (provider stracił odczyty wagi; do ich powrotu `getWeight` →
`unavailable`).

**Providerzy:** `dymo.m10` (`DymoM10Plugin`, priority 100, default) — wbudowany w flavor `sunmi`; inne buildy —
osobne APK `plugin-dymo-bundle`.

## plugin-dymo-m10-lib: DymoM10Plugin

Waga pocztowa **Dymo M10** (USB HID, VID `0x0922`, PID `0x8003`), czytana bezpośrednio przez Android USB host
API. Port z kduma-OSS/CS-usb-scale-counter (`ScaleReport.cs`, `AndroidScale.cs`). Waga tylko nadaje — nie
przyjmuje komend (brak tary sprzętowej; tara/liczenie sztuk są po stronie klienta, np. w demo).

**Dystrybucja:** jak MSR605X — wbudowany w hal-service tylko we flavorze `sunmi`; pozostałe buildy — osobne APK
**`plugin-dymo-bundle`** (`dev.duma.android.hal.plugins.dymo`, Service `DymoService`, `plugin.id=dymo.m10`),
które nadpisuje wersję wbudowaną.

```kotlin
pluginId = "dymo.m10"
capabilities = ["dymo.m10"]
interfaces = [InterfaceBinding("scale", priority = 100)]
```

**Protokół:** 6-bajtowe raporty wejściowe z interrupt IN (`claimInterface(force = true)`):
`[0]` report id (`3` = dane, inne ignorowane), `[1]` status, `[2]` jednostka (`2` = g, `11` = oz),
`[3]` wykładnik (signed), `[4..5]` waga LE → `waga = raw · 10^wykładnik`.

| bajt statusu | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | inny |
|---|---|---|---|---|---|---|---|---|---|
| `status` | `fault` | `zero` | `inMotion` | `stable` | `underZero` | `overWeight` | `needCalibration` | `needZeroing` | `unknown` |

**Stabilność liczona w pluginie** (`ScaleState`): M10 zgłasza `stable` prawie cały czas (komentarze w
`MainViewModel.cs`/`Form1.cs` referencji), więc — jak referencja — plugin ma **timer 500 ms**: każda zmiana
wagi o ≥ 1 g (względem ostatnio wyemitowanego odczytu) go restartuje, a po 500 ms bez zmiany odczyt dostaje
`stable = true`. Statusy błędu (`fault`, `overWeight`, `needCalibration`, `needZeroing`, `unknown`) trzymają
`stable = false`; wyjście z błędu restartuje timer. `inMotion` nie blokuje stabilizacji.

**Eventy wagi** (`dymo.m10.weight` + `scale.onWeight`, zawsze pełny odczyt): zmiana o ≥ 1 g (`stable=false`),
ustabilizowanie (`stable=true`, raz na stabilizację), zmiana statusu wagi lub jednostki.
`dymo.m10.status` + `scale.onStatus {status, previous}` przy zmianie statusu (także `offline` i powrót z niego).
Do tego `dymo.m10.attached` /
`dymo.m10.detached`.

**USB i dostępność:** plugin jest dostępny tylko gdy waga jest podłączona **i** aplikacja ma uprawnienie USB
(prosi sam przy wykryciu). Błąd stanu wagi (np. `overWeight`) nie zmienia dostępności. Odłączenie →
`detached`, `available = false`, ostatni odczyt jest czyszczony. Pętla odczytu (`UsbScaleTransport`, timeout
250 ms) po błędzie otwiera urządzenie ponownie co 1 s. Raporty: `adb shell setprop log.tag.DymoM10 DEBUG`.

**Metody** (uprawnienie `dymo.m10`):

| Metoda | Params | Wynik |
|--------|--------|-------|
| dymo.m10.getWeight (= scale.getWeight) | `{}` | odczyt; `unavailable` bez wagi/uprawnienia/odczytu |
| dymo.m10.getInfo (= scale.getInfo) | `{}` | `{name, connected, permission, reading \| null}` |

**Zachowanie sprzętu (sprawdzone na M10 + OrderPAD_3):**
- waga nadaje raport co ~130–260 ms także bez zmian;
- surowy status to `stable` (4) również w trakcie zmian wagi (np. 6 → 92 → 124 → 88 g), a `inMotion`
  nie pojawia się wcale — stąd stabilność liczona w pluginie;
- poniżej zera waga podaje wartość bez znaku ze statusem `underZero` (tara, zdjęcie ładunku → `05 02 00 5a00`
  = 90 g) — plugin ją neguje (`-90`);
- przy zerze waga zawsze podaje uncje (`03 02 0b ff 0000`), niezależnie od ustawienia — `ScaleState` zostawia
  jednostkę ostatniego niezerowego odczytu (przed pierwszym takim odczytem zero jest w `oz`);
- aplikacja z domyślnym skojarzeniem USB dla wagi (np. referencyjna `sh.duma.scalecounter`) uruchamia się przy
  podłączeniu i przejmuje interfejs (`forceClaim`) — raporty do pluginu ustają. Po **3 s bez raportów** plugin
  zgłasza status `offline` (`scale.onStatus {status: "offline"}`), czyści odczyt (`getWeight` → `unavailable`),
  a gdy raporty wrócą (inna aplikacja zwolni interfejs), emituje `{status, previous: "offline"}` i nowy odczyt —
  bez ponownego otwierania urządzenia.

Emitowane eventy widać w logu: `adb shell setprop log.tag.DymoM10 DEBUG` (`emit …`, `rx …`).

## plugin-msr605x-lib: Msr605xPlugin

Czytnik/nagrywarka kart magnetycznych **MSR605X** (USB HID, VID `0x0801`, PID `0x0003`), sterowany bezpośrednio
przez Android USB host API — bez SDK producenta. Port sterownika `msr605x.py` (Magnetic-Fox, 2026). Działa na
każdym urządzeniu z USB-OTG. Plugin **nie jest experimental** (zweryfikowany na sprzęcie, łącznie z
zapisem i kasowaniem).

**Dystrybucja:**
- wbudowany w hal-service tylko we flavorze `sunmi` (stable i development);
- osobne APK **`plugin-msr605x-bundle`** (`dev.duma.android.hal.plugins.msr605x`, flavory stable/development)
  dla pozostałych buildów — Service `Msr605xService` z `PluginServiceWrapper`, wykrywany przez
  `dev.duma.android.hal.HARDWARE_PLUGIN`. Zainstalowany nadpisuje wersję wbudowaną (zewnętrzny plugin wypiera
  built-in). Plugin działa wtedy w procesie bundle'a: tam trzyma połączenie USB i nasłuchiwanie, a uprawnienie
  USB należy do aplikacji bundle'a. Dostępność (`setPluginAvailable`) i ustawienia przechodzą przez AIDL
  (patrz [`plugin-contract.md`](plugin-contract.md)), więc działa tak samo jak wbudowany.

```kotlin
pluginId = "msr605x"
capabilities = ["msr605x"]
interfaces = [InterfaceBinding("magneticCardReader", priority = 100, features = ["read", "write"])]
```

**Struktura:** `Msr605xProtocol` (czysty Kotlin: ramkowanie 64-bajtowych raportów HID — nagłówek
`0x80` first | `0x40` last | długość — budowanie komend i parsowanie odpowiedzi), `Msr605xDevice` (komendy
nad transportem; czekanie na kartę z timeoutem i anulowaniem, po timeoucie/anulowaniu hard reset przez
feature report), `UsbHidTransport` (odpowiedzi z interrupt IN; komendy zawsze jako `SET_REPORT(Feature)` — czytnik nie ma
endpointu OUT i ignoruje `SET_REPORT(Output)` z report id 0, tak jak sterownik referencyjny na macOS), `Msr605xPlugin`.

**USB i uprawnienia:** plugin jest dostępny (`setPluginAvailable`) tylko gdy MSR605X jest podłączony **i**
aplikacja ma uprawnienie USB. O uprawnienie prosi sam, gdy wykryje czytnik (przy starcie albo
`ACTION_USB_DEVICE_ATTACHED`) — nie ma do tego metody API. Komenda bez uprawnienia ponawia prośbę i zwraca
`unavailable`.

**Nasłuchiwanie w tle (domyślnie włączone):** MSR605X czyta kartę tylko po uzbrojeniu komendą odczytu
(jedna komenda = jedno przeciągnięcie) i dopóki czeka, nie odpowiada na nic poza hard resetem. Plugin
trzyma go więc uzbrojonego w tle: każde przeciągnięcie emituje `msr605x.swipe` i
`magneticCardReader.onSwipe`, po czym czytnik jest uzbrajany ponownie — zachowuje się jak czytnik
pasywny (`sunmi.card`). Każda inna komenda (LED, ustawienia, `read`/`write`/`erase`) najpierw przerywa
nasłuchiwanie (hard reset, ~100–350 ms), wykonuje się, a potem nasłuchiwanie wraca. W trybie odczytu
czytnik sam steruje diodami, więc stan ustawiony `setLed` nie utrzymuje się podczas nasłuchiwania.
`getInfo` odpowiada z cache (model/firmware) i nie przerywa nasłuchiwania.

Automatyczny start steruje ustawienie pluginu **„Listen for card swipes automatically”** (hal-service →
szczegóły pluginu MSR605X → Settings, domyślnie włączone); `msr605x.setListening` przełącza je doraźnie
do restartu usługi.

**Metody** (uprawnienie `msr605x`):

| Metoda | Params | Opis |
|--------|--------|------|
| msr605x.read | `{timeoutMs?}` (domyślnie 15000, max 120000) | czeka na kartę → `{status, track1, track2, track3}` |
| msr605x.readRaw | `{timeoutMs?}` | surowe ścieżki (hex) |
| msr605x.write | `{track1?, track2?, track3?, timeoutMs?}` | zapis ISO 7811 (walidacja alfabetu i długości) |
| msr605x.writeRaw | `{track1?, track2?, track3? (hex), timeoutMs?}` | zapis surowy (najpierw BPC 8,8,8) |
| msr605x.erase | `{tracks: [1,2,3], timeoutMs?}` | kasowanie ścieżek |
| msr605x.cancel | `{}` | przerywa oczekiwanie na kartę (omija mutex) |
| msr605x.setCoercivity / getCoercivity | `{mode: "hi"\|"lo"}` | Hi-Co / Lo-Co |
| msr605x.setBpi | `{track1?, track2?, track3?: 210\|75}` | bity na cal |
| msr605x.setBpc | `{track1, track2, track3: 5..8}` | bity na znak |
| msr605x.setLeadingZeros / getLeadingZeros | `{track13, track2: 0..255}` | zera wiodące |
| msr605x.setLed | `{led: off\|green\|yellow\|red\|all}` | diody |
| msr605x.getInfo | `{}` | `{connected, permission, model, firmware, listening}` |
| msr605x.setListening | `{enabled}` | nasłuchiwanie w tle wł./wył. do restartu → `{listening}` |
| msr605x.commTest | `{}` | `{ok}` |
| msr605x.reset | `{hard?}` | reset (hard przerywa trwającą operację) |

Błędy: `timeout` (brak karty w czasie), `cancelled`, `unavailable` (brak urządzenia/uprawnienia),
`bad_request` (zła walidacja), `write_failed`/`erase_failed` (status urządzenia ≠ `0`).

Status po odczycie/zapisie/kasowaniu to `'0'` + maska bitowa ścieżek, które nie przeszły weryfikacji
(bit 0 = ścieżka 1, bit 1 = ścieżka 2, bit 2 = ścieżka 3), np. `'5'` → `"failed on track 1+3"`.
Sterownik referencyjny traktuje `'1'`/`'2'`/`'4'` jako „R/W error” / „command format error” /
„invalid command” — na sprzęcie to awarie ścieżek 1/2/3. Zapis Lo-Co na karcie Hi-Co daje `'7'`.

**Eventy:** `msr605x.swipe` (każde przeciągnięcie — z nasłuchiwania albo `read`; obok unified
`magneticCardReader.onSwipe`), `msr605x.attached`,
`msr605x.detached`.
