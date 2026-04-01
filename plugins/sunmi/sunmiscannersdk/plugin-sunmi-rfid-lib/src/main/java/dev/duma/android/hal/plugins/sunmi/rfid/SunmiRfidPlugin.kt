package dev.duma.android.hal.plugins.sunmi.rfid

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sunmi.rfid.RFIDManager
import com.sunmi.sdk.ServiceConnectStatus
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.rfid.descriptor.RfidEventDescriptors
import dev.duma.android.hal.plugins.sunmi.rfid.descriptor.RfidMethodDescriptors
import dev.duma.android.hal.plugins.sunmi.rfid.handler.BatteryGpioHandler
import dev.duma.android.hal.plugins.sunmi.rfid.handler.InventoryHandler
import dev.duma.android.hal.plugins.sunmi.rfid.handler.ReaderConfigHandler
import dev.duma.android.hal.plugins.sunmi.rfid.handler.ReaderInfoHandler
import dev.duma.android.hal.plugins.sunmi.rfid.handler.SystemHandler
import dev.duma.android.hal.plugins.sunmi.rfid.handler.Tag6BHandler
import dev.duma.android.hal.plugins.sunmi.rfid.handler.Tag6CHandler
import dev.duma.android.hal.plugins.sunmi.rfid.receiver.RfidBroadcastReceiver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi RFID SDK (SunmiScannerSdk).
 * Controls the UHF RFID module on Sunmi L2k-UHF.
 *
 * Hybrid sync/async model:
 * - Inventory/streaming methods return {"status":"started"} and deliver results via events
 * - All other methods wait for the SDK callback and return the result directly
 */
class SunmiRfidPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.rfid"
    override val version = 1

    private var eventCallback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private var serviceConnectStatus: ServiceConnectStatus? = null

    // Infrastructure
    private val bridge = RfidOperationBridge(::emitEvent)
    private val broadcastReceiver by lazy { RfidBroadcastReceiver(::emitEvent) }

    // Handlers
    private val inventoryHandler by lazy { InventoryHandler(bridge) }
    private val tag6CHandler by lazy { Tag6CHandler(bridge) }
    private val tag6BHandler by lazy { Tag6BHandler(bridge) }
    private val readerConfigHandler by lazy { ReaderConfigHandler(bridge) }
    private val readerInfoHandler by lazy { ReaderInfoHandler(bridge) }
    private val batteryGpioHandler by lazy { BatteryGpioHandler(bridge) }
    private val systemHandler by lazy { SystemHandler(bridge) }

    // --- Lifecycle ---

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent("com.sunmi.scanner.IScanRFIDInterface")
            .setPackage("com.sunmi.scanner")
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun initialize(pluginContext: PluginContext) {
        this.context?.let { ctx ->
            RFIDManager.getInstance().setPrintLog(false)

            val connectStatus = object : ServiceConnectStatus {
                override fun onServiceConnected() {
                    RFIDManager.getInstance().getHelper()?.registerReaderCall(bridge.readerCall)
                }
                override fun onServiceDisconnected() {
                    try {
                        RFIDManager.getInstance().getHelper()?.unregisterReaderCall()
                    } catch (_: Exception) {
                        // Service already gone
                    }
                }
            }
            serviceConnectStatus = connectStatus
            RFIDManager.getInstance().addServiceConnectStatus(connectStatus)

            RFIDManager.getInstance().connect(ctx)
            ContextCompat.registerReceiver(ctx, broadcastReceiver, RfidBroadcastReceiver.buildIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        eventCallback = callback
    }

    override fun getCapabilities() = listOf("sunmi.rfid")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: UHF RFID",
        version = version,
        capabilities = getCapabilities(),
        groups = listOf(
            DescriptorGroup(name = "Inventory", methods = RfidMethodDescriptors.inventoryMethods()),
            DescriptorGroup(name = "6C Tag Operations", methods = RfidMethodDescriptors.tag6CMethods()),
            DescriptorGroup(name = "6B Tag Operations", methods = RfidMethodDescriptors.tag6BMethods()),
            DescriptorGroup(name = "Reader Configuration", methods = RfidMethodDescriptors.readerConfigMethods()),
            DescriptorGroup(name = "Reader Info", methods = RfidMethodDescriptors.readerInfoMethods()),
            DescriptorGroup(name = "Battery & GPIO", methods = RfidMethodDescriptors.batteryGpioMethods()),
            DescriptorGroup(name = "System", methods = RfidMethodDescriptors.systemMethods()),
            DescriptorGroup(events = RfidEventDescriptors.allEvents()),
        )
    )

    // --- Execute ---

    override suspend fun execute(method: String, params: String): CommandResult = mutex.withLock {
        val helper = RFIDManager.getInstance().getHelper()
            ?: return@withLock CommandResult.unavailable("RFID helper not available")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return@withLock try {
            inventoryHandler.handle(method, json, helper)
                ?: tag6CHandler.handle(method, json, helper)
                ?: tag6BHandler.handle(method, json, helper)
                ?: readerConfigHandler.handle(method, json, helper)
                ?: readerInfoHandler.handle(method, json, helper)
                ?: batteryGpioHandler.handle(method, json, helper)
                ?: systemHandler.handle(method, json, helper)
                ?: CommandResult.unsupportedMethod(method)
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown SDK error")
        }
    }

    private fun emitEvent(event: String, payload: String) {
        eventCallback?.onEvent(event, payload)
    }
}
