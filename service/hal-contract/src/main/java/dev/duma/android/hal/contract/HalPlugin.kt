package dev.duma.android.hal.contract

/**
 * In-process hardware plugin interface. Each plugin (e.g. Sunmi printer, scanner)
 * implements this interface. hal-service registers plugins and routes commands
 * to the appropriate plugin based on method name (e.g. "sunmi.printer.print").
 */
interface HalPlugin {
    val pluginId: String
    val version: Int
    fun isSupported(): Boolean
    fun getCapabilities(): List<String>
    fun getDescriptor(): PluginDescriptor
    fun initialize(pluginContext: PluginContext)
    suspend fun execute(method: String, params: String): CommandResult
    fun setEventCallback(callback: HalPluginEventCallback?)

    /**
     * Releases resources acquired in [initialize] (e.g. registered BroadcastReceivers,
     * bound services). Called when the plugin is torn down — on hal-service shutdown,
     * when displaced by a higher-priority plugin, or from a bundle Service's onDestroy.
     * Default is a no-op; override only if the plugin holds releasable resources.
     */
    fun dispose() {}

    /**
     * Keyboard-emulating input devices owned by this plugin. While the user has enabled HAL's
     * keyboard filter (one Android accessibility service in the HAL app, shared by all plugins),
     * key DOWN/UP events from these devices are consumed so they never reach the focused app —
     * the plugin delivers the data through its own API instead.
     *
     * Read once when the plugin registers; the list is expected to be constant for the instance.
     * Default: none.
     */
    fun getKeyboardFilterDevices(): List<KeyboardDeviceId> = emptyList()
}
