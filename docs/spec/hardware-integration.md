# Notatki integracji sprzętowej

**Status: PRZYSZŁA IMPLEMENTACJA — referencja dla prawdziwych pluginów vendorowych.**

Zebrane z analizy dokumentacji Sunmi SDK i specyfiki Androida.
Do użycia gdy stuby zostaną zastąpione prawdziwymi implementacjami.

## Barcode Scanner (Sunmi)

### Trzy mechanizmy odbioru skanów

**1. Broadcast (rekomendowany dla Service):**
- Action: `com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED`
- Extras: `data` (String — zdekodowany kod), `source_byte` (byte[] — surowe bajty)
- Działa bezproblemowo w Service, bez foreground Activity
- Nie daje pełnej kontroli nad skanerem (konfiguracja, trigger programowy)

**2. AIDL IScanInterface (pełna kontrola):**
- Bind: `Intent("com.sunmi.scanner.IScanInterface").setPackage("com.sunmi.scanner")`
- Metody: `scan()`, `stop()`, `getScannerModel()`, `sendKeyEvent(KeyEvent)`
- `sendKeyEvent(ACTION_UP)` startuje skan, `sendKeyEvent(ACTION_DOWN)` stopuje
- Daje programowy trigger i konfigurację symbologii

**3. KeyEvent (symulacja klawiatury):**
- Skaner "wpisuje" znaki jak klawiatura, kończy Enter
- Wymaga Activity z focusem — NIE działa w Service
- Obejście: AccessibilityService z `flagRequestFilterKeyEvents` buforuje znaki
- Najgorszy tryb: brak symbologii, brak konfiguracji, wymaga obejścia
- Użyj tylko jako fallback kompatybilności

### Deduplikacja

Broadcast i AIDL callback mogą strzelić jednocześnie (ten sam skan).
Deduplikacja: LinkedHashMap z kluczem `"$data:$symbology"`, okno 300ms.

```kotlin
private fun isDuplicate(scan: BarcodeScan): Boolean {
    val now = System.currentTimeMillis()
    recentScans.entries.removeAll { now - it.value > 300 }
    val key = "${scan.data}:${scan.symbology}"
    return if (key in recentScans) true
    else { recentScans[key] = now; false }
}
```

## NFC

### Problem: NFC wymaga foreground Activity

Android NFC dispatch kieruje tagi do Activity na pierwszym planie.
Service NIE może bezpośrednio odbierać tagów NFC.

### Rozwiązanie: NfcProxyActivity

Przeźroczysta Activity (`Theme.Translucent.NoTitleBar`) z `enableReaderMode()`:

```kotlin
class NfcProxyActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag -> forwardToService(tag) },
            NfcAdapter.FLAG_READER_NFC_A or FLAG_READER_NFC_B or FLAG_READER_NFC_V or FLAG_READER_NFC_F,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun forwardToService(tag: Tag) {
        Intent(this, HalService::class.java).apply {
            action = "ACTION_NFC_TAG"
            putExtra("tag", tag)
        }.also { startService(it) }
        finish()  // Użytkownik nic nie widzi
    }
}
```

### Manifest NfcProxyActivity

```xml
<activity android:name=".NfcProxyActivity"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:launchMode="singleTask" android:exported="true">
    <intent-filter>
        <action android:name="android.nfc.action.NDEF_DISCOVERED" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="*/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.nfc.action.TAG_DISCOVERED" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

### Sunmi NFC SDK

Niektóre modele Sunmi (np. P2 Pro) mają dedykowane NFC SDK, które może
działać bezpośrednio z Service. Warto sprawdzić dostępność per model
i użyć standardowego Android NFC jako fallback.

## RFID (Sunmi UHF)

### SDK

Sunmi UHF SDK — model-specific. Bind bezpośrednio z Service (nie wymaga Activity).
Modele z UHF: L2H, L2K (z modułem), dedykowane handheld readers.

### Fizyczny przycisk → RFID

**Kluczowe ustalenie:** Sunmi NIE broadcastuje wciśnięcia przycisku.
`com.sunmi.scanner.ACTION_KEY_EVENT` i `com.sunmi.hardware.ACTION_KEY_EVENT`
NIE ISTNIEJĄ — te nazwy były sfabrykowane.

Boczny przycisk jest na sztywno podpięty do systemowego serwisu skanera.
Nie ma publicznego API "użytkownik wcisnął przycisk".

### Opcje przechwytywania przycisku

**1. AccessibilityService (rekomendowane, uniwersalne):**
- `flagRequestFilterKeyEvents` w accessibility config
- Przechwytuje surowy KeyEvent ZANIM trafi do skanera systemowego
- Wymaga jednorazowego włączenia w Settings → Accessibility
- Działa na wszystkich modelach Sunmi

```xml
<!-- res/xml/accessibility_config.xml -->
<accessibility-service
    android:accessibilityEventTypes="typeAllMask"
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:description="@string/accessibility_description" />
```

```kotlin
class HardwareKeyAccessibilityService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in TRACKED_KEYS) return false
        // Forward do HalService przez Intent
        return true  // Konsumuj event
    }
}
```

**2. dispatchKeyEvent w Activity:**
- Wymaga foreground Activity z focusem
- Wymaga wyłączenia domyślnego bindowania przycisku w Sunmi Scanner Settings
- Mniej praktyczne dla background service

**3. Programowy trigger z API:**
- Zamiast fizycznego przycisku: `POST /rfid/start` z klienta
- Najprostsze, ale mniej ergonomiczne w magazynie

### ButtonToRfidBridge

Łączy zdarzenia przycisków z kontrolą RFID. Trzy tryby:

```kotlin
enum class ScanTriggerMode {
    HOLD_TO_SCAN,     // Trzymaj = skanuj, puść = stop (magazyn)
    PRESS_OR_HOLD,    // Krótkie = single, długie = continuous
    TOGGLE            // Wciśnij = start, wciśnij ponownie = stop (inwentaryzacja)
}

class ButtonToRfidBridge(buttonManager, rfidManager, scope) {
    var scanMode = ScanTriggerMode.HOLD_TO_SCAN
    var holdThresholdMs = 500L

    // onPressed:
    //   HOLD_TO_SCAN → startInventory()
    //   PRESS_OR_HOLD → schedule holdJob (delay holdThresholdMs → startContinuous)
    //   TOGGLE → if scanning stop else start

    // onReleased:
    //   HOLD_TO_SCAN → stopInventory()
    //   PRESS_OR_HOLD → cancel holdJob, if short press → singleInventory()
    //   TOGGLE → nop
}
```

## Mixed content / HTTPS vs localhost

### Chrome Local Network Access (od Chrome 142, październik 2025)

HTTPS strona łącząca się z ws://localhost → prompt LNA (jednorazowy per device).
Po kliknięciu "Allow" Chrome zapamiętuje. Od Chrome 147 dotyczy też WebSocket.

Scenariusze:
- Web app hostowana z localhost → zero promptów (loopback → loopback)
- Capacitor/RN WebView → konfigurowalne (`allowMixedContent`)
- HTTPS strona w Chrome → jednorazowy prompt "Allow"
- MDM managed → `LocalNetworkAccessAllowedForUrls` policy

TLS na localhost nie jest wymagany dla prototypu. Warto uwzględnić w architekturze
Ktor na przyszłość (dodanie SSL connector to zmiana kilku linii).
