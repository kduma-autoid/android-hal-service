package dev.duma.android.hal.transport.aidl;

import dev.duma.android.hal.contract.CommandResult;
import dev.duma.android.hal.transport.aidl.IHalCallback;

interface IHalService {
    CommandResult requestToken(String jsonRequest);
    boolean authenticate(String token);
    CommandResult execute(String method, String jsonParams);
    String getStatus();
    void registerCallback(IHalCallback callback);
    void unregisterCallback(IHalCallback callback);
    CommandResult subscribe(String jsonEvents);
    CommandResult unsubscribe(String jsonEvents);
}
