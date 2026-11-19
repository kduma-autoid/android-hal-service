# Pluginy — implementacja

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
| `sunmi.scanner.inner` (`SunmiInnerScannerPlugin`) | 100 (default) | sunmi stable + development |
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
| `msr605x` (`Msr605xPlugin`) | 100 (default) | `read`, `write` | wszystkie flavory, development (experimental) |
| `sunmi.card` (`SunmiCardPlugin`) | 50 | — (pasywny) | sunmi development (experimental) |

`magneticCardReader.read` bez sufiksu trafia do providera z cechą `read` nawet gdy defaultem jest pasywny
czytnik; `magneticCardReader.read@sunmi.card` → `unavailable`.

## plugin-msr605x-lib: Msr605xPlugin

Czytnik/nagrywarka kart magnetycznych **MSR605X** (USB HID, VID `0x0801`, PID `0x0003`), sterowany bezpośrednio
przez Android USB host API — bez SDK producenta. Port sterownika `msr605x.py` (Magnetic-Fox, 2026). Działa na
każdym urządzeniu z USB-OTG, więc moduł jest podpięty pod wszystkie flavory; jako całość jest `experimental`
(niezweryfikowany na sprzęcie), więc w buildach `stable` nie wystawia niczego.

```kotlin
pluginId = "msr605x"
capabilities = ["msr605x"]
interfaces = [InterfaceBinding("magneticCardReader", priority = 100, features = ["read", "write"])]
```

**Struktura:** `Msr605xProtocol` (czysty Kotlin: ramkowanie 64-bajtowych raportów HID — nagłówek
`0x80` first | `0x40` last | długość — budowanie komend i parsowanie odpowiedzi), `Msr605xDevice` (komendy
nad transportem; czekanie na kartę z timeoutem i anulowaniem, po timeoucie/anulowaniu hard reset przez
feature report), `UsbHidTransport` (interrupt IN; OUT przez interrupt OUT albo `SET_REPORT`), `Msr605xPlugin`.

**USB i uprawnienia:** plugin jest dostępny (`setPluginAvailable`) tylko gdy MSR605X jest podłączony **i**
aplikacja ma uprawnienie USB. O uprawnienie prosi sam, gdy wykryje czytnik (przy starcie albo
`ACTION_USB_DEVICE_ATTACHED`) — nie ma do tego metody API. Komenda bez uprawnienia ponawia prośbę i zwraca
`unavailable`.

**Metody** (uprawnienie `msr605x`):

| Metoda | Params | Opis |
|--------|--------|------|
| msr605x.read | `{timeoutMs?}` (domyślnie 15000, max 120000) | czeka na kartę → `{status, track1, track2, track3}` |
| msr605x.readRaw | `{timeoutMs?}` | surowe ścieżki (hex) |
| msr605x.write *(exp.)* | `{track1?, track2?, track3?, timeoutMs?}` | zapis ISO 7811 (walidacja alfabetu i długości) |
| msr605x.writeRaw *(exp.)* | `{track1?, track2?, track3? (hex), timeoutMs?}` | zapis surowy (najpierw BPC 8,8,8) |
| msr605x.erase *(exp.)* | `{tracks: [1,2,3], timeoutMs?}` | kasowanie ścieżek |
| msr605x.cancel | `{}` | przerywa oczekiwanie na kartę (omija mutex) |
| msr605x.setCoercivity / getCoercivity | `{mode: "hi"\|"lo"}` | Hi-Co / Lo-Co |
| msr605x.setBpi | `{track1?, track2?, track3?: 210\|75}` | bity na cal |
| msr605x.setBpc | `{track1, track2, track3: 5..8}` | bity na znak |
| msr605x.setLeadingZeros / getLeadingZeros | `{track13, track2: 0..255}` | zera wiodące |
| msr605x.setLed | `{led: off\|green\|yellow\|red\|all}` | diody |
| msr605x.getInfo | `{}` | `{connected, permission, model, firmware}` |
| msr605x.commTest | `{}` | `{ok}` |
| msr605x.reset | `{hard?}` | reset (hard przerywa trwającą operację) |

Błędy: `timeout` (brak karty w czasie), `cancelled`, `unavailable` (brak urządzenia/uprawnienia),
`bad_request` (zła walidacja), `write_failed`/`erase_failed` (status urządzenia ≠ `0`).

**Eventy:** `msr605x.swipe` (każdy odczyt, obok unified `magneticCardReader.onSwipe`), `msr605x.attached`,
`msr605x.detached`.
