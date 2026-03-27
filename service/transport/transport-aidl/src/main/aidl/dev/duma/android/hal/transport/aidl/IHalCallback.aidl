package dev.duma.android.hal.transport.aidl;

interface IHalCallback {
    void onEvent(String eventName, String jsonData);
    void onError(String deviceType, int code, String message);
}
