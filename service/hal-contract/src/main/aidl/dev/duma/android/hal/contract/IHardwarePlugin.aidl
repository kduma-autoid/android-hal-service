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

    // Added later — appended so older bundles/services stay compatible (an older bundle answers
    // getSettingsJson with null and ignores onSettingChanged).
    /** JSON array of PluginSetting, or null/"[]" when the plugin declares none. */
    String getSettingsJson();
    void onSettingChanged(String key, boolean value);
    // Appended last to keep the transaction codes above stable for older bundles and hosts.
    String getKeyboardFilterDevicesJson();
}