package dev.duma.android.hal.plugins.sunmi.docker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.sunmi.docker.IDockerController
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi DockerManager/IDockerController SDK.
 * Controls reverse power on the SUNMI FLEX 3 docking station.
 * Binds directly to the Docker controller service via AIDL.
 *
 * It also reports which dock the tablet sits in ([DockSku]). Sunmi Peripheral Manager asks the dock
 * MCU for its SKU through the `smdss` daemon and stores the answer in the system setting
 * [KEY_DOCKER_SKU]; apps cannot reach `smdss` or the pogo kernel nodes themselves, so the setting is
 * the only source. Peripheral Manager clears it on every power disconnect (even a short blip while
 * docking), so an empty value means "unknown", not necessarily "undocked".
 *
 * @param context Android Context needed to bind Docker controller service.
 */
class SunmiDockerPlugin(
    private val context: Context? = null
) : BaseHalPlugin() {

    override val pluginId = "sunmi.docker"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private var dockerController: IDockerController? = null
    private var skuObserver: ContentObserver? = null
    private var lastSku: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            dockerController = service?.let { IDockerController.Stub.asInterface(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            dockerController = null
        }
    }

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent("com.sunmi.docker.service")
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.docker")

    override fun getDescriptor() = fullDescriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    private fun fullDescriptor(): PluginDescriptor = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: Docker Service",
        version = version,
        experimental = true,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.docker.enableReversePower",
                "Enable reverse power (power from tablet to dock).",
                "sunmi.docker",
                superRequired = true,
                experimental = true,
                exampleParameters = "{}",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.docker.disableReversePower",
                "Disable reverse power.",
                "sunmi.docker",
                superRequired = true,
                experimental = true,
                exampleParameters = "{}",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.docker.isReversePowerEnabled",
                "Check if reverse power is currently enabled.",
                "sunmi.docker",
                experimental = true,
                exampleParameters = "{}",
                exampleOutput = """{"enabled": true}"""
            ),
            MethodDescriptor(
                "sunmi.docker.getDockInfo",
                "Dock the tablet sits in, as reported by the dock to Sunmi Peripheral Manager: raw SKU " +
                    "(e.g. \"1\" or \"D05-000\") and " +
                    "model name. sku is null when unknown: no dock, or Peripheral Manager cleared it " +
                    "after a power blip (it is not re-read until the tablet is docked again).",
                "sunmi.docker",
                experimental = true,
                exampleParameters = "{}",
                exampleOutput = """{"sku":"1","model":"Table-Side Stand","family":"tinno"}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                "sunmi.docker.dockChanged",
                "The dock SKU reported by Peripheral Manager changed (docked, undocked or cleared).",
                "sunmi.docker",
                experimental = true,
                exampleEvent = """{"sku":"1","model":"Table-Side Stand","family":"tinno"}"""
            )
        )
    )

    override fun initialize(pluginContext: PluginContext) {
        this.context?.let { ctx ->
            lastSku = readSku(ctx)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val sku = readSku(ctx)
                    if (sku == lastSku) return
                    lastSku = sku
                    callback?.onEvent("sunmi.docker.dockChanged", dockInfoJson(sku))
                }
            }
            try {
                ctx.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(KEY_DOCKER_SKU), false, observer
                )
                skuObserver = observer
            } catch (_: Exception) { }
        }
        this.context?.let { ctx ->
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.sunmi.dockercontroller",
                        "com.sunmi.dockercontroller.DockerControllerService"
                    )
                }
                ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) { }
        }
    }

    override suspend fun onExecute(method: String, params: String): CommandResult = mutex.withLock {
        if (method == "sunmi.docker.getDockInfo") {
            val ctx = context ?: return@withLock CommandResult.unavailable("No context")
            return@withLock CommandResult.Success(dockInfoJson(readSku(ctx)))
        }
        val controller = dockerController
            ?: return@withLock CommandResult.unavailable("Docker controller not connected")

        return@withLock try {
            when (method) {
                "sunmi.docker.enableReversePower" -> {
                    controller.enableReversePower()
                    CommandResult.Success()
                }
                "sunmi.docker.disableReversePower" -> {
                    controller.disableReversePower()
                    CommandResult.Success()
                }
                "sunmi.docker.isReversePowerEnabled" -> {
                    CommandResult.Success("""{"enabled":${controller.isReversePowerEnabled}}""")
                }
                else -> CommandResult.unsupportedMethod(method)
            }
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown SDK error")
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

    override fun dispose() {
        val ctx = context ?: return
        skuObserver?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
        skuObserver = null
        runCatching { ctx.unbindService(connection) }
    }

    private fun readSku(ctx: Context): String? =
        try {
            Settings.System.getString(ctx.contentResolver, KEY_DOCKER_SKU)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

    private fun dockInfoJson(sku: String?): String {
        val known = sku?.let { DockSku.find(it) }
        return JSONObject()
            .put("sku", sku ?: JSONObject.NULL)
            .put("model", known?.model ?: JSONObject.NULL)
            .put("family", known?.family ?: JSONObject.NULL)
            .toString()
    }

    /**
     * SKU table of Sunmi Peripheral Manager 1.0.77 (`com.sunmi.docker.utils.SKUEnum`). Docks report
     * longer strings than the table holds (a Printing Base answers `D05-000`, a Tinno stand `1`), so
     * [find] matches by prefix like Peripheral Manager's `SkuFinder`.
     */
    private enum class DockSku(val sku: String, val model: String, val family: String) {
        TABLE_SIDE_STAND("1", "Table-Side Stand", "tinno"),
        TABLE_SIDE_FIXED("2", "Table-Side Fixed Stand", "tinno"),
        SINGLE_ADAPTER("3", "Single adapter", "tinno"),
        DESKTOP_BASE_D03("D03", "Desktop Base", "sunmi"),
        DESKTOP_BASE_D04("D04", "Desktop Base", "sunmi"),
        PRINTING_BASE_D05("D05", "Printing Base", "sunmi"),
        PRINTING_BASE_D06("D06", "Printing Base", "sunmi"),
        MULTI_FUNCTION_D07("D07", "Multi-Function Pivoting Base", "sunmi");

        companion object {
            fun find(sku: String) = entries.firstOrNull { sku.startsWith(it.sku) }
        }
    }

    private companion object {
        /** System setting Sunmi Peripheral Manager writes the dock SKU to. */
        const val KEY_DOCKER_SKU = "key_docker_sku"
    }

}
