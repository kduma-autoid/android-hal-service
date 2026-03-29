package dev.duma.android.hal.contract;

interface IPluginContext {
    String execute(String method, String jsonParams);
    List<String> getAvailableCapabilities();
    boolean hasCapability(String capability);
}
