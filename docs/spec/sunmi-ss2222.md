# Sunmi SS2222 USB

`sunmi.scanner.ss2222` obsługuje jeden skaner USB (`324F:0113`, w trybie danych `AF99:8001`) przez Android USB Host API
(minSdk 24). Moduł `plugins/sunmi/plugin-sunmi-scanner-ss2222-lib` dostarcza AAR;
HAL Sunmi i `plugin-sunmi-bundle` zawierają go w wariantach `stable` i `development`.

Tryby USB skanera, protokół SSI, parametry i pomiary czasów opisuje osobna specyfikacja referencyjna
[`sunmi-ss2222-protocol.md`](sunmi-ss2222-protocol.md).

Provider `barcodeScanner` ma priorytet 110. Gotowy SS2222 wyprzedza `sunmi.scanner.inner`
(priorytet 100), z zachowaniem ustawień kolejności i włączenia providerów HAL.
`isSupported()` oznacza obsługę USB Host przez Androida. Dostępność jest zgłaszana dopiero
po otwarciu i inicjalizacji urządzenia. Odłączenie usuwa providera z dostępnych backendów.
Więcej niż jeden pasujący skaner oznacza błąd `ambiguous_device`.

## Połączenie i sesje

Sterownik sprawdza interfejs HID 1 oraz endpointy interrupt `82`/`02`, oba o rozmiarze 64 B.
Przejmuje tylko IF1 z `force=true`. IF0 pozostaje klawiaturą Androida. Zgoda USB jest wiązana
z urządzeniem i konkretną próbą połączenia; odmowa nie udostępnia skanera.

Inicjalizacja wyłącza skanowanie (`EA`), odczytuje wersję (`A3` → `A4`) i zapewnia tymczasowo
tryb `8A=11` oraz światło sterowane przez firmware (`F202=00`). Zachowuje beep po odczycie.
Skany spoza sesji są odrzucane. `trigger` uruchamia pojedynczą sesję bez limitu oczekiwania;
pierwszy wynik zamyka przyjmowanie następnych i po 300 ms powoduje `EA` (skaner ignoruje `EA` wysłane
tuż po dekodowaniu: na FLEX 3 brak ACK po 5 ms unieważniał sesję). `startContinuous` działa do
`stop`. Kolejny start aktywnej sesji zwraca `busy`, a `stop` w bezczynności jest idempotentny.
Odpowiedź sterująca wraca po potwierdzeniu komendy, wynik skanowania przychodzi zdarzeniem.

Komendy SSI są serializowane. Termin odpowiedzi wynosi 2 s. Timeout unieważnia połączenie,
wysyła best-effort `EA` i wymaga fizycznego odłączenia oraz ponownego podłączenia skanera.
Spóźniony ACK nie jest odpowiedzią na następną komendę. Fragmentacja HID ma limit 16 KiB
oraz 1 s na wiadomość; niepełne i błędne wiadomości są odrzucane.
Puste transfery USB (0 B), obserwowane na Flex 3 pomiędzy poprawnymi raportami, nie są fragmentami
HID: są pomijane bez przerywania składania wiadomości.
Reconnect wraca do bezczynności; dalej decyduje ustawienie odczytu ciągłego (niżej).

## Tryb danych (ustawienie)

Fabrycznie skaner jest klawiaturą USB i każdy kod „pisze” w Androidzie, a kolejny kod czyta dopiero po
skończeniu pisania (ok. 12 ms na znak). Ustawienie pluginu **„Use data mode (no keyboard)”**
(`usbDataMode`, domyślnie **włączone**) przełącza skaner w tryb USB **TTDATA** (`F2 01=0C`, `AF99:8001`):
bez klawiatury, z SSI po bulk, z odczytem różnych kodów co ok. 160 ms zamiast 283–390 ms. Szczegóły
trybów: [`sunmi-ss2222-protocol.md`](sunmi-ss2222-protocol.md).

- Przy połączeniu w trybie klawiatury sterownik wyłącza skanowanie, sprawdza rewizję i wysyła `F2 01=0C`.
  Skaner znika z USB i wraca jako `AF99:8001`; w tym czasie stan to `CONNECTING`, nie `DISCONNECTED`.
  Inicjalizacja (parametry, `IDLE`) odbywa się już w trybie danych.
- Kontrolowane zamknięcie przywraca parametry, włącza skanowanie i **wysyła `F2 01=0E`**: skaner wraca
  w fabrycznym trybie klawiatury dla innych aplikacji. Po błędzie USB/SSI sterownik też wysyła `F2 01=0E`;
  ponowne wyliczenie urządzenia działa jak ponowne podłączenie, więc HAL sam łączy się znowu (bez odłączania kabla).
