package dev.duma.android.hal.transport.aidl;

import dev.duma.android.hal.transport.aidl.IHalCallback;

interface IHalService {
    String requestToken(String jsonRequest);
    boolean authenticate(String token);
    String execute(String method, String jsonParams);
    String getStatus();
    void registerCallback(IHalCallback callback);
    void unregisterCallback(IHalCallback callback);
    String subscribe(String jsonEvents);
    String unsubscribe(String jsonEvents);
}
