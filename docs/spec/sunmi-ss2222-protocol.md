# Sunmi SS2222: tryby USB i protokół SSI (specyfikacja referencyjna)

Zbiór wszystkiego, co ustalono o skanerze **Sunmi SS2222** („SM-2D PRODUCT DEFHID+KBW”) podłączonym po USB do Sunmi
FLEX 3: tryby USB, ramkowanie, komendy SSI, parametry, czasy i pułapki. Dokument dotyczy samego urządzenia; jak korzysta
z niego HAL, opisuje [`sunmi-ss2222.md`](sunmi-ss2222.md).

**Źródła i pewność.** Dane pochodzą z (1) rozpoznania z października 2026 (aplikacja `scanner-testapp`, dekompilacja
`com.sunmi.scanner`, sweep parametrów) i (2) pomiarów z listopada 2026 (aplikacja-sonda `SS2222 Probe`, sterownik HAL).
Oznaczenia: **✔** sprawdzone na sprzęcie, **¹** wzięte z numeracji Zebry (DS2208 PRG) i niesprawdzone, **?** znaczenie nieznane.

Urządzenie testowe: HW `V3.0.A`, FW `2.4.94`, Product ID `2502D57G02104`, data produkcji `2025.7.18`
(z `F4 01`). Producentem silnika jest najpewniej „FT”/Totinfo (`F4 88`: „Design in FT”, dekoder „OLI Lib”), który
emuluje Zebra SSI z numeracją parametrów Zebry, ale z węższym zakresem funkcji.

## 1. Tryby USB (parametr `F2 01`)

`F2 01` wybiera, czym skaner przedstawia się na USB. Zmiana działa natychmiast: skaner potwierdza ją ACK, znika z magistrali
i po ok. 0,3–1 s wraca z nową tożsamością. Zapis tymczasowy (status `00`) znika po odłączeniu zasilania. ✔ dla wszystkich
wartości `00`–`0F`.

| `F2 01` | VID:PID | Nazwa (iProduct) | Interfejsy | Klawiatura | Kanał SSI | Powrót do `0E` komendą |
|---|---|---|---|---|---|---|
| **`0E`** (fabrycznie) | `324F:0113` | `SM-2D PRODUCT DEFHID+KBW` | IF0 HID boot keyboard (EP `0x81` INT IN); IF1 HID vendor (EP `0x82` INT IN, `0x02` INT OUT, 64 B) | tak | raporty HID na IF1 (§2.1) | – |
| `0A`, `0B`, **`0C`**, `0D` | `AF99:8001` | `TOT2D PRODUCT USB TTDATA` (producent `Totinfo`) | IF0 vendor `255/0/0` (EP `0x81` BULK IN, `0x01` BULK OUT, 64 B) | **nie** | gołe pakiety po bulk (§2.2) | **tak** |
| `02`, `04`, `07`, `08` | `324F:0112` | `SM-2D PRODUCT USB UART` | IF0 CDC Communications `2/2/1` (EP `0x82` INT IN); IF1 CDC Data `10/0/0` (EP `0x81` BULK IN, `0x01` BULK OUT) | **nie** | gołe pakiety po bulk IF1 (§2.2) | **tak** |
| `01`, `03` | `324F:0111` | `SM-2D PRODUCT HID KBW` | IF0 HID boot keyboard (EP `0x81` INT IN, 8 B) | tak | **brak** (nie ma OUT) | **nie** – tylko odłączenie zasilania |
| `00`, `05`, `06`, `09`, `0F` | – | – | skaner znika z USB (prawdopodobnie interfejs nie-USB, np. RS232/TTL) | – | – | **nie** – tylko odłączenie zasilania |

Uwagi:

- Wszystkie tryby mają numer seryjny `APP-000000000`, `bcdUSB 1.10`, `bMaxPower 100 mA`.
- **TTDATA (`0C`) to tryb dla HAL:** brak klawiatury, pełne SSI, najszybszy odczyt (§6), powrót komendą, a usługa Sunmi go nie zna.
- **`324F:0112` (CDC)** jest wariantem, który oficjalna aplikacja Sunmi (`com.sunmi.scanner`) obsługuje sama: od razu przejmuje
  port i rozsyła kody broadcastem `com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED` (§8). Aplikacja musi odebrać jej interfejs
  (`claimInterface(force = true)`), co z nią konkuruje.
- Każda zmiana tożsamości to dla Androida nowe urządzenie: aplikacja potrzebuje **nowego uprawnienia USB** (§7).
- Inna, niezależna od `F2 01` tożsamość: po `8A=0C` (§5.3) skaner wraca jako `AF99:9000` „FT HID POS” (serial `A-EPM`).
  Jej interfejsów nie odczytano; nazwa sugeruje standardowy HID POS Barcode Scanner (usage page `0x8C`). Powrót tylko
  przez odłączenie zasilania.

