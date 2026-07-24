# Sunmi Scanner — analiza wsteczna i walidacja możliwości modelu

**Status: analiza + zaimplementowana walidacja.** Dokument zbiera wynik dezasemblacji
klienta usługi `com.sunmi.scanner` oraz opisuje, gdzie jest „zaszyta” informacja o tym, czy
dany model skanera obsługuje daną funkcję i jak ją wykorzystujemy do walidacji.

---

## 1. Kontekst i środowisko analizy

Zadanie zakładało wyciągnięcie APK usługi `com.sunmi.scanner` z podłączonego przez ADB
telefonu i zdezasemblowanie jej. W środowisku, w którym pracował agent (kontener w chmurze),
**żadne urządzenie nie było osiągalne** — `adb devices` nie pokazywał telefonu (brak
przekazania USB, brak sieciowego celu ADB). Telefon podłączony do lokalnej maszyny nie jest
widoczny z kontenera.

Zamiast tego przeanalizowano **dołączony do repo, nowszy odpowiednik po stronie klienta**:
`vendor/libs/SunmiScannerSdk-release-v1.1.12.aar`. To jest oficjalne SDK Sunmi, które rozmawia
dokładnie tym samym protokołem `scanXXXX=..;`, ale w nowszej wersji niż ta, z której ręcznie
przepisano `SunmiHelper`/`ScannerService`/`CodeConstants`. AAR zdekompilowano (CFR) i na jego
podstawie odtworzono protokół, encje i zestaw parametrów.

Uzupełniająco zweryfikowano oficjalną dokumentację Sunmi (Scanner User Guide) oraz publiczne
dekompilaty AIDL — patrz sekcja Źródła.

> Aby uzyskać **dokładną** zawartość plików capability (patrz §4) trzeba wyciągnąć je z
> urządzenia (są w assetach usługi). Tego kroku nie dało się wykonać bez dostępu do telefonu.
> Jeśli uruchomisz to lokalnie z podłączonym urządzeniem:
> `adb shell pm path com.sunmi.scanner` → `adb pull <ścieżka do base.apk>` → rozpakuj
> `assets/*Config.json`.

---

## 2. Jak naprawdę działa protokół

Klient wiąże się z usługą:

- pakiet: `com.sunmi.scanner`
- akcja:  `com.sunmi.scanner.IScanInterface`

`IScanInterface` (AIDL) w nowszej wersji ma **19 metod** (starsze publiczne dekompilaty
pokazują tylko `sendKeyEvent/scan/stop/getScannerModel`):

| # | metoda | rola |
|---|--------|------|
| 1 | `sendKeyEvent(KeyEvent)` | programowy trigger (klawiszem) |
| 2 | `scan()` / 3 `stop()` | start/stop skanowania |
| 4 | `getScannerModel()` → int | **identyfikator silnika skanera (100–122)** |
| 5 | `setScannerModel(int)` | wymuszenie modelu (niebezpieczne — patrz niżej) |
| 6 | `sendCommand(String)` | wysłanie komendy konfiguracyjnej `scanXXXX=..;` |
| 7 | `sendQuery(String, ICallBack)` | **odczyt konfiguracji** (zwraca typowaną encję) |
| 8 | `clearConfig()` → bool | reset konfiguracji do domyślnej |
| 9–14 | `onScreenOn/Off`, `onCameraOn/Off`, `isNeedCameraHandle`, `isSdkRelease` | cykl życia / kamera |
| 15–16 | `openDevice(...)`, `closeDevice(...)` | skanery zewnętrzne (USB/szeregowe) |
| 17–18 | `register/unregisterDecodeCallback(String, IDataCallback)` | strumień wyników skanów |
| 19 | `switchSpecialScene(int)` | przełączanie „scen” |

### Kluczowa obserwacja: to nie jest tylko „wysyłanie stringów”

Nowy SDK nie strzela w ciemno komendami `scan0002033=1;`. Ma pełny **protokół zapytań**
(`sendQuery`), który zwraca **typowane, sparcelowane encje**:

| zapytanie (stała w `SunmiHelper`) | kod | zwraca encję |
|-----------------------------------|-----|--------------|
| `QUERY_ALL_SETTING_INFO` | `sunmi001000` | `ServiceSetting` (wszystkie ustawienia usługi) |
| `QUERY_ALL_ENABLE_CODE`  | `scan0001000` | `CodeEnable` (**lista symbologii + stan włączenia**) |
| `QUERY_<SYMBOLOGY>_SETTING` | np. `scan0002001` | `CodeSetting` (min/max len, check char, itd.) |
| `QUERY_ADVANCED_FORMAT`  | `sunmi001001` | `List<Pair>` (reguły przekształceń wyjścia) |

To jest właśnie „lepszy sposób”: obecny plugin (`SunmiInnerScannerPlugin`) już z tego korzysta
do **odczytu** ustawień (metody `get*` wołają `sendQuery` i deserializują `ServiceSetting`/
`CodeEnable`/`CodeSetting`). `SunmiHelper` służy już tylko do **budowania łańcuchów komend**
(`createCmd`), a `ScannerService` trzyma mapę `id → nazwa modelu`. Reszta idzie przez typowane
API SDK.

---

## 3. Architektura w repo (stan obecny)

```
plugin-sunmi-scanner-common-lib/
  common/compat/SunmiHelper.java     – budowanie komend scanXXXX=..;  (przepisane z dezasm.)
  common/compat/ScannerService.java  – mapa id→nazwa modelu (100–122) (przepisane z dezasm.)
  common/compat/CodeConstants.java   – nazwy symbologii + (ślad) nazwy plików capability
  common/ScannerServiceManager.kt    – ref-counting wrapper na ScannerManager (bind/unbind)
plugin-sunmi-scanner-inner-lib/
  inner/SunmiInnerScannerPlugin.kt   – właściwy plugin HAL (metody get*/set*/trigger/…)
plugin-sunmi-sunmiscannersdk-sdk     – opakowany AAR SunmiScannerSdk (com.sunmi.scanner.*)
```

Plugin **już** opiera się na nowym SDK (`InnerScanner`, `sendQuery`, `QueryCallback`,
typowane encje). „Przepisane gówno” to w praktyce tylko `SunmiHelper` (kodowanie komend) i
`ScannerService` (tabela modeli) — i te dwa pliki były wzięte ze **starszej** wersji usługi.

---

## 4. Gdzie jest „zaszyta” walidacja modelu ↔ funkcja

To był główny cel. Odpowiedź ma trzy warstwy.

### 4a. Statycznie — pliki `*Config.json` w assetach usługi

W zdekompilowanym `CodeConstants` (przepisanym ze starszej usługi) zostały ślady prywatnych
stałych, które nazywają pliki konfiguracji per-silnik. To jest **fizyczne miejsce**, gdzie
usługa trzyma „co dany silnik potrafi”:

```
HoneywellConfig.json        / HoneywellDefaultConfig.json
NewlandConfig.json          / NewlandDefaultConfig.json
  + override:  Newland2096Config.json, Newland2596Config.json, Newland3108Config.json
ZebraConfig.json            / ZebraDefaultConfig.json
  + override:  Zebra1350Config.json
SmConfig.json               / SmDefaultConfig.json
Fp1825Nls1365Config.json    / Fp1825Nls1365DefaultConfig.json
```

Każdy plik (jeden na **rodzinę silnika**, z nadpisaniami dla kilku modeli) wylicza symbologie i
zakresy parametrów, które silnik przyjmuje. Komenda `scanXXXX=..;` dla symbologii, której silnik
nie ma, jest **po cichu ignorowana** przez usługę — stąd „zawodność”. Tych plików nie dało się
odczytać bez urządzenia (instrukcja wyciągnięcia — §1).

### 4b. W czasie działania — `QUERY_ALL_ENABLE_CODE`