- Wyłączenie ustawienia przy otwartym skanerze przełącza go od razu na `0E`, włączenie – na `0C`.
- Jeśli HAL zostanie zabity bez zamknięcia, skaner zostaje w trybie danych (bez klawiatury) do ponownego
  startu HAL albo odłączenia zasilania.
- Każda tożsamość USB to dla Androida osobne urządzenie, więc HAL prosi o uprawnienie także dla `AF99:8001`.
  Biblioteka rejestruje `Ss2222UsbAttachActivity` (bez interfejsu) z filtrem `USB_DEVICE_ATTACHED` dla obu
  tożsamości: wybór „Zawsze otwieraj” w systemowym oknie sprawia, że kolejne podłączenia i przełączenia
  dostają uprawnienie automatycznie.
- `getStatus.usbMode`: `"data"`, `"keyboard"` albo `null` (brak otwartego skanera).
- W trybie danych filtr klawiatury nie ma czego filtrować; reguła `UsbDevice(324F:0113)` zostaje dla trybu klawiatury.

## Odczyt ciągły (ustawienie)

Bez HAL skaner czyta kody cały czas. Sterownik HAL przejmuje go, wyłącza (`EA`) i włącza tylko na
żądanie. Tym zachowaniem steruje ustawienie pluginu **„Disable the scanner between scans"**
(`disableWhenIdle`, domyślnie **włączone**, na ekranie szczegółów pluginu w HAL):

- **włączone** (domyślnie): skaner jest wyłączony i czyta tylko w `trigger` / `startContinuous`;
- **wyłączone**: skaner czyta tak jak bez HAL, a HAL zgłasza każdy kod zdarzeniem
  `barcode` / `barcodeScanner.onScan`, bez wywoływania `trigger`.

Przy wyłączonym ustawieniu plugin sam uruchamia sesję ciągłą (`E9`), ilekroć skaner jest bezczynny
(`IDLE`): zaraz po inicjalizacji, po podłączeniu, po zakończeniu skanu (`trigger`, `stop`, auto-stop po
pojedynczym odczycie) i po wyłączeniu ustawienia. Po błędzie USB/SSI (timeout, NAK) sterownik oddaje
skaner włączony (`E9`) zamiast wyłączonego (`EA`), więc nie zostaje martwy do ponownego podłączenia.
Kontrolowane zamknięcie przywraca parametry i włącza skaner niezależnie od ustawienia. Inicjalizacja
nadal na chwilę wysyła `EA`, bo zapis parametrów wymaga bezczynności.

- Operacje wymagające bezczynności (`trigger`, `startContinuous`, `setConfig`, `setParams`,
  `rawCommand` `C6`/`E9`) najpierw zatrzymują sesję tła, a po nich odczyt ciągły wraca sam.
  Po `trigger` wraca dopiero po pojedynczym odczycie (lub `stop`).
- `stop` przy wyłączonym ustawieniu kończy bieżący skan, po czym odczyt ciągły wraca. Żeby skaner
  przestał czytać, włącz ustawienie.
- Włączenie ustawienia zatrzymuje tylko sesję uruchomioną przez plugin; sesja klienta trwa dalej.
- Nieudany start jest ponawiany z rosnącym odstępem (1 s … 30 s), nie przy każdym `IDLE`.
- `getStatus.continuous` mówi, czy skaner czyta ciągle (ustawienie wyłączone).

## Metody

Natywne nazwy mają prefiks `sunmi.scanner.ss2222.`. Wszystkie parametry są obiektami JSON.

| Metoda | Parametry | Wynik |
|---|---|---|
| `trigger` | `{}` | `{"status":"scanning"}` po ACK, potem jeden wynik |
| `stop` | `{}` | `{}` po ACK lub od razu w bezczynności |
| `startContinuous` | `{}` | `{"status":"scanning"}` po ACK, kolejne wyniki |
| `getStatus` | `{}` | `state`, `available`, `keyboardFilterEnabled`, `continuous`, `usbMode`, `device` |
| `getDeviceInfo` | `{}` | `model`, `productId`, `hardwareVersion`, `firmwareVersion` |
| `getConfig` | `{}` | `symbologies`, `goodReadBeep`, `beepVolume` |
| `setConfig` | częściowa konfiguracja poniżej | `{}` po kontrolnym odczycie |
| `beep` | `{"kind":"ok"}` lub `{"kind":"error"}` | `{}` po ACK |
| `readParam` | `{"id":138}` | `{"id":138,"value":"11"}` (hex) |
| `setParams` | `{"parameters":[{"id":3,"value":1}]}` | `{}` |
| `rawCommand` | `{"opcode":163,"data":""}` | odpowiedź zależna od opcode |

