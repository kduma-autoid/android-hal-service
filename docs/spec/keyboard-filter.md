# Filtr klawiatury HAL

Część peryferiów (skanery, czytniki kart magnetycznych) emuluje klawiaturę USB HID: oprócz danych
dostarczanych przez API vendora „wpisuje” je jako naciśnięcia klawiszy, łącznie z końcowym Enterem.
Gdy klient HAL odbiera dane przez plugin, te naciśnięcia trafiałyby do aplikacji na pierwszym planie.

Filtr klawiatury HAL pochłania zdarzenia DOWN/UP z takich urządzeń.

## Architektura

- **Jedna usługa dostępności** — `dev.duma.android.hal.service.keyboardfilter.HalKeyboardFilterService`
  w głównym APK HAL (`dev.duma.android.hal.service`), z `flagRequestFilterKeyEvents`
  i `canRetrieveWindowContent=false`. Nie czyta treści okien.
- Pluginy **nie deklarują własnych usług**. Zgłaszają urządzenia przez
  `HalPlugin.getKeyboardFilterDevices(): List<KeyboardDeviceId>` (USB VID/PID). Użytkownik włącza
  jedną usługę niezależnie od liczby urządzeń.
- `PluginRegistry` pyta plugin raz, przy rejestracji (built-in i z bundla), i utrzymuje sumę list
  jako niezmienny snapshot `keyboardFilterDevices`. Przy wyparciu, rozłączeniu bundla i przywróceniu
  wypartego built-in lista jest podmieniana razem z pluginem. Plugin nieobsługiwany (`isSupported=false`)
  nic nie wnosi; wyjątek z `getKeyboardFilterDevices()` oznacza brak urządzeń, rejestracja trwa.
- Usługa działa w procesie HAL i przy każdym zdarzeniu czyta snapshot z `HalService.pluginRegistry`.
  Konsumuje wyłącznie `ACTION_DOWN`/`ACTION_UP` z urządzenia o zgłoszonym VID/PID; inne klawiatury
  i inne akcje przechodzą. Gdy HAL nie działa albo nikt nic nie zgłosił, nie filtruje niczego.
- Filtr działa przez cały czas, gdy usługa jest włączona — także poza aktywnym skanowaniem.

## Zgłaszanie urządzeń przez plugin

```kotlin
override fun getKeyboardFilterDevices() = listOf(KeyboardDeviceId(vendorId = 0x1A86, productId = 0x7523))
```

Lista powinna być stała dla instancji pluginu (jest odczytywana tylko przy rejestracji).

## Zgodność AIDL

Pluginy z bundli przekazują listę przez `IHardwarePlugin.getKeyboardFilterDevicesJson()`, dopisaną na
końcu interfejsu (numery wcześniejszych transakcji bez zmian). Nie jest to pole `PluginDescriptor`,
bo stary host dekoduje deskryptor bez `ignoreUnknownKeys`.

- nowy host + stary bundle — brak transakcji: `null`/wyjątek → brak urządzeń;
- stary host + nowy bundle — host nigdy nie woła nowej metody; brak filtrowania.

## Status

`KeyboardFilter.isEnabled(context)` (hal-contract) sprawdza globalne włączenie dostępności i dokładny
komponent `dev.duma.android.hal.service/…HalKeyboardFilterService` w `ENABLED_ACCESSIBILITY_SERVICES`.
Działa również w bundlu (Settings.Secure jest czytelne dla każdej aplikacji). Pokazuje wyłącznie stan
ustawień — nie dowodzi, że usługa otrzymuje zdarzenia (Android/OEM, inne filtry dostępności, HAL musi
działać); skuteczność trzeba sprawdzić na sprzęcie.

Panel pluginu w aplikacji HAL (`PluginDetailActivity`) dla pluginów ze zgłoszonymi urządzeniami pokazuje
listę VID:PID, status i przycisk do ustawień dostępności. Włączenie usługi wykonuje użytkownik.

## Diagnostyka

- `adb shell setprop log.tag.HalKbdFilter DEBUG` — usługa loguje raz na urządzenie jego metadane
  (id, nazwa, VID:PID, descriptor) i czy jest filtrowane. Nigdy nie loguje kodów klawiszy.
- `KeyboardFilter.describeInputDevices(filtered)` — lista `InputDevice` znanych Androidowi
  (bez zdarzeń klawiszy); plugin może ją udostępnić jako metodę diagnostyczną, aby ustalić VID/PID
  urządzenia, z którego faktycznie przychodzą naciśnięcia.

## Migracja istniejących filtrów

Plugin mający własną usługę dostępności (np. `Ss2222KeyboardFilterService` z PR #9) powinien ją usunąć
(klasa, wpis w manifeście, XML, stringi), nadpisać `getKeyboardFilterDevices()` swoim VID/PID,
a pole statusu (np. `keyboardFilterEnabled`) liczyć przez `KeyboardFilter.isEnabled`.

## Zarejestrowane urządzenia

### `sunmi.card` — czytnik kart magnetycznych (FLEX 3)

- Zgłasza `1A86:7523` — ten sam VID/PID, po którym plugin wykrywa czytnik (mostek WCH). **Do potwierdzenia
  na sprzęcie**: naciśnięcia mogą pochodzić z osobnego interfejsu HID o innym VID/PID.
- `sunmi.card.getInfo` zwraca `keyboardFilterEnabled` (stan ustawień, patrz [Status](#status)).
- `sunmi.card.listInputDevices` (tylko `development`) — wynik `KeyboardFilter.describeInputDevices`
  z polem `filtered`. Procedura: podłączyć czytnik, wywołać metodę, znaleźć urządzenie z `keyboard: true`
  odpowiadające czytnikowi; jeżeli `filtered` jest `false`, poprawić `KEYBOARD_DEVICES` w `SunmiCardPlugin`.
- Dane karty nadal przychodzą zdarzeniem `sunmi.card.swipe` z `CardManager`; filtr nie składa
  naciśnięć w dane.
