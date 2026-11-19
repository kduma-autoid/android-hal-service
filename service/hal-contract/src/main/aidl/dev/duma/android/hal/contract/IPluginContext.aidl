package dev.duma.android.hal.contract;

import dev.duma.android.hal.contract.CommandResult;

interface IPluginContext {
    CommandResult execute(String method, String jsonParams);
    List<String> getAvailableCapabilities();
    boolean hasCapability(String capability);

    // Added later — appended so older bundles/services stay compatible. A bundle calling these on an
    // older hal-service gets the empty reply: a no-op / null.
    void setPluginAvailable(boolean available);
    /** Stored value of one of the plugin's settings as "true"/"false", or null when never changed. */
    String getSetting(String key);
}