`barcodeScanner.trigger/stop` są odpowiednikami natywnych metod. Diagnostyka
`readParam/setParams/rawCommand` istnieje tylko w `development`, wymaga włączonych metod
eksperymentalnych i uprawnienia `super`. Wywołanie AAR bezpośrednio omija autoryzację hosta;
aplikacja osadzająca bibliotekę odpowiada za kontrolę dostępu do jej API.

Przykład konfiguracji:

```json
{
  "symbologies": {"DATA_MATRIX": true, "EAN13": true},
  "goodReadBeep": false,
  "beepVolume": "medium"
}
```

| Klucz `symbologies` | Parametr SSI |
|---|---|
| `EAN13`, `EAN8` | `03`, `04` |
| `UPC_A`, `UPC_E` | `01`, `02` |
| `CODE128`, `GS1_128` | `08`, `0E` |
| `CODE39`, `ITF`, `CODABAR` | `00`, `06`, `07` |
| `QR_CODE`, `DATA_MATRIX` | `F025`, `F024` |
| `PDF417`, `CODE11`, `GS1_DATABAR` | `0F`, `0A`, `F052` |

Przełączniki przyjmują boolean. `DATA_MATRIX` dotyczy potwierdzonego kwadratowego Data Matrix.
Nie ma osobnego przełącznika MicroPDF417. `goodReadBeep` to `38` (0/1), a `beepVolume` to
`8C`: `high` (0), `medium` (1), `low` (2), `mute` (3). `mute` wycisza też metodę `beep`.
Nieznane ustawienia i wartości są odrzucane przed wysłaniem USB. Nieznane wartości odczytane
z urządzenia mają w `getConfig` wartość `null`.

Zapisy są dozwolone w bezczynności, wyłącznie ze statusem SSI `00`. Sterownik zapamiętuje
poprzednie wartości, weryfikuje zapis i przy częściowym błędzie próbuje go wycofać.
Kontrolowane `dispose()` przywraca ustawienia sprzed przejęcia urządzenia i włącza skanowanie
dla innych aplikacji. Sprzątanie ma ograniczenie czasu. Po zabiciu procesu lub utracie USB
przywrócenie nie jest gwarantowane: ustawienia tymczasowe mogą pozostać do odłączenia zasilania.

Diagnostyka czyta wyłącznie parametry z niepustą odpowiedzią w dostarczonym `sweep-all.tsv`.
`rawCommand` przyjmuje opcode jako liczbę i dane hex, bez nagłówka, statusu czy checksumy:

- `A3`: brak danych; zwraca `{"reply":"..."}` z pełną odpowiedzią SSI.
- `C7`: jeden identyfikator parametru, np. `"F2 02"`; wynik jak `readParam`.
- `C6`: `"FF <id> <value> ..."`; ten sam zakres zapisów co `setConfig`.
- `E6`: `"00"` lub `"01"`; jak `beep`.
- `E9`, `EA`: brak danych; jak `startContinuous` i `stop`.

Formatowanie, prefiksy/sufiksy, `EB`, `F303`, tryb USB i sterowanie światłem nie są zapisywalne
przez diagnostykę. Reset, zapis trwały i pozostałe komendy są odrzucane.

## Zdarzenia i TypeScript

Plugin emituje `sunmi.scanner.ss2222.barcode` oraz `barcodeScanner.onScan` z tym samym wynikiem:

```json
{"data":"123","format":"UNKNOWN","rawData":"MTIzDQ=="}
```

`rawData` zawiera dokładne bajty w Base64, łącznie z terminatorem. `data` dekoduje UTF-8
z pominięciem jednego końcowego CR; pozostałe białe znaki pozostają. Symbologia nie jest
odgadywana z tekstu. Składanie animowanych QR należy do klienta.

`sunmi.scanner.ss2222.stateChanged` zawiera `{"state":"IDLE"}`. Stany:
`DISCONNECTED`, `WAITING_FOR_PERMISSION`, `CONNECTING`, `IDLE`, `STARTING`, `SCANNING`,
`STOPPING`, `ERROR`. `sunmi.scanner.ss2222.error` zawiera `code` i `message`.

```ts
const scanner = await SunmiBarcodeScannerClient.forBackend(client, 'sunmi.scanner.ss2222');
const offScan = await scanner.onScan(scan => console.log(scan.data, scan.rawData));
const offState = await client.on('sunmi.scanner.ss2222.stateChanged@sunmi.scanner.ss2222',
  (_event, state) => console.log(state));
await scanner.trigger();
// Wynik przychodzi w onScan. Anulowanie sesji: await scanner.stop().
// Po zakończeniu pracy: await offScan(); await offState().
```