## 2. Ramkowanie

### 2.1 Tryb `0E`: raporty HID na interfejsie 1

Każdy raport ma 64 B, w obu kierunkach (EP `0x02` OUT, `0x82` IN):

```
[0] = 0x01 (report ID)   [1] = długość fragmentu n ≤ 56   [2..2+n) = dane   reszta = 0   [63] = 0x01, gdy jest kolejny fragment
```

Wiadomość dłuższą niż 56 B dzieli się na kolejne raporty; ostatni ma `[63] = 0x00`. ✔ Odczytany kod przychodzi jako
jedna wiadomość z surowymi bajtami kodu zakończonymi `0D` (np. `01 12 41 51 52 … 0D` w raporcie). Na FLEX 3 między
poprawnymi raportami pojawiają się transfery 0-bajtowe; to nie są fragmenty, należy je pomijać ✔.

Wysyłanie: `bulkTransfer` na EP `0x02` działa (mimo typu INT) ✔. `SET_REPORT` (control) też przyjmowany.
`GET_REPORT` feature/input ID 1 zwraca 0 B.

### 2.2 TTDATA i CDC: gołe pakiety po bulk

Bez opakowania HID: pakiet SSI (§3) wysyła się wprost na BULK OUT ✔. Odpowiedzi i kody przychodzą na BULK IN jako
transfery USB; dłuższy transfer jest dzielony na pakiety 64 B, a koniec wyznacza krótki pakiet (np. `REPLY_REVISION`
o długości 98 B przyszedł jako 64 + 34) ✔. Najprościej czytać buforem ≥ kilka KiB: jedno wywołanie `bulkTransfer` =
jeden komunikat.

- Kod: surowe bajty zakończone `0D`, bez nagłówka (np. `41 51 52 7C … 0D` = `AQR|…\r`) ✔.
- Pakiet SSI rozpoznaje się po spójności długości i sumy kontrolnej (`[0] == rozmiar − 2`, poprawny checksum) ✔.

## 3. Pakiet SSI

```
[len][opcode][source][status][dane …][chkHi][chkLo]      len = 4 + liczba bajtów danych
chk = (−Σ bajtów od len do ostatniego bajtu danych) & 0xFFFF
```

- `source`: host wysyła `04`, skaner odpowiada `00` ✔.
- `status` w poleceniach: `00` = **tymczasowo** (znika po odłączeniu zasilania), `08` = **trwale** (zapis do pamięci skanera) ✔.
- Host nie musi potwierdzać pakietów skanera (nie było retransmisji) ✔. `9F=01` (software handshaking) – fabrycznie włączone.

Odpowiedzi skanera:

| odpowiedź | bajty | znaczenie |
|---|---|---|
| `CMD_ACK` | `04 D0 00 00 FF 2C` | przyjęto ✔ |
| `CMD_NAK` | `05 D1 00 00 <powód> chk` | odrzucono; powód: `01` błędna suma kontrolna, `02` nieoczekiwane / nieobsługiwane, `06` odmowa (np. `E4` poza trybem `8A=08`), `0A` niepożądane ✔ (`02`, `06`) |

## 4. Komendy SSI

Kolumna „raport HID” to gotowy raport dla trybu `0E` (dopełnić zerami do 64 B); w TTDATA/CDC wysyła się sam pakiet
(bajty od drugiego do końca, bez `01 <len>`).

