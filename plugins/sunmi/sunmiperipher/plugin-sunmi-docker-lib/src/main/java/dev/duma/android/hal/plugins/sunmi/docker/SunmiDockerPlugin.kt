package dev.duma.android.hal.plugins.sunmi.docker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.sunmi.docker.IDockerController
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * HAL plugin wrapping the Sunmi DockerManager/IDockerController SDK.
 * Controls reverse power on the SUNMI FLEX 3 docking station.
 * Binds directly to the Docker controller service via AIDL.
 *
 * @param context Android Context needed to bind Docker controller service.
 */
class SunmiDockerPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.docker"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private var dockerController: IDockerController? = null

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

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi Docker Service Controller",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.docker.enableReversePower",
                "Enable reverse power (power from tablet to dock).",
                "sunmi.docker",
                superRequired = true,
                exampleParameters = "{}",
                exampleOutput = """{"status": "ok"}"""
            ),
            MethodDescriptor(
                "sunmi.docker.disableReversePower",
                "Disable reverse power.",
                "sunmi.docker",
                superRequired = true,
                exampleParameters = "{}",
                exampleOutput = """{"status": "ok"}"""
            ),
            MethodDescriptor(
                "sunmi.docker.isReversePowerEnabled",
                "Check if reverse power is currently enabled.",
                "sunmi.docker",
                exampleParameters = "{}",
                exampleOutput = """{"enabled": true}"""
            )
        ),
        events = emptyList()
    )

    override fun initialize(context: PluginContext) {
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

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        val controller = dockerController
            ?: return@withLock error("device_not_ready", "Docker controller not connected")

        return@withLock try {
            when (method) {
                "sunmi.docker.enableReversePower" -> {
                    controller.enableReversePower()
                    success()
                }
                "sunmi.docker.disableReversePower" -> {
                    controller.disableReversePower()
                    success()
                }
                "sunmi.docker.isReversePowerEnabled" -> {
                    """{"enabled":${controller.isReversePowerEnabled}}"""
                }
                else -> error("unsupported_method", "Method not supported: $method")
            }
        } catch (e: Exception) {
            error("sdk_error", e.message ?: "Unknown SDK error")
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

    private fun success(): String = """{"status":"ok"}"""
    private fun error(code: String, message: String): String =
        """{"error":"$code","message":"$message"}"""
}
