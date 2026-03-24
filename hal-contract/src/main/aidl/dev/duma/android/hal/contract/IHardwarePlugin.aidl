package dev.duma.android.hal.contract;

import dev.duma.android.hal.contract.IPluginEventCallback;

interface IHardwarePlugin {
    String getPluginId();
    int getVersion();
    List<String> getCapabilities();
    String execute(String method, String jsonParams);
    void registerEventCallback(IPluginEventCallback callback);
    void unregisterEventCallback(IPluginEventCallback callback);
}