Usługa sama udostępnia rzut tych danych: `sendQuery("scan0001000")` zwraca `CodeEnable`, w którym
`codes[]` to symbologie znane usłudze dla **aktualnie podłączonego** silnika, a `enable[]` to ich
stan. To jest praktyczne, dostępne w runtime źródło prawdy o obsługiwanych symbologiach.

> Uwaga: publiczna dokumentacja Sunmi nie obiecuje, że `codes[]` jest przefiltrowane per-model
> (część firmware może zwracać pełną listę). Dlatego traktujemy tę warstwę jako **miękką**
> (permisywną, gdy brak konkretnej listy) i uzupełniamy ją warstwą statyczną 4c.

### 4c. Statyczna macierz 1D/2D z dokumentacji Sunmi

Sunmi publikuje macierz symbologii per-rodzina-silnika (Scanner User Guide, Załącznik). Wynika z
niej twardo, które silniki są **liniowe 1D** i fizycznie nie odczytają kodów 2D
(QR / DataMatrix / PDF417 / Aztec / MaxiCode / HanXin):

- **1D-only:** `101 SUPER_N1365_Y1825`, `107 ZEBRA_1350`, `112 ZEBRA_965`
- **2D:** pozostałe silniki Newland / Zebra / Honeywell z listy
- **nieznane:** silniki własne Sunmi `SM_SS_*` (113, 118, 119, 121, 122) — Sunmi nie publikuje
  dla nich danych; traktujemy jako „nieokreślone” i nie blokujemy

### Tabela modeli (100–122)

Zweryfikowano, że **nie istnieją publicznie udokumentowane ID > 122** — tabela w repo jest
kompletna i aktualna. Kolumna 1D/2D: `[G]` = z macierzy Sunmi, `[V]` = z numeru części silnika,
`[?]` = brak danych (silnik własny Sunmi).

| ID | nazwa | rodzina | 2D? |
|----|-------|---------|-----|
| 100 | NONE | — | — |
| 101 | SUPER_N1365_Y1825 | FP1825_NLS1365 | **nie [G]** |
| 102 | NLS_2096 | NEWLAND | tak [G] |
| 103 | ZEBRA_4710 | ZEBRA | tak [G] |
| 104 | HONEYWELL_3601 | HONEYWELL | tak [G] |
| 105 | HONEYWELL_6603 | HONEYWELL | tak [G] |
| 106 | ZEBRA_4750 | ZEBRA | tak [G] |
| 107 | ZEBRA_1350 | ZEBRA | **nie [G]** |
| 108 | HONEYWELL_6703 | HONEYWELL | tak [V] |
| 109 | HONEYWELL_3603 | HONEYWELL | tak [V] |
| 110 | NLS_CM47 | NEWLAND | tak [V] |
| 111 | NLS_3108 | NEWLAND | tak [V] |
| 112 | ZEBRA_965 | ZEBRA | **nie [G]** (laser) |
| 113 | SM_SS_1100 | SM | ? |
| 114 | NLS_CM30 | NEWLAND | tak [V] |
| 115 | HONEYWELL_4603 | HONEYWELL | tak [V] |
| 116 | ZEBRA_4770 | ZEBRA | tak [V] |
| 117 | NLS_2596 | NEWLAND | tak [V] |
| 118 | SM_SS_1103 | SM | ? |
| 119 | SM_SS_1101 | SM | ? |
| 120 | HONEYWELL_5703 | HONEYWELL | tak [V] |
| 121 | SM_SS_1100_2 | SM | ? |
| 122 | SM_SS_1104 | SM | ? |

Uwaga do `setScannerModel(int)`: to nadpisanie fabryczne — zły argument potrafi wyłączyć usługę
skanera do czasu wyczyszczenia cache. Nie używać jako „API możliwości”.

---

## 5. Zaimplementowana walidacja

Dodane pliki (moduł `plugin-sunmi-scanner-common-lib`):

