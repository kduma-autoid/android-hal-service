# Sunmi SS2222 USB

`sunmi.scanner.ss2222` obsługuje jeden skaner USB `324F:0113` przez Android USB Host API
(minSdk 24). Moduł `plugins/sunmi/plugin-sunmi-scanner-ss2222-lib` dostarcza AAR;
HAL Sunmi i `plugin-sunmi-bundle` zawierają go w wariantach `stable` i `development`.

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
pierwszy wynik zamyka przyjmowanie następnych i powoduje `EA`. `startContinuous` działa do
`stop`. Kolejny start aktywnej sesji zwraca `busy`, a `stop` w bezczynności jest idempotentny.
Odpowiedź sterująca wraca po potwierdzeniu komendy, wynik skanowania przychodzi zdarzeniem.

Komendy SSI są serializowane. Termin odpowiedzi wynosi 2 s. Timeout unieważnia połączenie,
wysyła best-effort `EA` i wymaga fizycznego odłączenia oraz ponownego podłączenia skanera.
Spóźniony ACK nie jest odpowiedzią na następną komendę. Fragmentacja HID ma limit 16 KiB
oraz 1 s na wiadomość; niepełne i błędne wiadomości są odrzucane.
Puste transfery USB (0 B), obserwowane na Flex 3 pomiędzy poprawnymi raportami, nie są fragmentami
HID: są pomijane bez przerywania składania wiadomości.
Reconnect wraca do bezczynności, bez automatycznego wznowienia skanowania.

## Metody

Natywne nazwy mają prefiks `sunmi.scanner.ss2222.`. Wszystkie parametry są obiektami JSON.

| Metoda | Parametry | Wynik |
|---|---|---|
| `trigger` | `{}` | `{"status":"scanning"}` po ACK, potem jeden wynik |
| `stop` | `{}` | `{}` po ACK lub od razu w bezczynności |
| `startContinuous` | `{}` | `{"status":"scanning"}` po ACK, kolejne wyniki |
| `getStatus` | `{}` | `state`, `available`, `keyboardFilterEnabled`, `device` |
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

Po zdarzeniu `IDLE` można rozpocząć skanowanie ciągłe przez
`client.execute('sunmi.scanner.ss2222.startContinuous', {})` i zakończyć je przez `scanner.stop()`.
Metody natywne są też w istniejącym panelu metod HAL. Przy niedostępnym urządzeniu nowy HAL
ukrywa capability, więc jego metody nie są routowane. W aplikacji osadzającej AAR `getStatus`
można odczytać również przed połączeniem.

## Filtr klawiatury Androida

Biblioteka zawiera `Ss2222KeyboardFilterService`, dostępny również w HAL i bundle.
Panel pluginu ma przycisk przejścia do ustawień dostępności. Użytkownik włącza usługę samodzielnie.
Usługa konsumuje DOWN/UP tylko dla VID/PID SS2222 przez cały czas włączenia, również po
zakończeniu skanu. Inne klawiatury działają normalnie. Usługa nie czyta treści okien.
Skanowanie przez USB działa także przy wyłączonym filtrze.

`keyboardFilterEnabled` sprawdza globalne włączenie dostępności i konkretny komponent usługi
w ustawieniach Androida. Nie dowodzi otrzymywania zdarzeń przez usługę: Android/OEM lub inny
filtr dostępności może wpływać na ich dostarczanie. Trzeba to sprawdzić na terminalu.

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
