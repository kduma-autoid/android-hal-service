# Plan: przebudowa wtyczek `sunmi.screen` i `sunmi.nfc`

Status: szkic, do realizacji. Ustalenia z dekompilacji `SunmiUsbScreen` v2.8.13,
`SunmiPeripheralManager` v1.0.77 i `sunmiperipher_sdk_v1.0.2.aar` oraz z pomiarów
na OrderPAD 3 i FLEX 3 (2026-11-20).

## 1. Ustalenia

### 1.1. Callback usługi ekranów (`ISetCall`)

Sygnatura to `onCallBack(sn, type, key, value, str)`. Do niedawna wtyczka czytała
ją jako `(sn, type, value, extra1, extra2)`, więc pod `value` był `key`, a
prawdziwe `value` przepadało (poprawione na tej gałęzi).

To **nie** jest zdarzenie o zmianie listy ekranów. To raport wartości parametru,
który MCU ekranu odsyła po USB: potwierdzenie `setParameter` albo zmiana, którą
usługa zrobiła sama (np. przed wyłączeniem ekranu sama wyłącza dotyk).

- `type`: 1 = OPTION (parametr ekranu), 2 = DC_FLAG (`key` = indeks flagi;
  znaczenie flag nieustalone).
  - DC_FLAG idzie do ekranu jako typ protokołu 6 (`TYPE_DISPLAY_DC_FLAG`)
    z parą (indeks, wartość), analogicznie do znaku wodnego NFC (typ 9).
    Usługa sama go nigdy nie wysyła.
  - Test na NP511 `MS02P43R40005` (20.11, OrderPAD 3): ekran przyjmuje
    indeksy 0–3 i odsyła raport z tym samym indeksem. Wartość jest
    sprowadzana do 0/1 (wysłane 50, 99 i 255 wracają jako 1), więc to
    włączniki, a nie skala. Przełączanie pojedynczo i wszystkich naraz
    nie zmienia niczego widocznego na ekranie. To stan w firmware bez
    efektu w UI, więc w API go nie wystawiamy.
- `key` dla OPTION — te same numery co w `setParameter(sn, 1, key, value, str)`:

  | key | parametr | key | parametr |
  |---|---|---|---|
  | 0 | jasność (0–100) | 16 | test panelu dotykowego |
  | 1 | głośność | 18 | nazwa ekranu |
  | 2 | ekran wł./wył. | 19 | przezroczystość nazwy (0–100, globalnie) |
  | 3/4/5 | testy CPU/DDR/pamięci | 20 | pokazywanie nazwy (0/1, globalnie) |
  | 6 | NFC (tylko set) | 21 | temperatura barwowa (0–255) |
  | 7 | dotyk wł./wył. | 257 | tryb pracy |
  | 14 | wyłączenie zasilania (tylko set) | 256/258/259 | logi / restart (tylko set) |

  Przez callback przychodzą tylko 0–5, 7, 16, 18–21 i 257.
- `value`: wartość odesłana przez ekran; `str`: tekst (nazwa ekranu), zwykle pusty.
- Błędy (`value = -1`) usługa wysyła tylko broadcastem, nie przez callback.

### 1.2. Lista ekranów

AIDL usługi (`getDisplayInfo`, `setCallback`, `setParameter`) nie zgłasza
podłączenia ani odłączenia ekranu. Usługa jednak przy każdej zmianie stanu ekranu
przepisuje `Settings.Global.sub_display_device_info` listą **tylko podłączonych**
ekranów, w formacie `getDisplayInfo()`:

```json
[{"sn":"MS02P43R40005","model":"NP511","width":1280,"height":800,"screen_size":"10.1",
  "nfc_type":0,"usb_ports":2,"cpu":"T113-S3","mem":134217728,"storage":134217728,
  "ver_app":"1.4.13","ver_fw":"1.4.13","ver_hw":"1.0","ver_resource":"1.1.1",
  "ver_uboot":"1.2.9","external_display":false,"touch_port":0, ...}]
```

`SubScreenManager.getDeviceInfo()` z SDK obcina to do `sn → model`.

Usługa trzyma też stan per ekran w `Settings.Global`:
`sunmi_subscreen_power/<sn>`, `sunmi_subscreen_touch/<sn>`,
`sunmi_subscreen_brightness/<sn>`, `sunmi_subscreen_nickname/<sn>` oraz globalne
`sunmi_subscreen_nickname_switch`. Nie wiadomo jeszcze, czy to stan bieżący,
czy zapamiętane ustawienie (patrz §6).

### 1.3. Czytniki NFC

Czytnikami zarządza `com.sunmi.peripheralmanager` (`NfcCtrlService`). SDK dostaje
listę przez `INfcListener.onNfcListChanged(List<Nfc>)`; `Nfc` ma pola `sn`,
`type`, `isSelect`, `waterMarkAlpha`. `type` to `NfcType.ordinal()`:

