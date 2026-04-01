# Protokół Intent

Jednorazowe komendy przez Android Intent. Idealne dla Tasker, Shortcuts,
i apek które nie chcą utrzymywać połączenia.

## Actions

### dev.duma.hal.REQUEST_TOKEN

Żądanie tokenu (pierwszy krok autoryzacji).

**Extras wejściowe:**
- `developerKey` (String, opcjonalnie) — JWT developer key
- `clientId` (String) — identyfikator klienta

**Result extras:**
- `result` (String) — JSON response:
  - Sukces: `{"token":"abc...","permissions":["printer","scanner"]}`
  - Błąd: `{"error":"invalid_key","message":"..."}`

**Result codes:**
- RESULT_OK — sukces
- RESULT_CANCELED — błąd (szczegóły w extra "result")

### dev.duma.hal.EXECUTE

Wykonanie komendy (wymaga tokenu).

**Extras wejściowe:**
- `token` (String) — token z requestToken
- `method` (String) — np. "printer.print"
- `params` (String) — JSON params

**Result extras:**
- `result` (String) — JSON response

**Result codes:**
- RESULT_OK — sukces
- RESULT_CANCELED — błąd

## Implementacja

IntentGatewayActivity — przeźroczysta Activity w module transport-intent.
Zadeklarowana w AndroidManifest hal-service (transport-intent dostarcza klasę):

```xml
<activity android:name="[klasa z transport-intent]"
    android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="dev.duma.hal.REQUEST_TOKEN" />
        <action android:name="dev.duma.hal.EXECUTE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

## CallerContext

callingUid z Binder (Activity ma dostęp) → packageName, certHash.
Token binding weryfikowane identycznie jak AIDL.

## Runtime toggle

isToggleable = true — można wyłączyć w Dashboard.
Gdy wyłączony: Activity natychmiast zwraca błąd.

## Przykład użycia (klient)

```kotlin
val intent = Intent("dev.duma.hal.EXECUTE")
    .putExtra("token", "abc123...")
    .putExtra("method", "printer.print")
    .putExtra("params", """{"template":"receipt"}""")
startActivityForResult(intent, REQUEST_CODE)

// onActivityResult:
val result = data?.getStringExtra("result")
```
