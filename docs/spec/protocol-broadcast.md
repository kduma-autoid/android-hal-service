# Protokół Broadcast

Publiczny push eventów do apek które nie utrzymują aktywnego połączenia.
Bez autoryzacji — każda apka z BroadcastReceiver dostaje eventy.

## Format

Action: `dev.duma.hal.event.{eventName}`

Przykłady:
- `dev.duma.hal.event.scanner.barcode`
- `dev.duma.hal.event.rfid.tag`
- `dev.duma.hal.event.printer.stateChanged`

**Extras:**
- `event` (String) — nazwa eventu, np. "scanner.barcode"
- `data` (String) — JSON dane eventu

## Konfiguracja per-event

Nie wszystkie eventy są broadcastowane — użytkownik wybiera w Dashboard.

Dashboard pokazuje listę eventów z PluginDescriptor wszystkich pluginów.
Checkbox per event:
- ☑ scanner.barcode — "Barcode scanned (generic)"
- ☑ sunmi.scanner.barcode — "Barcode scanned (Sunmi)"
- ☐ rfid.tag — "RFID tag detected"

Konfiguracja w SharedPreferences: `Set<String> enabledBroadcastEvents`.
Przekazywana do BroadcastTransport przez TransportConfig.

## pushEvent flow

```kotlin
fun pushEvent(eventName: String, jsonData: String) {
    if (!isEnabled) return                          // globalny toggle
    if (eventName !in enabledBroadcastEvents) return // per-event config

    val intent = Intent("dev.duma.hal.event.$eventName")
        .putExtra("event", eventName)
        .putExtra("data", jsonData)
    context.sendBroadcast(intent)
}
```

## Runtime toggle

isToggleable = true — globalny on/off w Dashboard.
Niezależny od per-event config (oba muszą przepuścić).

## Klient — przykład receivera

```xml
<!-- AndroidManifest klienta -->
<receiver android:name=".ScanReceiver" android:exported="true">
    <intent-filter>
        <action android:name="dev.duma.hal.event.scanner.barcode" />
    </intent-filter>
</receiver>
```

```kotlin
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventName = intent.getStringExtra("event")
        val data = intent.getStringExtra("data")
    }
}
```

## Opcjonalne: chroniony broadcast

Domyślnie: otwarty broadcast (sendBroadcast bez permission).
Opcjonalnie (do konfiguracji w przyszłości):
```kotlin
sendBroadcast(intent, "dev.duma.hal.RECEIVE_EVENTS")
```
Na start: bez ograniczeń.