| opcode | nazwa | dane | raport HID (`0E`) | odpowiedź / efekt |
|---|---|---|---|---|
| `A3` | REQUEST_REVISION | – | `01 06 04 A3 04 00 FF 55` | `A4` REPLY_REVISION: `Product Name:SS2222 Product ID:… Hardware version:V3.0.A Firmware version:2.4.94` ✔ |
| `C6` | PARAM_SEND | `FF` + pary `[parametr][wartość]`, można łączyć | np. `01 0A 08 C6 04 00 FF F2 02 00 FD 3B` | ACK ✔; NAK, gdy wartość spoza zakresu |
| `C7` | PARAM_REQUEST | lista numerów parametrów | np. `01 08 06 C7 04 00 F3 03 FE 39` | `C6` z danymi `FF [parametr][wartość]…` tylko dla obsługiwanych ✔; `C7 FE` („wszystkie”) zwraca pustą listę, bez danych – brak odpowiedzi |
| `C8` | PARAM_DEFAULTS | – | – | **nigdy nie używane** (reset fabryczny, nieodwracalne) |
| `E4` | START_DECODE | – | `01 06 04 E4 04 00 FF 14` | tylko w trybie `8A=08`: sesja dekodowania do `88`×100 ms; w innych trybach NAK `06` ✔ |
| `E5` | STOP_DECODE | – | `01 06 04 E5 04 00 FF 13` | jw.; przerywa sesję, ACK także bez sesji ✔ |
| `E6` | BEEP | `00` OK / `01` błąd | `01 07 05 E6 04 00 00 FF 11` | dźwięk skanera (ciszej niż po odczycie); `02`–`1F` → NAK `02` ✔ |
| `E7` | LED_ON | dowolny bajt (ignorowany) | `01 07 05 E7 04 00 01 FF 0F` | światło wł. **i `F2 02=01`** (host przejmuje światło) ✔ |
| `E8` | LED_OFF | dowolny bajt (ignorowany) | `01 07 05 E8 04 00 01 FF 0E` | światło wył., skanowanie działa dalej, **`F2 02=01`** ✔ |
| `E9` | SCAN_ENABLE | – | `01 06 04 E9 04 00 FF 0F` | ACK; skaner czyta zgodnie z trybem `8A` ✔ |
| `EA` | SCAN_DISABLE | – | `01 06 04 EA 04 00 FF 0E` | ACK; nie czyta, światło gaśnie, blokuje też `E4` ✔ |
| `C0`/`C1` | ILLUMINATION | – | – | brak odpowiedzi, bez efektu ✔ |
| `C4` | AIM_OFF | – | `01 06 04 C4 04 00 FF 34` | NAK `02` ✔ |

Komendy Newland (`~␁0000…;␃`, `@SCNMOD` itd.) i broadcast `Setting_cmd` aplikacji Sunmi **nie działają** na SS2222 ✔.

### 4.1 Czasy odpowiedzi

- ACK na `EA`/`E9`: 6–80 ms ✔. `REPLY_REVISION`: ok. 80 ms ✔.
- **`EA` wysłane ok. 5 ms po dekodowaniu kodu jest ignorowane** (brak ACK, brak jakichkolwiek raportów przez 2 s) ✔ (FLEX 3,
  pojedynczy odczyt). HAL czeka 300 ms po kodzie przed `EA`.
- `F2 01`: ACK, po nim skaner znika z USB w ciągu ok. 0,15–0,3 s ✔.

## 5. Parametry

### 5.1 Numeracja i dostęp

- Numer jednobajtowy `00`–`EF`; strony dwubajtowe: `F0 xx` = 256+xx, `F1 xx` = 512+xx, `F2 xx` = 768+xx (jak u Zebry).
  Odpowiadają też niestandardowe strony `F3`–`F7` i `F9`; `F8`, `FA`–`FF` nic nie zwracają ✔.
- Sweep z października: **355 parametrów** z wartościami; tabela wartości fabrycznych:
  [`sunmi-ss2222-factory-params.tsv`](sunmi-ss2222-factory-params.tsv) (kolumny: parametr, długość, wartość hex, ASCII).
  Kontrolny sweep z 20.11.2026 po wszystkich testach trybów był identyczny (różnił się tylko licznik czasu w `F4 66`) ✔.
- Wartości są jednobajtowe poza `F3 35`–`F3 38` (2 B) i tekstami strony `F4`.

### 5.2 Parametry o ustalonym znaczeniu

