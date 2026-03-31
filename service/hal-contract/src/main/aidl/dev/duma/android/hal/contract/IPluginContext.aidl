package dev.duma.android.hal.contract;

import dev.duma.android.hal.contract.CommandResult;

interface IPluginContext {
    CommandResult execute(String method, String jsonParams);
    List<String> getAvailableCapabilities();
    boolean hasCapability(String capability);
}
