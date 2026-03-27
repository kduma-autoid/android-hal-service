package dev.duma.android.hal.contract;

interface IPluginEventCallback {
    void onEvent(String eventName, String jsonData);
    void onError(String deviceType, int code, String message);
}