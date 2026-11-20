# Filtr klawiatury HAL

Część peryferiów (skanery, czytniki kart magnetycznych) emuluje klawiaturę USB HID: oprócz danych
dostarczanych przez API vendora „wpisuje” je jako naciśnięcia klawiszy, łącznie z końcowym Enterem.
Gdy klient HAL odbiera dane przez plugin, te naciśnięcia trafiałyby do aplikacji na pierwszym planie.

Filtr klawiatury HAL pochłania zdarzenia DOWN/UP z takich urządzeń.

## Architektura

- **Jedna usługa dostępności** — `dev.duma.android.hal.service.keyboardfilter.HalKeyboardFilterService`
  w głównym APK HAL (`dev.duma.android.hal.service`), z `flagRequestFilterKeyEvents`
  i `canRetrieveWindowContent=false`. Nie czyta treści okien.
- Pluginy **nie deklarują własnych usług**. Zgłaszają reguły przez
  `HalPlugin.getKeyboardFilterRules(): List<KeyboardFilterRule>` (patrz [Reguły](#reguły)). Użytkownik
  włącza jedną usługę niezależnie od liczby urządzeń.
- `PluginRegistry` pyta plugin raz, przy rejestracji (built-in i z bundla), i utrzymuje sumę list
  jako niezmienny snapshot `keyboardFilterRules`. Przy wyparciu, rozłączeniu bundla i przywróceniu
  wypartego built-in lista jest podmieniana razem z pluginem. Plugin nieobsługiwany (`isSupported=false`)
  nic nie wnosi; wyjątek z `getKeyboardFilterRules()` oznacza brak reguł, rejestracja trwa.
- Usługa działa w procesie HAL i przy każdym zdarzeniu czyta snapshot z `HalService.pluginRegistry`.
  Konsumuje wyłącznie `ACTION_DOWN`/`ACTION_UP` pasujące do którejś reguły; inne klawiatury, inne
  wstrzykiwane zdarzenia i inne akcje przechodzą. Gdy HAL nie działa albo nikt nic nie zgłosił, nie filtruje niczego.
- Filtr działa przez cały czas, gdy usługa jest włączona — także poza aktywnym skanowaniem.

## Reguły

`KeyboardFilterRule` to sealed interface; każdy rodzaj reguły mówi wprost, po czym dopasowuje zdarzenie
(`KeyOrigin`: VID/PID urządzenia wejściowego i `KeyEvent.getSource()`):

| Reguła | Dopasowuje | Kiedy |
|--------|------------|-------|
| `UsbDevice(vendorId, productId)` | każde zdarzenie z urządzenia o tym VID/PID | urządzenie samo jest klawiaturą USB HID (keyboard wedge), np. skaner SS2222 (PR #9) |
| `EventSource(source)` | zdarzenia z dokładnie tym `source`, z dowolnego urządzenia | dane „wpisuje” serwis producenta przez `injectInputEvent`, oznaczając zdarzenia własną wartością `source` (np. czytnik MSR Sunmi) |

Zdarzenia wstrzykiwane programowo przychodzą z wirtualnej klawiatury Androida (id -1, VID/PID
`0000:0000`), więc `UsbDevice` ich nie złapie. `EventSource` łapie tylko zdarzenia z danym znacznikiem;
inne wstrzykiwane wejście (inne aplikacje, `adb shell input`) przechodzi.

```kotlin
override fun getKeyboardFilterRules() = listOf(
    KeyboardFilterRule.UsbDevice(vendorId = 0x324F, productId = 0x0113),
)
```

Lista powinna być stała dla instancji pluginu (jest odczytywana tylko przy rejestracji).

## Zgodność AIDL

Pluginy z bundli przekazują listę przez `IHardwarePlugin.getKeyboardFilterRulesJson()` — JSON z rodzajem
reguły w polu `type` (`usb`, `source`), np. `[{"type":"usb","vendorId":12879,"productId":275}]`. Lista
z nieznanym rodzajem reguły jest odrzucana w całości (brak filtrowania zamiast filtrowania częściowego).
Metoda jest dopisana na
końcu interfejsu (numery wcześniejszych transakcji bez zmian). Nie jest to pole `PluginDescriptor`,
bo stary host dekoduje deskryptor bez `ignoreUnknownKeys`.

- nowy host + stary bundle — brak transakcji: `null`/wyjątek → brak reguł;
- stary host + nowy bundle — host nigdy nie woła nowej metody; brak filtrowania.

## Status

`KeyboardFilter.isEnabled(context)` (hal-contract) sprawdza globalne włączenie dostępności i dokładny
komponent `dev.duma.android.hal.service/…HalKeyboardFilterService` w `ENABLED_ACCESSIBILITY_SERVICES`.
Działa również w bundlu (Settings.Secure jest czytelne dla każdej aplikacji). Pokazuje wyłącznie stan
ustawień — nie dowodzi, że usługa otrzymuje zdarzenia (Android/OEM, inne filtry dostępności, HAL musi
działać); skuteczność trzeba sprawdzić na sprzęcie.

Panel pluginu w aplikacji HAL (`PluginDetailActivity`) dla pluginów ze zgłoszonymi regułami pokazuje
ich listę (np. `USB 324F:0113`, `event source 0x98123456`), status i przycisk do ustawień dostępności. Włączenie usługi wykonuje użytkownik.

## Diagnostyka

- `adb shell setprop log.tag.HalKbdFilter DEBUG` — usługa loguje raz na parę urządzenie/`source`
  metadane (id, nazwa, VID:PID, `source`, descriptor) i czy zdarzenie jest filtrowane. Nigdy nie loguje kodów klawiszy.
- `KeyboardFilter.describeInputDevices(filtered)` — lista `InputDevice` znanych Androidowi
  (bez zdarzeń klawiszy); plugin może ją udostępnić jako metodę diagnostyczną, aby ustalić VID/PID
  urządzenia, z którego faktycznie przychodzą naciśnięcia. `filtered` oznacza urządzenia objęte regułą
  `UsbDevice`; reguły `EventSource` dotyczą zdarzeń, nie urządzeń.
- Gdy żadna klawiatura nie odpowiada czytnikowi, a znaki i tak się pojawiają, są wstrzykiwane programowo:
  `adb shell getevent -l` nie pokaże wtedy `EV_KEY`, a `dumpsys input` pokaże zdarzenia klawiszy.
  Wartość `source` znajdzie się w logu usługi (wyżej) albo w kodzie serwisu producenta.

## Migracja istniejących filtrów

Plugin mający własną usługę dostępności (np. `Ss2222KeyboardFilterService` z PR #9) powinien ją usunąć
(klasa, wpis w manifeście, XML, stringi), nadpisać `getKeyboardFilterRules()` regułą `UsbDevice` ze swoim VID/PID,
a pole statusu (np. `keyboardFilterEnabled`) liczyć przez `KeyboardFilter.isEnabled`.
