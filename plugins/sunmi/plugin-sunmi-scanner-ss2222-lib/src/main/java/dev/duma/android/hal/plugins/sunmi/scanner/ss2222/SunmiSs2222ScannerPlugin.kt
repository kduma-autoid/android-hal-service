package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.Ss2222Driver
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.DriverException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** USB Host provider for SS2222; neither the Sunmi scanner SDK nor keyboard focus is required. */
class SunmiSs2222ScannerPlugin internal constructor(
    private val appContext: Context?,
    private val newDriver: (Context, Ss2222Driver.Listener) -> Ss2222Driver,
) : BaseHalPlugin() {
    constructor(appContext: Context? = null) : this(appContext, ::Ss2222Driver)
    override val pluginId = ID
    override val version = 1
    @Volatile private var callback: HalPluginEventCallback? = null
    @Volatile private var driver: Ss2222Driver? = null
    @Volatile private var context: Context? = appContext?.applicationContext
    private val generation = AtomicLong()
    private val commands = Mutex()

    override fun isSupported(): Boolean = context?.packageManager
        ?.hasSystemFeature(PackageManager.FEATURE_USB_HOST) == true

    override fun getCapabilities() = listOf(ID)

    override fun getDescriptor(): PluginDescriptor = descriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    @Synchronized
    override fun initialize(pluginContext: PluginContext) {
        val epoch = generation.incrementAndGet()
        driver?.close()
        context = appContext?.applicationContext ?: pluginContext.applicationContext.applicationContext
        pluginContext.setPluginAvailable(false)
        val listener = object : Ss2222Driver.Listener {
            override fun onStateChanged(state: Ss2222Driver.State) {
                if (generation.get() != epoch) return
                pluginContext.setPluginAvailable(state in READY_STATES)
                emit("$ID.stateChanged", buildJsonObject { put("state", state.name) })
            }

            override fun onBarcode(raw: ByteArray) {
                if (generation.get() != epoch) return
                // The factory output has one terminal CR. Do not trim arbitrary whitespace or
                // every trailing CR/LF: a QR's own trailing newline is part of its payload.
                val end = if (raw.lastOrNull() == 0x0d.toByte()) raw.size - 1 else raw.size
                val body = buildJsonObject {
                    put("data", String(raw, 0, end, Charsets.UTF_8))
                    put("format", "UNKNOWN")
                    put("rawData", Base64.encodeToString(raw, Base64.NO_WRAP))
                }
                emit("$ID.barcode", body)
                emit("barcodeScanner.onScan", body)
            }

            override fun onError(code: String, message: String) {
                if (generation.get() != epoch) return
                emit("$ID.error", buildJsonObject { put("code", code); put("message", message) })
            }
        }
        driver = newDriver(context!!, listener).also { it.open() }
    }

    @Synchronized
    override fun dispose() {
        generation.incrementAndGet()
        val previous = driver
        driver = null
        previous?.close()
        callback = null
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) { this.callback = callback }

    override suspend fun onExecute(method: String, params: String): CommandResult = commands.withLock {
        try {
            val input = Json.parseToJsonElement(params.ifBlank { "{}" }) as? JsonObject
                ?: return@withLock CommandResult.badRequest("Parameters must be a JSON object")
            val operation = when (method) {
                "barcodeScanner.trigger" -> "trigger"
                "barcodeScanner.stop" -> "stop"
                else -> method.removePrefix("$ID.")
            }
            val body = when (operation) {
                "getStatus" -> {
                    Ss2222Parameters.checkKeys(input, emptySet())
                    status()
                }
                "trigger", "startContinuous" -> {
                    Ss2222Parameters.checkKeys(input, emptySet())
                    requireDriver().start(singleShot = operation == "trigger")
                    buildJsonObject { put("status", "scanning") }
                }
                "stop" -> {
                    Ss2222Parameters.checkKeys(input, emptySet())
                    requireDriver().stop()
                    buildJsonObject {}
                }
                "getDeviceInfo" -> {
                    Ss2222Parameters.checkKeys(input, emptySet())
                    val active = requireDriver()
                    active.queryRevision()
                    deviceInfo(active)
                }
                "getConfig" -> {
                    Ss2222Parameters.checkKeys(input, emptySet())
                    config(requireDriver())
                }
                "setConfig" -> {
                    val values = Ss2222Parameters.config(input)
                    requireDriver().writeParams(values)
                    buildJsonObject {}
                }
                "beep" -> {
                    Ss2222Parameters.checkKeys(input, setOf("kind"))
                    val kind = Ss2222Parameters.string(input, "kind")
                    require(kind == "ok" || kind == "error") { "kind must be ok or error" }
                    requireDriver().beep(error = kind == "error")
                    buildJsonObject {}
                }
                "readParam" -> {
                    Ss2222Parameters.checkKeys(input, setOf("id"))
                    val id = Ss2222Parameters.readable(Ss2222Parameters.integer(input, "id"))
                    parameter(requireDriver(), id)
                }
                "setParams" -> {
                    val values = Ss2222Parameters.parameters(input)
                    requireDriver().writeParams(values)
                    buildJsonObject {}
                }
                "rawCommand" -> raw(Ss2222Parameters.raw(input), requireDriver())
                else -> return@withLock CommandResult.unsupportedMethod(method)
            }
            CommandResult.Success(body.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            CommandResult.badRequest(e.message ?: "Invalid parameters")
        } catch (e: DriverException) {
            val type = when (e.code) {
                "command_timeout" -> CommandResult.ErrorType.TIMEOUT
                "busy", "invalid_state", "unavailable", "device_disconnected", "permission_denied", "not_connected" -> CommandResult.ErrorType.UNAVAILABLE
                else -> CommandResult.ErrorType.INTERNAL
            }
            CommandResult.Failure(e.code, e.message ?: "Scanner error", type)
        }
    }

    private fun requireDriver(): Ss2222Driver = driver
        ?: throw DriverException("unavailable", "SS2222 is not initialized")

    private fun status(): JsonObject = buildJsonObject {
        val active = driver
        put("state", active?.state?.name ?: "DISCONNECTED")
        put("available", active?.state in READY_STATES)
        put("keyboardFilterEnabled", context?.let(Ss2222KeyboardFilterService::isEnabled) ?: false)
        put("device", if (active?.deviceInfo == null) JsonNull else deviceInfo(active))
    }

    private fun deviceInfo(active: Ss2222Driver): JsonObject = buildJsonObject {
        active.deviceInfo?.let {
            put("model", it.model)
            put("productId", it.productId)
            put("hardwareVersion", it.hardwareVersion)
            put("firmwareVersion", it.firmwareVersion)
        }
    }

    private suspend fun config(active: Ss2222Driver): JsonObject {
        val values = Ss2222Parameters.configurableIds.associateWith { id ->
            val bytes = active.readParam(id)
            if (bytes.size != 1) throw DriverException("invalid_response", "Unexpected value length for parameter $id")
            bytes[0].toInt() and 0xff
        }
        fun flag(id: Int) = when (values[id]) {
            0 -> JsonPrimitive(false)
            1 -> JsonPrimitive(true)
            else -> JsonNull
        }
        return buildJsonObject {
            put("symbologies", buildJsonObject {
                Ss2222Parameters.symbologies.forEach { (name, id) -> put(name, flag(id)) }
            })
            put("goodReadBeep", flag(Ss2222Parameters.GOOD_READ_BEEP))
            put("beepVolume", Ss2222Parameters.volumes.getOrNull(values.getValue(Ss2222Parameters.VOLUME))
                ?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private suspend fun parameter(active: Ss2222Driver, id: Int): JsonObject {
        val bytes = active.readParam(id)
        return buildJsonObject { put("id", id); put("value", Ss2222Parameters.hex(bytes)) }
    }

    private suspend fun raw(op: Ss2222Parameters.RawOperation, active: Ss2222Driver): JsonObject {
        // State-changing diagnostics go through the controller, never directly to USB.
        return when (op.opcode) {
            0xA3 -> {
                val reply = active.queryRevision()
                buildJsonObject { put("reply", Ss2222Parameters.hex(reply)) }
            }
            0xC7 -> parameter(active, op.parameter!!)
            0xC6 -> { active.writeParams(op.writes); buildJsonObject {} }
            0xE6 -> { active.beep(op.beepError); buildJsonObject {} }
            0xE9 -> { active.start(singleShot = false); buildJsonObject { put("status", "scanning") } }
            0xEA -> { active.stop(); buildJsonObject {} }
            else -> error("Validated opcode was not handled")
        }
    }

    private fun emit(event: String, body: JsonObject) {
        try {
            callback?.onEvent(event, body.toString())
        } catch (e: Exception) {
            Log.w("SS2222", "Event callback failed for $event", e)
        }
    }

    private fun descriptor() = PluginDescriptor(
        pluginId = ID, name = "Sunmi: SS2222 USB Scanner", version = version,
        capabilities = getCapabilities(),
        interfaces = listOf(InterfaceBinding("barcodeScanner", priority = 110)),
        groups = listOf(
            DescriptorGroup("Scanning", methods = listOf(
                method("trigger", "Wait for one barcode until decoded or stopped.", output = "{\"status\":\"scanning\"}"),
                method("stop", "Stop scanning; already idle is a successful no-op."),
                method("startContinuous", "Read successive barcodes until stop.", output = "{\"status\":\"scanning\"}"),
                method("getStatus", "Session state and keyboard filter setting (enabled does not prove effective filtering).",
                    output = "{\"state\":\"IDLE\",\"available\":true,\"keyboardFilterEnabled\":false}"),
                method("getDeviceInfo", "Read SS2222 product, hardware and firmware information.",
                    output = "{\"model\":\"SS2222\",\"hardwareVersion\":\"V3.0.A\",\"firmwareVersion\":\"2.4.94\"}"),
            )),
            DescriptorGroup("Configuration", methods = listOf(
                method("getConfig", "Read supported symbology switches, good-read beep and volume.",
                    output = "{\"symbologies\":{\"EAN13\":true},\"goodReadBeep\":true,\"beepVolume\":\"high\"}"),
                method("setConfig", "Apply temporary settings while idle; verify and roll back on failure.",
                    params = "{\"symbologies\":{\"DATA_MATRIX\":true},\"goodReadBeep\":false,\"beepVolume\":\"medium\"}"),
                method("beep", "Play the scanner's OK or error sound (muted when volume is mute).", params = "{\"kind\":\"ok\"}"),
            )),
            DescriptorGroup("SSI diagnostics", methods = listOf(
                method("readParam", "Read one observed SSI parameter; value is hex bytes.", "{\"id\":138}", "{\"id\":138,\"value\":\"11\"}", true),
                method("setParams", "Temporary, idle-only writes restricted to the named configuration allowlist.",
                    "{\"parameters\":[{\"id\":3,\"value\":1}]}", diagnostic = true),
                method("rawCommand", "Controlled SSI opcode and hex payload. A3/C7/C6/E6/E9/EA only; the controller enforces state and write policy.",
                    "{\"opcode\":163,\"data\":\"\"}", diagnostic = true),
            )),
            DescriptorGroup(events = listOf(
                EventDescriptor("$ID.barcode", "Decoded barcode; rawData preserves all bytes including the terminator.", ID,
                    exampleEvent = "{\"data\":\"123\",\"format\":\"UNKNOWN\",\"rawData\":\"MTIzDQ==\"}"),
                EventDescriptor("$ID.stateChanged", "Scanner lifecycle or scan session changed.", ID,
                    exampleEvent = "{\"state\":\"IDLE\"}"),
                EventDescriptor("$ID.error", "USB, protocol or configuration failure.", ID,
                    exampleEvent = "{\"code\":\"command_timeout\",\"message\":\"SSI response timed out\"}"),
            )),
        ),
    )

    private fun method(name: String, description: String, params: String = "{}", output: String = "{}", diagnostic: Boolean = false) =
        MethodDescriptor("$ID.$name", description, ID, superRequired = diagnostic, experimental = diagnostic,
            exampleParameters = params, exampleOutput = output)

    companion object {
        const val ID = "sunmi.scanner.ss2222"
        private val READY_STATES = setOf(Ss2222Driver.State.IDLE, Ss2222Driver.State.STARTING,
            Ss2222Driver.State.SCANNING, Ss2222Driver.State.STOPPING)
    }
}
