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
}