| parametr | fabr. | znaczenie |
|---|---|---|
| `03` / `08` / `F0 25` | `01` | EAN-13 / Code 128 / QR – **jedyne fabrycznie włączone** symbologie ✔ |
| `00` `01` `02` `04` `06` `07` `0A` `0E` `0F` `F0 24` `F0 52` | `00` | Code 39, UPC-A, UPC-E, EAN-8, I2of5, Codabar, Code 11, GS1-128, PDF417 (+MicroPDF417), Data Matrix (tylko kwadratowy), GS1 DataBar – czytają po włączeniu ✔ |
| `09`, `0B` | `00` | Code 93, MSI – **nie czytają nawet po włączeniu** ✔; Aztec nie ma parametru (`F1 3E`) |
| `12`–`1F` | różne | długości min/max symbologii (Zebra) ¹ |
| `2D` | `00` | Transmit Code ID: `01` = AIM (`]E0`, `]Q1`, `]C0`, `]d1`, `]L2` …) ✔ |
| `38` | `01` | beep po dobrym odczycie (`00` = cisza, światło dalej mruga, `E6` działa) ✔ |
| `69` | `00` | prefiks (jeden bajt ASCII) ✔ |
| `68` / `6A` | `0A` / `0D` | sufiks 1 (LF) / sufiks 2 (CR) ✔ |
| `EB` | `00` | format: `0` dane, `1` +S1, `2` +S2, `3` +S1+S2, `4` P+dane, `5` P+dane+S1, `6` P+dane+S2, `7` P+dane+S1+S2 ✔. **Pułapka:** fabrycznie czyta się `00`, a kod i tak kończy się CR; sam zapis `EB` wyłącza ten ukryty terminator – zawsze ustawiaj `EB` jawnie (np. `02`) |
| `88` | `1E` | czas sesji `E4` ×100 ms (3 s) ✔ |
| `89` | `0A` | odstęp ponownego odczytu tego samego kodu ×100 ms – **działa tylko w trybie `8A=04`** ✔ |
| `8A` | `11` | tryb wyzwalania (§5.3) ✔ |
| `8C` | `00` | głośność: `00` wysoka, `01` średnia, `02` niska, `03` cisza (także dla `E6`) ✔ |
| `9F` | `01` | software handshaking ACK/NAK ¹ |
| `F2 01` | `0E` | **tryb USB** (§1) ✔ |
| `F2 02` | `00` | światłem steruje firmware (mruga przy odczycie); `01` = host (ustawiają je `E7`/`E8`) ✔ |
| `F2 C9` | `8A` | lustro `F3 03` (tylko odczyt, zapis → NAK) ✔ |
| `F3 03` | `8A` | blokada ponownego odczytu **tego samego, trzymanego** kodu w trybie `11`, ×100 ms (13,8 s). Zapis tylko `00`–`63` (0–9,9 s); `00` ≈ 0,2 s bez blokady. **Wartości fabrycznej nie da się wpisać z powrotem** – wraca tylko po odłączeniu zasilania przy zmianie tymczasowej ✔ |
| `F4 01` | tekst | dane produktu (nazwa, ID, data produkcji, HW, FW, pola „Custom … NULL”) ✔ |
| `F4 02` | tekst | „F Ver:2.4.94, Ver Type:RELEASE, Build Name…” ✔ |
| `F4 0A` | tekst | „client null” ✔ |
| `F4 66` | tekst | status: „DB:[0][FA02], S:1544, Setcode Type:1, Setcode PWD:00, Auto Sen…, CMOS M:0, Mk:24000000, **SysTime:…ms**” (czas od włączenia) ✔ |
| `F4 88` | tekst | „Design in FT, OLI Lib Ver … 2020.9.200923, OLI Ver:2.0.1-E[1280]” ✔ |
| `F4 99` | tekst | „ubl:ver:3.3.9-[05], aep:ver:2.0.2-[05]” ✔ |

Nie istnieją (PARAM_REQUEST ich nie zwraca): Zebrowe oświetlenie i celownik (`F0 2A`, `F0 32`, `F1 9D`), ciągłe czytanie
`F1 89`, kategoria klawisza prefiksu (`62`–`64`), USB keystroke delay (`F0 1C`) ✔. Parametry `F2`/`F3` poza
opisanymi mają nieznane znaczenie; ślepe zapisy są ryzykowne (patrz `F2 01`).

### 5.3 Tryb wyzwalania `8A`

| `8A` | PARAM_SEND | zachowanie |
|---|---|---|
| `00`, `02`, `0A`, `0B` | ACK | nie czyta, światło zgaszone ✔ |
| `01`, `03`, `05`–`07` | NAK `02` | – |
| `04` | ACK | ciągłe czytanie; ten sam kod tylko raz z rzędu; światło co jakiś czas gaśnie na ~2 s ✔ |
| `08` | ACK | **host**: światło zgaszone, czyta tylko po `E4` (sesja `88`×100 ms, kończy się po odczycie lub `E5`); kod ok. 90 ms po `E4` ✔ |
| `09` | ACK | czujnik zbliżenia ✔ |
| **`0C`** | ACK | **⚠ skaner zmienia tożsamość na `AF99:9000` „FT HID POS”**; ratuje tylko odłączenie zasilania. Wartości ≥ `0C` (poza `11`) nie testować ✔ |
| **`11`** (fabr.) | ACK | światło stale, czyta samo; trzymany kod co `F3 03`; mruga przy odczycie ✔ |

## 6. Czasy odczytu (pomiary, FLEX 3)