| `type` | `NfcType` | Znaczenie |
|---|---|---|
| 0 | HOST | wbudowane w terminal |
| 1 | EXT | zewnętrzne, stary mechanizm ze stałym SN `S123456789010` |
| 2 | ACCESSORY | zewnętrzny czytnik NFC |
| 3 | SUB_SCREEN | NFC w ekranie zewnętrznym |

Zweryfikowane w HAL na OrderPAD 3 (2026-11-20), listener dostaje listę od razu
po rejestracji:

| SN | `type` | Co to jest |
|---|---|---|
| LE12P59T00066 | 0 (host), `selected` | NFC wbudowane |
| MS02P43R40005 | 3 (subScreen) | NFC w ekranie NP511 |
| S123456789100 | 2 (accessory) | zewnętrzny czytnik NFC |

SN modułu `subScreen` jest równy SN ekranu z `sub_display_device_info` — to
klucz łączący obie wtyczki.

Heurystyka z PR kduma-autoid/android-hal-service#8 (SN hosta z TMS/Androida +
`sub_display_device_info`) daje dla akcesorium `reader: unknown`. Typ z SDK
rozwiązuje to bez zgadywania.

## 2. Wspólne zasady obu wtyczek

- **Kategorie jako string-enum** (camelCase), nigdy jako surowe liczby.
  Surowa wartość może się pojawić tylko pomocniczo obok enuma (np. `key` przy
  `parameter: "unknown"`). Nieznana wartość → `"unknown"`, nie błąd.
- **Odczyt stanu i zdarzenie mają ten sam pełny kształt** (snapshot), z polem
  `state: initializing | ready | unavailable` jak w PR #8. Zdarzenie jest
  deduplikowane i niesie cały stan, a nie różnicę.
- **SN jest kluczem** łączącym ekran z jego modułem NFC. Wtyczki nie zależą od
  siebie nawzajem — obie czytają `sub_display_device_info`.