Przy włączonym ustawieniu odczytu ciągłego skaner czyta w tle sam, więc wystarczy `onScan`.
Bez niego po zdarzeniu `IDLE` można rozpocząć skanowanie ciągłe przez
`client.execute('sunmi.scanner.ss2222.startContinuous', {})` i zakończyć je przez `scanner.stop()`.
Metody natywne są też w istniejącym panelu metod HAL. Przy niedostępnym urządzeniu nowy HAL
ukrywa capability, więc jego metody nie są routowane. W aplikacji osadzającej AAR `getStatus`
można odczytać również przed połączeniem.

## Filtr klawiatury Androida

Plugin nie ma własnej usługi dostępności. Zgłasza HAL-owi regułę
`KeyboardFilterRule.UsbDevice(0x324F, 0x0113)` przez `getKeyboardFilterRules()`, a filtruje ją
wspólny filtr klawiatury HAL (`HalKeyboardFilterService`, zob. [`keyboard-filter.md`](keyboard-filter.md)).
Użytkownik włącza w ustawieniach dostępności jedną usługę „HAL keyboard filter"; panel pluginu
pokazuje regułę, stan filtra i przycisk przejścia do tych ustawień.
Filtr konsumuje DOWN/UP tylko z klawiatury o VID/PID SS2222, przez cały czas włączenia, również po
zakończeniu skanu. Inne klawiatury działają normalnie, a skanowanie przez USB działa także przy
wyłączonym filtrze.

`keyboardFilterEnabled` (`KeyboardFilter.isEnabled`) sprawdza globalne włączenie dostępności
i komponent filtra HAL w ustawieniach Androida. Nie dowodzi otrzymywania zdarzeń przez usługę:
Android/OEM lub inny filtr dostępności może wpływać na ich dostarczanie. Trzeba to sprawdzić na terminalu.

## AIDL i wdrożenie

`IPluginContext.setPluginAvailable(boolean)` dopisano na końcu kontraktu, zachowując numery
wcześniejszych transakcji. Nowy bundle ze starym hostem toleruje nieobsługiwaną transakcję:
komendy nadal sprawdzają sprzęt, ale stary host nie ukrywa dynamicznie odłączonego providera.
Zdarzenia i dostępność w nowym hostcie są powiązane z instancją i generacją inicjalizacji.
Wyparcie wersji wbudowanej przez bundle nie daje starym callbackom kontroli nad następcą.

## Odbiór na sprzęcie

Przy statusie `UNAVAILABLE` logcat z tagiem `SS2222` pokazuje przejścia stanów oraz kod i opis
błędu inicjalizacji, bez treści skanowanych kodów. `permission_denied` oznacza brak zgody Androida
na USB; ponowne uruchomienie usługi HAL lub odłączenie i podłączenie skanera ponawia żądanie zgody.
Wariant `development` dodatkowo loguje czas wysłania i zakończenia komend, metadane odpowiedzi SSI
oraz powód ich przyjęcia lub odrzucenia. Przy rozpoczęciu komendy i jej timeoutcie zapisuje stan
wątku USB, liczniki odczytów i wiek ostatniego raportu; porównanie pozwala odróżnić brak odbioru od
odrzucenia odpowiedzi. Logi nie zawierają treści kodów ani wartości parametrów.
`command_timeout` unieważnia połączenie i wymaga odłączenia oraz ponownego podłączenia skanera,
nawet jeśli urządzenie nadal figuruje na liście USB Androida.

Testy jednostkowe nie zastępują odbioru na Flex 3 z SS2222. Przeprowadzić osobno dla wersji
wbudowanej i bundle, w obu wariantach stabilności:

- Zgoda i odmowa USB; jeden i dwa skanery; odłączenie podczas komendy; reconnect do `IDLE`.
- Pojedynczy skan bez limitu, anulowanie, ciągły odczyt, długi QR i dokładne `rawData`.
- Konfiguracja, kontrolny odczyt, przywrócenie po zamknięciu i ponowna inicjalizacja pluginu.
- Chrome: pole tekstowe oraz przycisk z fokusem, opóźniony Enter po zakończeniu sesji.
- Natywne dialogi, zwykła klawiatura, filtr włączony i wyłączony, ewentualny drugi filtr dostępności.
- Domyślny i przypięty provider, wyparcie przez bundle i powrót do wersji wbudowanej.
- Diagnostyka z/bez `super`, z wyłączonymi metodami eksperymentalnymi oraz brak diagnostyki w `stable`.

Źródła protokołu: przekazane `HANDOFF.md`, `scanner-testapp/NOTES.md`, `sweep-all.tsv`
i logi sesji z archiwum `ss2222-handoff.zip` (rozpoznanie 2026-10-21).