| tryb | odstęp między **różnymi** kodami | uwagi |
|---|---|---|
| `0E`, kody ~10 znaków (licznik) | ok. 0,19 s | październik ✔ |
| `0E`, kody ~17–26 znaków | 283–390 ms (mediana) | z tego 212–323 ms to **wypisywanie kodu na klawiaturze IF0** (ok. 12 ms/znak); następny kod przychodzi ok. 66 ms po końcu wypisywania ✔ |
| `0E`, kod 180 znaków | szacunkowo ok. 2,2 s | z ok. 12 ms/znak |
| **TTDATA** (`0C`), kody ~17–26 znaków | **160 ms** (155–164, stabilnie) | brak klawiatury; komplet 15 kadrów animowanego QR (300 ms/kadr) w jednym cyklu ✔ |
| CDC (`08`) | ok. 202 ms (45–618) | aplikacja trzyma port; przez broadcasty Sunmi 296–893 ms ✔ |

- **Wąskim gardłem trybu `0E` jest wyjście klawiatury:** skaner nie dekoduje kolejnego kodu, póki nie skończy go „pisać”.
  Filtr klawiatury w Androidzie (usługa dostępności) połyka klawisze, ale nie skraca tego czasu ✔. Parametru wyłączającego
  samo wyjście klawiatury w trybie `0E` nie znaleziono; rozwiązaniem jest tryb bez klawiatury (TTDATA).
- W trybie `0E` kod przychodzi na IF1 ok. 13 ms przed rozpoczęciem wypisywania go na IF0 ✔.
- Ponowne podsunięcie tego samego kodu (zabranie i przyłożenie) czyta się od razu (0,5–1 s przy machaniu), niezależnie od `F3 03` ✔.

## 7. Android: uprawnienia i system Sunmi

- Każda tożsamość USB (`324F:0113`, `AF99:8001`, `324F:0112`, `324F:0111` …) i każde ponowne podłączenie wymagają uprawnienia
  USB dla aplikacji (okno systemowe „… ma mieć dostęp do …”) ✔. Okno z intencji `USB_DEVICE_ATTACHED` (filtr urządzeń w manifeście)
  ma pole „Zawsze otwieraj”, które zapamiętuje aplikację dla danego VID:PID i przy kolejnych podłączeniach nadaje uprawnienie samo.
- Odczyt `hidraw`/`/dev/bus/usb` z powłoki ADB blokuje SELinux; dostęp tylko przez USB Host API ✔.
- `com.sunmi.scanner` zna `324F:0113` jedynie jako klawiaturę (lista KBW) i nie wysyła mu komend; próbuje `query:scan00000105`
  i wpada w `StackOverflowError` (`sendQuery()` na urządzeniu bez wbudowanej głowicy) ✔. Wariant `324F:0112` obsługuje ramkowaniem SSI ✔.
- Tryb wyjścia klawiatury w systemie Sunmi (z dekompilacji, **niesprawdzone**): lista `sunmi_scanner_devices` w `Settings.Global`
  (`pid:vid:flagi`), zmieniana eksportowanym broadcastem `com.sunmi.scanner.ACTION_BAR_DEVICES_SETTING`
  (`name`, `pid`, `vid`, `type` 0–3, `isBroadcast`); kody przychodzą wtedy broadcastem `com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED`
  (`data`, `source_byte`). To przechwytywanie klawiszy po stronie Androida – nie skraca czasu wypisywania kodu przez skaner.

## 8. Bezpieczeństwo eksperymentów

- Wszystkie zmiany rób **tymczasowo** (status `00`); odłączenie zasilania przywraca stan fabryczny. Trwałe zapisy (`08`)
  tylko świadomie; jedyny dotąd: `F2 02=00` (wartość fabryczna).
- Wartości wymagające odłączenia zasilania: `F2 01` ∈ {`00`, `01`, `03`, `05`, `06`, `09`, `0F`}, `8A=0C`.
- Nigdy nie wysyłaj `C8` (PARAM_DEFAULTS) ani aktualizacji firmware.
- Po serii eksperymentów porównaj pełny sweep z [`sunmi-ss2222-factory-params.tsv`](sunmi-ss2222-factory-params.tsv).

## 9. Otwarte kwestie

- Interfejsy `AF99:9000` „FT HID POS” (po `8A=0C`) i czy da się z niego wrócić komendą.
- Znaczenie wartości „znikających” `F2 01` (RS232/TTL?) – wymaga innego okablowania.
- Różnice między `0A`, `0B`, `0C`, `0D` (wszystkie dają TTDATA) oraz między `02`, `04`, `07`, `08` (wszystkie CDC).
- Pozostałe parametry stron `F2`/`F3`/`F5`/`F6`, limity długości symbologii.
- Skąd okres bez odczytów zaraz po przełączeniu na TTDATA (raz zaobserwowany; po komendzie SSI odczyty ruszyły).
