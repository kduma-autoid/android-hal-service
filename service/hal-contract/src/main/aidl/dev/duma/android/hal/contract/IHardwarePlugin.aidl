package dev.duma.android.hal.contract;

import dev.duma.android.hal.contract.IPluginEventCallback;
import dev.duma.android.hal.contract.IPluginContext;
import dev.duma.android.hal.contract.CommandResult;

interface IHardwarePlugin {
    String getPluginId();
    int getVersion();
    boolean isSupported();
    List<String> getCapabilities();
    CommandResult execute(String method, String jsonParams);
    String getDescriptorJson();
    void registerEventCallback(IPluginEventCallback callback);
    void unregisterEventCallback(IPluginEventCallback callback);
    void initialize(IPluginContext context);
}