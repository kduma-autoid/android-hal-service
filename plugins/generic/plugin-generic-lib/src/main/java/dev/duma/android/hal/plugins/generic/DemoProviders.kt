package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import org.json.JSONObject

/**
 * Base for the always-available `demo` interface providers. Implements the contract methods
 * (`demo.echo`/`demo.ping`/`demo.emit`) and emits the `demo.notice` event, differing only in how
 * [transform] mutates echoed text, in priority (which one is the default), and in advertised feature.
 */
abstract class BaseDemoPlugin(
    private val id: String,
    private val displayName: String,
    private val priorityValue: Int,
    private val feature: String
) : HalPlugin {

    override val pluginId = id
    override val version = 1

    private var callback: HalPluginEventCallback? = null

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = id,
        name = displayName,
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        interfaces = listOf(
            InterfaceBinding(interfaceId = "demo", priority = priorityValue, features = listOf(feature))
        )
    )

    override fun initialize(pluginContext: PluginContext) {}

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

    protected abstract fun transform(text: String): String

    override suspend fun execute(method: String, params: String): CommandResult {
        val json = try {
            JSONObject(if (params.isBlank()) "{}" else params)
        } catch (_: Exception) {
            JSONObject()
        }
        return when (method.substringAfterLast('.')) {
            "echo" -> CommandResult.Success(
                JSONObject()
                    .put("result", transform(json.optString("text", "")))
                    .put("provider", id)
                    .toString()
            )
            "ping" -> CommandResult.Success(
                JSONObject().put("pong", true).put("provider", id).toString()
            )
            "emit" -> {
                callback?.onEvent(
                    "demo.notice",
                    JSONObject()
                        .put("message", json.optString("message", ""))
                        .put("provider", id)
                        .toString()
                )
                CommandResult.Success(
                    JSONObject().put("emitted", true).put("provider", id).toString()
                )
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}

/** Default `demo` provider — uppercases echoed text. */
class DemoAlphaPlugin : BaseDemoPlugin(
    id = "demo.alpha",
    displayName = "[Demo] Alpha (uppercase)",
    priorityValue = 100,
    feature = "uppercase"
) {
    override fun transform(text: String): String = text.uppercase()
}

/** Secondary `demo` provider — reverses echoed text. */
class DemoBetaPlugin : BaseDemoPlugin(
    id = "demo.beta",
    displayName = "[Demo] Beta (reverse)",
    priorityValue = 50,
    feature = "reverse"
) {
    override fun transform(text: String): String = text.reversed()
}
