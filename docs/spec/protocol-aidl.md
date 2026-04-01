# Protokół AIDL (kliencki)

Interfejs AIDL wystawiony przez HAL Service dla natywnych klientów Android.
Umieszczony w module transport-aidl (nie w hal-contract — kontrakt jest dla pluginów).

## IHalService.aidl

```aidl
interface IHalService {
    // Autoryzacja
    String requestToken(String jsonRequest);
    // jsonRequest: {"serviceKey":"...","clientId":"..."}
    // → {"token":"...","permissions":[...],...}
    // → {"error":"...","message":"..."}

    boolean authenticate(String token);

    // Komendy (wymagają wcześniejszego authenticate)
    String execute(String method, String jsonParams);
    // → JSON result lub JSON error

    // Status
    String getStatus();

    // Eventy — klient musi zasubskrybować, żeby otrzymywać
    void registerCallback(IHalCallback callback);
    void unregisterCallback(IHalCallback callback);

    // Subskrypcje — identyczna semantyka jak WS subscribe
    // events: JSON array np. ["scanner.barcode", "rfid.*"]
    String subscribe(String jsonEvents);
    String unsubscribe(String jsonEvents);
}
```

## IHalCallback.aidl

```aidl
interface IHalCallback {
    void onEvent(String eventName, String jsonData);
    void onError(String deviceType, int code, String message);
}
```

## Flow

1. bindService → onServiceConnected → IHalService
2. requestToken(jsonRequest) → token
3. authenticate(token) → true/false
4. registerCallback(callback)
5. subscribe(["scanner.barcode","rfid.*"])
6. execute("printer.print", params) → result
7. Eventy przychodzą przez callback.onEvent()

## Sesja AIDL

- Per callingUid: mapa uid → token (po authenticate)
- RemoteCallbackList<IHalCallback> z subskrypcjami per callback
- CallerContext: Binder.getCallingUid() → packageManager → packageName, certHash

## Binding

Token binding weryfikowane przez callingUid:
packageManager.getPackagesForUid(uid) → packageName
packageManager.getPackageInfo(pkg, GET_SIGNING_CERTIFICATES) → certHash