- Wspólny helper do obserwacji `Settings.Global` (dziś osobno w
  `NfcAntennaEnvironment.kt` z PR #8) w module wspólnym dla bundli Sunmi.
- Walidacja parametrów po stronie HAL (`bad_request`), np. nieznany SN.
- `dispose()` zwalnia callbacki, listenery i obserwatory.
- Obie zmiany są niekompatybilne wstecz: `version` deskryptora → 2, nowe wersje
  główne klientów TS, opis w `docs/spec/sunmi-screen.md` i `docs/spec/sunmi-nfc.md`.

## 3. `sunmi.screen` v2

### Model ekranu

```json
{
  "sn": "MS02P43R40005",
  "model": "NP511",
  "nickname": "Customer display 1",
  "display": { "widthPx": 1280, "heightPx": 800, "sizeInch": 10.1 },
  "hasNfc": true,
  "usbPorts": 2,
  "hardware": { "cpu": "T113-S3", "memoryBytes": 134217728, "storageBytes": 134217728 },
  "firmware": { "app": "1.4.13", "fw": "1.4.13", "hw": "1.0", "resource": "1.1.1", "uboot": "1.2.9" },
  "settings": { "screenEnabled": true, "touchEnabled": true, "brightness": 100 }
}
```

`hasNfc` = `nfc_type != -1`. `settings` z ostatniego raportu callbacku, a przed
pierwszym raportem z `Settings.Global` (jeśli §6 potwierdzi, że to stan bieżący).

### Metody

| Metoda | Parametry | Mapowanie |
|---|---|---|
| `getScreens` | `{}` | `{state, screens}`; zastępuje `getDeviceInfo` |
| `setScreenEnabled` | `{sn, enabled}` | key 2 |
| `setTouchEnabled` | `{sn, enabled}` | key 7 |
| `setBrightness` | `{sn, brightness}` 0–100 | key 0 |
| `setVolume` | `{sn, volume}` | key 1 (zakres do ustalenia) |
| `setColorTemperature` | `{sn, value}` 0–255 | key 21 |
| `setNickname` | `{sn, nickname}` | key 18 + `str` (firmware ekranu ≥ 1.2.5) |
| `setNicknameDisplay` | `{visible, transparency}` | key 20 / 19 (globalnie) |

Wszystkie przez `IUsbScreenInterface.setParameter` bezpośrednio, bez
`SubScreenManager`. Testy CIT, logi, restart i wyłączenie zasilania — poza API.

### Zdarzenia

| Zdarzenie | Payload | Źródło |
|---|---|---|
| `sunmi.screen.screensChanged` | `{state, screens, added, removed}` | `sub_display_device_info` oraz zmiana `settings` |
| `sunmi.screen.parameterChanged` | `{sn, parameter, key, value, str}` | callback, `type=1` |

`parameter`: `brightness`, `volume`, `screenEnabled`, `touchEnabled`,
`nickname`, `nicknameTransparency`, `nicknameVisible`, `colorTemperature`,
`workMode`, `cpuTest`, `ddrTest`, `memTest`, `tpTest`, `unknown`.
Raporty DC_FLAG (`type=2`) tylko logujemy: to włączniki 0/1 w firmware ekranu bez widocznego efektu (§1.1).

## 4. `sunmi.nfc` v2

Bazuje na PR #8 (położenie anten), ale rodzaj czytnika bierze z `type` z SDK.

### Snapshot (odczyt i zdarzenie)

```json
{
  "state": "ready",
  "controller": "builtin",
  "watermarkAlpha": 1,
  "modules": [
    { "sn": "LE12P59T00066", "type": "host", "selected": true,
      "location": { "displayId": 0, "rect": { "x": 1440, "y": 0, "width": 480, "height": 400 }, "...": "..." },
      "locationUnavailableReason": null },
    { "sn": "MS02P43R40005", "type": "subScreen", "selected": false,
      "screen": { "sn": "MS02P43R40005", "model": "NP511", "nickname": "Customer display 1" },
      "location": null, "locationUnavailableReason": "subScreenLocationUnknown" },
    { "sn": "S123456789100", "type": "accessory", "selected": false,
      "location": null, "locationUnavailableReason": "accessoryLocationUnknown" }
  ]
}
```

- `type`: `host | ext | accessory | subScreen | unknown` (na tej gałęzi
  `modulesChanged` już tak emituje).
- `screen` tylko dla `subScreen`, dopasowany po SN z `sub_display_device_info`.
- `controller` z `device_mgr_nfc_cur_controller` (`builtin | external | unknown`).
- `watermarkAlpha` z `Nfc.waterMarkAlpha` lub `device_mgr_nfc_watermark_alpha`
  (w ustawieniu jest `0.01`, więc skala do ustalenia — §6).
- Enumy powodów w camelCase (zmiana względem snake_case z PR #8).

### Metody i zdarzenia

| Nazwa | Opis |
|---|---|
| `getModules` | snapshot; łączy dzisiejsze `getAntennaLocations` z PR #8 |
| `switchModule {sn}` | SN musi być na liście (`bad_request`); pusty SN → moduł `host` z listy |
| `setWatermarkAlpha {alpha}` | bez zmian, 0–100 |
| `modulesChanged` | snapshot; zastępuje osobne `antennaLocationsChanged` z PR #8 |

### Co znika

- Rozpoznawanie hosta po SN (TMS / `Build.getSerial()` / `ANDROID_ID`) i ponawianie
  odczytu TMS w tle — host to moduł z `type: host`.
- `reader` z PR #8 i powód `reader_identity_unknown` (zostaje tylko dla `unknown`).
- Heurystykę SN można zostawić jako kontrolę spójności z ostrzeżeniem w logu.

## 5. Etapy

1. **Ta gałąź** (gotowe, niezacommitowane): poprawne pola callbacku ekranu,
   `type` jako enum w obu zdarzeniach, `type`/`selected` w `modulesChanged`,
   typy TS.
2. **PR #8**: zamiana heurystyki na `type` z SDK. Decyzja: poprawić PR #8 przed
   merge'em czy zmergować i poprawić w etapie 4.
3. **`sunmi.screen` v2** wg §3 + `docs/spec/sunmi-screen.md`.
4. **`sunmi.nfc` v2** wg §4 (po merge'u PR #8).
5. **Klienci TS i demo**: `@kduma-autoid/hal-client-plugin-sunmi-screen` i
   `-sunmi-nfc` w nowych wersjach głównych; `ScreensNfcView.vue` pokazuje ekrany
   z ich modułami NFC razem.

## 6. Do sprawdzenia na urządzeniu

- [x] `type` modułów NFC w HAL (0 / 3 / 2 dla host / subScreen / accessory).
- [ ] Czy `sunmi_subscreen_power|touch|brightness/<sn>` to stan bieżący.
      Po wyłączeniu ekranu o 17:12 ustawienia nadal pokazują `power=1`, `touch=1`.
- [ ] Odczyt przez `setParameter(..., value = -1)` (`GET_VALUE`) dla parametrów
      innych niż nazwa ekranu.
- [ ] Zakres głośności (key 1).
- [ ] Skala `watermarkAlpha`: `Nfc.waterMarkAlpha` vs ustawienie `0.01`.
- [ ] `switchModule` na akcesorium i na NFC ekranu; czy `selected` i
      `controller` zmieniają się zgodnie.
- [ ] Kiedy pojawia się typ `ext` (stały SN `S123456789010`).
- [x] DC_FLAG: flagi 0–3 to włączniki 0/1 bez widocznego efektu na NP511.
      Zostawione na 0.
- [ ] Zachowanie po odłączeniu ekranu z NFC: kolejność zdarzeń `screensChanged`
      i `modulesChanged`.
