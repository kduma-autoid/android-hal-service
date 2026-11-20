# Sunmi NFC: moduły i położenie anten

Plugin `sunmi.nfc`, wersja deskryptora `2`, udostępnia odczyt
`sunmi.nfc.getAntennaLocations` (parametry `{}`) oraz zdarzenie
`sunmi.nfc.antennaLocationsChanged`. Oba zwracają ten sam pełny stan.
Capability pozostaje `sunmi.nfc`; transporty HAL i AIDL nie wymagają zmian.

## Kontrakt

Przykładowa odpowiedź na FLEX 3 z wybranym czytnikiem wbudowanym:

```json
{
  "state": "ready",
  "controller": "builtin",
  "modules": [
    {
      "sn": "HOST-SERIAL",
      "sdkType": 0,
      "selected": true,
      "reader": "builtin",
      "location": {
        "displayId": 0,
        "displayWidthPx": 1920,
        "displayHeightPx": 1080,
        "rotationDegrees": 0,
        "rect": { "x": 1200, "y": 470, "width": 200, "height": 200 },
        "estimated": false
      },
      "locationUnavailableReason": null
    }
  ]
}
```

`state` opisuje uzyskanie listy modułów:

| Wartość | Znaczenie |
|---|---|
| `initializing` | SDK nie przekazało jeszcze pierwszej listy. `modules: []` nie oznacza wtedy braku czytników. |
| `ready` | Otrzymano listę; może być pusta. Poszczególne położenia nadal mogą być nieznane. |
| `unavailable` | Inicjalizacja nie powiodła się lub plugin nie ma działającego kontekstu. |

Lista zawiera wszystkie moduły zgłoszone przez SDK, również nieaktywne.
`selected` pochodzi z SDK, a `controller` z ustawień Sunmi (`builtin`, `external`
lub `unknown`). Podczas przełączania te dwa źródła mogą chwilowo się różnić.
Wywołanie `switchModule` nie ustawia optymistycznie `selected`.

`sdkType` jest surową wartością SDK; przykład `0` nie definiuje jej znaczenia.
`reader` przyjmuje `builtin`, `external` lub `unknown`. Rozpoznanie opiera się
na dokładnym dopasowaniu SN hosta (TMS lub Android) i SN ekranu klienta.
`ANDROID_ID`, pozycja na liście i numer `sdkType` nie identyfikują anteny hosta.
Nieznany lub sprzeczny SN daje `reader: "unknown"`.
Jeżeli TMS nie jest jeszcze gotowy, odczyt numeru hosta jest ponawiany w tle
z rosnącym odstępem. Późniejsze rozpoznanie modułu powoduje nowe zdarzenie.

`location` jest `null`, gdy położenia nie można ustalić. Pole
`locationUnavailableReason` przyjmuje wtedy jedną z wartości:

| Powód | Znaczenie |
|---|---|
| `external_location_unknown` | Położenie czytnika zewnętrznego nie jest dostępne. |
| `reader_identity_unknown` | Nie ustalono jednoznacznie, do jakiego urządzenia należy moduł. |
| `missing_location` | Brak wpisu położenia lub pusta tablica. Nie oznacza braku NFC. |
| `invalid_location` | Niepoprawny JSON, liczby, wymiary lub granice prostokąta. |
| `settings_unreadable` | System odmówił odczytu ustawienia. |
| `display_unavailable` | Nie można odczytać głównego wyświetlacza. |
| `unsupported_display_metrics` | Rozmiar ekranu nie odpowiada panelowi lub parametry są nieobsługiwane. |

Brak dostępnej pozycji jednego modułu nie usuwa pozostałych modułów.
Położenie rozpoznanego czytnika wbudowanego pozostaje dostępne także wtedy,
gdy wybrany jest czytnik zewnętrzny. Dla poprawnej pozycji powód ma wartość `null`.

## Współrzędne i źródła danych

Położenie pochodzi z nieudokumentowanego klucza Sunmi
`Settings.Global.device_mgr_nfc_watermark_location`. Wartości jego prostokąta
mogą być stringami lub liczbami całkowitymi. Plugin korzysta z prostokąta
wybranego przez system; nie agreguje dodatkowego klucza `left_and_right`.

Algorytm sprawdza, czy prostokąt mieści się w naturalnych wymiarach panelu,
czy w wymiarach zamienionych miejscami. W drugim przypadku normalizuje
ROTATION_90 do układu naturalnego, a następnie przelicza wynik dla bieżącego
obrotu. Gdy obie interpretacje są możliwe, wybiera układ naturalny i ustawia
`estimated: true`. Niepoprawnych prostokątów nie przycina do ekranu.

`displayWidthPx`, `displayHeightPx` i `rect` dotyczą pełnego wyświetlacza
w jego bieżącym obrocie. `rotationDegrees` to `0`, `90`, `180` lub `270`
względem orientacji naturalnej Androida. Wartości są w pikselach, nie w dp
ani pikselach CSS. Niestandardowe skalowanie rozdzielczości nie jest obsługiwane.

Przed narysowaniem w natywnym widoku trzeba odjąć jego położenie na ekranie;
nie odejmuje się insetów ponownie:

```kotlin
val origin = IntArray(2)
view.getLocationOnScreen(origin)
val left = rect.x - origin[0]
val top = rect.y - origin[1]
canvas.drawRect(
    left.toFloat(), top.toFloat(),
    (left + rect.width).toFloat(), (top + rect.height).toFloat(), paint
)
```

Widok musi należeć do wyświetlacza wskazanego przez `displayId`.
Podgląd WWW jest schematem: pozycję i rozmiar markera liczy jako proporcje
do wymiarów ekranu z HAL, bez użycia `window.devicePixelRatio`.
Nie służy do potwierdzania wyrównania fizycznego z przeglądarki na innym komputerze.

Plugin obserwuje położenie, `device_mgr_nfc_cur_controller`,
`sub_display_device_info`, callbacki listy SDK i zmiany głównego wyświetlacza.
Nowe zdarzenie przenosi pełny stan i jest deduplikowane. Obserwatory i listenery
są zwalniane w `dispose()`, także w obu pakietach APK udostępniających NFC.
Odczyt nie zapisuje ustawień, nie przełącza kontrolera i nie ukrywa systemowego znacznika.

## TypeScript i zgodność

```typescript
import { SunmiNfcClient } from '@kduma-autoid/hal-client-plugin-sunmi-nfc';

const nfc = new SunmiNfcClient(halClient);
let updates = 0;
const unsubscribe = await nfc.onAntennaLocationsChanged(snapshot => {
  updates++;
  render(snapshot);
});
const beforeRead = updates;
const initial = await nfc.getAntennaLocations();
if (updates === beforeRead) render(initial);
// Na opuszczenie widoku lub wymianę klienta:
await unsubscribe();
```

Wyeksportowany typ wyniku to `NfcAntennaLocationsSnapshot`.
W aplikacji należy dodatkowo ignorować odpowiedzi poprzedniego połączenia.
Najpierw sprawdzić metodę w `system.describe`, potem subskrybować i odczytać stan.
Starszy HAL nie implementuje nowej metody; nie jest to ten sam przypadek co
obsługiwany odczyt zwracający `location: null`.

Istniejące `switchModule`, `setWatermarkAlpha` i zdarzenie `modulesChanged`
zachowują dotychczasowy kontrakt, w tym `{"modules":[{"sn":"..."}]}`.
Demo zachowuje stare kontrolki dla starszego HAL. Po utracie WebSocket oznacza
dane jako nieaktualne i udostępnia pełne ponowne połączenie; sam reconnect
socketu nie gwarantuje odtworzenia uwierzytelnienia i subskrypcji.

## Weryfikacja na urządzeniach

Handoff z 2026-10-21 potwierdza dane przez ADB oraz transformacje CPAD.
Nie stanowi potwierdzenia odczytu z tego APK. Przed wydaniem trzeba wykonać
poniższy test na FLEX 3 (Android 13) i OrderPAD 3 / CPAD (Android 14).

1. Zbudować `:service:hal-service:assembleSunmiDevelopmentDebug` i zainstalować
   APK o `targetSdk 36` jako zwykłą aplikację. Uruchomić HAL z dashboardu.
2. Otworzyć debugowy widok (nie jest obecny w APK release):

   ```bash
   adb shell am start -n dev.duma.android.hal.service/.service.NfcAntennaDiagnosticActivity
   ```

3. Sprawdzić odczyty trzech ustawień widoczne w panelu, przejście
   `initializing` → `ready`, listę SDK, rozpoznany SN hosta i czytnika zewnętrznego.
   Brak pierwszego callbacku pozostaje stanem oczekiwania, a nie pustą listą.
4. Dla czytnika wbudowanego porównać obrys ze znacznikiem Sunmi we wszystkich
   rotacjach. Przycisk ukrycia szczegółów odsłania obrys. Kolor cyjan oznacza
   rozstrzygnięty układ, przerywany bursztynowy — szacowany.
5. W demo WWW sprawdzić zdarzenia po obrocie, zmianie czytnika w ustawieniach
   i odłączeniu ekranu klienta; ponowne połączenie i opuszczenie widoku nie mogą
   mnożyć subskrypcji. Debugowy widok odpytuje odczyt co 500 ms, więc sam nie
   potwierdza poprawności dostarczania zdarzeń.
6. Sprawdzić, że po wybraniu NPF10 jego położenie jest nieznane, a geometria
   prawidłowo rozpoznanego hosta pozostaje dostępna. Przywrócić wcześniejszy
   wybór czytnika, przezroczystość znacznika i obrót.

Oczekiwane prostokąty, przy fabrycznej rozdzielczości:

| Obrót | FLEX 3, panel 1920×1080 | CPAD, panel naturalny 1200×1920 |
|---|---|---|
| 0° | `(1200,470,200,200)` | `(740,1460,460,460)` |
| 90° | `(470,520,200,200)` | `(1460,0,460,460)` |
| 180° | `(520,410,200,200)` | `(0,0,460,460)` |
| 270° | `(410,1200,200,200)` | `(0,740,460,460)` |

Zapisać model, wersję OS, wynik odczytu aplikacji, otrzymanie pierwszego
callbacku i zgodność każdej rotacji. Nie zmieniać kontrolera przez
`settings put`: sam wpis nie przełącza sprzętu. Pozycje ekranów zewnętrznych,
kalibracja i protokół USB firmware pozostają poza tym kontraktem.
