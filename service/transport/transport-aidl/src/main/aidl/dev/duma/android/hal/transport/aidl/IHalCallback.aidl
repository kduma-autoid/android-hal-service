package dev.duma.android.hal.transport.aidl;

interface IHalCallback {
    void onEvent(String eventName, String jsonData, String source);
    void onError(String deviceType, int code, String message);
}