- **`ScannerModelInfo.kt`** — strukturalne info o modelu: `id`, `name`, `engineFamily`
  (NEWLAND/ZEBRA/HONEYWELL/SM/FP1825_NLS1365/NONE/UNKNOWN) i `supports2d: Boolean?`
  (`true`/`false`/`null`=nieznane). Nieznane/nowsze ID degradują się do `UNKNOWN`/`null`.
- **`ScannerCapabilities.kt`** — runtime „oracle”: `supportedBarcodes(scanner)` odpytuje
  `QUERY_ALL_ENABLE_CODE`, cache’uje wynik (invalidacja na reconnect / `setScannerModel`),
  permisywnie zwraca `null` gdy usługa nie odpowie. Zawiera też statyczny zbiór symbologii 2D
  (`TWO_D_SYMBOLOGIES`, po nazwach z `CodeConstants`) i `is2dSymbology(name)`.

Zmiany w `SunmiInnerScannerPlugin.kt`:

- `setBarcode` i `setBarcodeConfig` **walidują** symbologię przed wysłaniem komendy:
  1. **statycznie** — silnik 1D (`supports2d == false`) + symbologia 2D → `badRequest`
     (działa bez round-tripu, wyłącznie z ID modelu);
  2. **w runtime** — jeśli usługa zwróciła konkretną listę i nie ma w niej symbologii →
     `badRequest`. Gdy listy brak → przepuszcza (nie psuje działającej konfiguracji).
- `getScannerModel` zwraca teraz też `engine` i `supports2d`.
- Nowe metody:
  - `sunmi.scanner.inner.getSupportedBarcodes` — lista symbologii wspieranych przez podłączony
    silnik (+ `determinable`, gdy usługa nie podała konkretnego zbioru);
  - `sunmi.scanner.inner.isBarcodeSupported {name}` — `{supported, determinable, model}`.

Zachowanie jest **permisywne przy niepewności** (nigdy nie blokuje, gdy nie wiadomo), a
**twarde tam, gdzie wiadomo na pewno** (2D na silniku 1D). To bezpośrednio adresuje „zawodność”:
próba włączenia np. QR na `SUPER_N1365_Y1825` dostaje jasny błąd zamiast po cichu ignorowanej
komendy.

---

## 6. Nowe parametry konfiguracyjne (nowsza wersja vs. przepisana)

**Najważniejsze ustalenie:** nowe parametry **nie są** nowymi komendami `scanXXXX`/`sunmiXXXX`.
Nowsze SDK dokłada klasę wartości `BarcodeScannerParams` i menedżer `LittleFlashScanner`, który
wysyła je **binarnym protokołem silnika** (`DeviceControl`, PROCODE_START/STOP/ACK/NAK,
`packageFormat`/`sendData`) — a nie przez `IScanInterface.sendCommand(String)`. Dlatego nie da
się (i nie należy) dopisywać ich do `SunmiHelper` jako `sunmiXXXX=..;`. Aby ich użyć, trzeba
sięgnąć po API `LittleFlashScanner` (jest w dołączonym AAR:
`com.sunmi.scanner.manager.LittleFlashScanner`, obok `AbstractScanManager`).

### 6a. Nowe klasy parametrów (wartości) — przez `LittleFlashScanner`

Wartości potwierdzone wprost z dołączonego `SunmiScannerSdk-release-v1.1.12.aar`
(`com.sunmi.scanner.constants.BarcodeScannerParams`), setter z `LittleFlashScanner`:

| Parametr (klasa `BarcodeScannerParams`) | setter (`LittleFlashScanner`) | wartości (int) |
|---|---|---|
| `SensitivityLevelModel` | `setSensitivityLevel` | Special=0, High=1 (=Default), Middle=8, Low=15 |
| `DurationScanMode` (sekundy) | `setDurationInScan` | Time_1=0, Time_3=1, Time_4=2, Time_5=3, Time_10=4, Time_15=5, Time_20=6, Time_0=7 |
| `IntervalTimeModel` | `setIntervalTime` | Time_0_S=0, Time_Half_S=1, Time_3_S=2 |
| `OutputIntervalOfSameCodeModel` (ms, ten sam kod) | `setOutputIntervalOfSameCode` | Time_300=0, Time_500=1, Time_1500=2, Time_3000=3, Time_0=4 |
| `PosLightsControlModel` (podświetlenie POS) | `setPosLightsControl` | Lighting_When_Read=0, Always_Lighting=1, Always_Close=2 |
| `TransmitNoReadMode` | `setTransmitNoRead` | Enable=0, Disable=1 |
| `ScanWorkModel.Global1DSwitch` | `set1DBarcodeSwitch` | Open=0, Close=1 |
| `ScanWorkModel.Global2DSwitch` | `set2DBarcodeSwitch` | Open=2, Close=3 |
| `ScanWorkModel.AllBarcodeSwitch` | `setAllBarcodeSwitch` | Open=4, Close=5 |
| `ScanMode` | (`mScanMode`/params) | Continuous=0, Host=1, AutoInduction=2 |

`LittleFlashScanner` to menedżer dla określonego typu skanera („little flash”). **Wymaga
weryfikacji na urządzeniu**, których modeli/silników dotyczy — nie zakładać, że działa dla
każdego wbudowanego silnika.

### 6b. Nowa komenda stringowa `scan00000108`

W nowszej usłudze (Scanner Service 4.3.x) pojawia się kod `scan00000108`, nieobecny w
przepisanym `SunmiHelper` (mamy 103/104/105/106/107/109). **Funkcja nieustalona** z samego
dekompilatu (obfuskacja). Nie dopisujemy „w ciemno” — do potwierdzenia na urządzeniu.

### 6c. Co już mamy (bez zmian)

Rodziny `sunmi006xxx` (advanced format / regex) i `sunmi003xxx` (akcje wyjścia / start/stop
decode) są już w `SunmiHelper` i w pluginie; potwierdzone jako nadal obsługiwane w usłudze
4.3.x. Nic do dodania.

### 6d. Rekomendacje

1. **Walidacja modelu ↔ funkcja** (§5) — zaimplementowana, adresuje główną „zawodność”.
2. **Nowe parametry §6a** — jeśli potrzebne, dodać osobną ścieżkę opartą o `LittleFlashScanner`
   (osobny plugin lub zestaw metod), z weryfikacją na urządzeniu. **Nie** dodawać jako
   `scanXXXX`/`sunmiXXXX`.
3. **`scan00000108`** — najpierw ustalić funkcję na urządzeniu.
4. **Pliki capability §4a** — wyciągnąć `assets/*Config.json` z urządzenia, by mieć pełną,
   offline’ową macierz per-model (uzupełnia runtime `QUERY_ALL_ENABLE_CODE`).

---

## Źródła

- Zdekompilowane `vendor/libs/SunmiScannerSdk-release-v1.1.12.aar` (CFR 0.152):
  `com.sunmi.scanner.IScanInterface`, `...entity.{ServiceSetting,CodeEnable,CodeSetting,Result,Entity,Pair}`,
  `...constants.BarcodeScannerParams`, `...sdk.{InnerScanner,ScannerManager}`.
- Sunmi Scanner User Guide (macierz symbologii per silnik, lista modeli 100–122):
  https://docs.sunmi.com/read/en-US/frmeghjk546
- Publiczne dekompilaty AIDL potwierdzające zestaw metod i listę modeli:
  https://github.com/FrenkyDema/sunmi_scanner , https://github.com/DevHugo/ReproScannerSunmi ,
  https://github.com/gmfe/gm_pda_scanner
- Dekompilaty APK usługi (diff wersji: `SunmiHelper`, `ConfigUtils`, `LittleFlashScanner`,
  komenda `scan00000108`): https://github.com/kduma-autoid/apk-sources — Scanner 2.16.52.4 oraz
  Scanner Service 4.3.3 / 4.3.9, SunmiScannerSdk-release-v1.1.11.
