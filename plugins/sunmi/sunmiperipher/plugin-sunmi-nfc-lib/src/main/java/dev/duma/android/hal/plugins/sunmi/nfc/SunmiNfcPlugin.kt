package dev.duma.android.hal.plugins.sunmi.nfc

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** HAL plugin wrapping Sunmi's NFC module controller and antenna metadata. */
class SunmiNfcPlugin internal constructor(
    private val context: Context?,
    private val sdk: NfcSdk,
    private val environment: NfcAntennaEnvironment,
    private val hostSerialResolver: TrustedHostSerialResolver,
    private val serialDispatcher: SerialDispatcher,
    private val backgroundDispatcher: CoroutineDispatcher
) : BaseHalPlugin() {

    constructor(context: Context? = null) : this(
        context = context,
        sdk = SunmiNfcSdk,
        environment = context?.let(::AndroidNfcAntennaEnvironment) ?: UnavailableNfcAntennaEnvironment,
        hostSerialResolver = AndroidTrustedHostSerialResolver,
        serialDispatcher = MainThreadSerialDispatcher(),
        backgroundDispatcher = Dispatchers.IO
    )

    override val pluginId = "sunmi.nfc"
    override val version = 2

    private val executeMutex = Mutex()
    private val lifecycleLock = Any()
    private val snapshotEngine = NfcAntennaSnapshotEngine()

    @Volatile
    private var callback: HalPluginEventCallback? = null

    private var pluginContext: PluginContext? = null
    private var generation = 0L
    private var lifecycleToken: Any? = null
    private var listenerRegistration: NfcListenerRegistration? = null
    private var backgroundScope: CoroutineScope? = null
    private var hostSerialRetryJob: Job? = null
    private var lastEmittedSnapshot: String? = null

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent("com.sunmi.nfc.ctrl").setPackage("com.sunmi.peripheralmanager")
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.nfc")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: NFC",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.nfc.switchModule",
                "Switch active NFC module by serial number.",
                "sunmi.nfc",
                exampleParameters = """{"sn":"NFC-001"}""",
                exampleOutput = "{}"
            ),
            MethodDescriptor(
                "sunmi.nfc.setWatermarkAlpha",
                "Set NFC watermark transparency (0-100).",
                "sunmi.nfc",
                exampleParameters = """{"alpha":100}""",
                exampleOutput = "{}"
            ),
            MethodDescriptor(
                "sunmi.nfc.getAntennaLocations",
                "Get immutable NFC module identity and antenna-location state.",
                "sunmi.nfc",
                exampleParameters = "{}",
                exampleOutput = EXAMPLE_SNAPSHOT
            )
        ),
        events = listOf(
            EventDescriptor(
                MODULES_CHANGED,
                "Fired when the list of available NFC modules changes.",
                "sunmi.nfc",
                exampleEvent = """{"modules":[{"sn":"NFC-001"}]}"""
            ),
            EventDescriptor(
                ANTENNA_LOCATIONS_CHANGED,
                "Fired with a full snapshot when NFC modules, identities, settings, or display geometry change.",
                "sunmi.nfc",
                exampleEvent = EXAMPLE_SNAPSHOT
            )
        )
    )

    override fun initialize(pluginContext: PluginContext) {
        val ctx = context ?: run {
            snapshotEngine.markUnavailable()
            emitSnapshotIfChanged()
            return
        }

        val token = Any()
        val activeGeneration: Long
        synchronized(lifecycleLock) {
            releaseResourcesLocked()
            activeGeneration = ++generation
            this.pluginContext = pluginContext
            snapshotEngine.beginInitialization()
            lastEmittedSnapshot = null
            lifecycleToken = token

            environment.start {
                serialDispatcher.post { handleEnvironmentChange(activeGeneration) }
            }
            refreshEnvironment()

            backgroundScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
            requestTrustedHostSerialResolutionLocked(activeGeneration, token)
        }

        try {
            sdk.init(ctx) { success ->
                serialDispatcher.post { handleSdkInitialization(activeGeneration, token, success) }
            }
        } catch (_: Exception) {
            serialDispatcher.post { handleSdkInitialization(activeGeneration, token, false) }
        }
    }

    override suspend fun onExecute(method: String, params: String): CommandResult = executeMutex.withLock {
        try {
            when (method) {
                "sunmi.nfc.switchModule" -> {
                    val requestedSn = JSONObject(params).optString("sn", "").trim()
                    val sn = requestedSn.ifEmpty {
                        resolveSwitchSerial()
                            ?: return@withLock CommandResult.badRequest(
                                "No SN provided and device identifier unavailable"
                            )
                    }
                    sdk.switchNfc(sn)
                    CommandResult.Success()
                }
                "sunmi.nfc.setWatermarkAlpha" -> {
                    val alpha = JSONObject(params).getInt("alpha")
                    if (alpha !in 0..100) {
                        return@withLock CommandResult.badRequest("alpha must be 0-100")
                    }
                    sdk.setWatermarkAlpha(alpha)
                    CommandResult.Success()
                }
                "sunmi.nfc.getAntennaLocations" -> {
                    val json = synchronized(lifecycleLock) {
                        refreshEnvironment()
                        lifecycleToken?.let { token ->
                            if (!snapshotEngine.hasTrustedHostSn()) {
                                requestTrustedHostSerialResolutionLocked(generation, token)
                            }
                        }
                        snapshotEngine.snapshot().toJson()
                    }
                    CommandResult.Success(json)
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
        synchronized(lifecycleLock) {
            ++generation
            releaseResourcesLocked()
            pluginContext = null
            snapshotEngine.markUnavailable()
            snapshotEngine.updateTrustedHostSn(null)
            snapshotEngine.updateEnvironment(SettingsSnapshot.UNREADABLE, DisplaySnapshot.Unavailable)
            lastEmittedSnapshot = null
        }
    }

    private fun handleSdkInitialization(
        expectedGeneration: Long,
        token: Any,
        success: Boolean
    ) {
        synchronized(lifecycleLock) {
            if (generation != expectedGeneration || lifecycleToken !== token) return
            if (!success) {
                snapshotEngine.markUnavailable()
                refreshEnvironment()
                emitSnapshotIfChanged()
                return
            }
            try {
                val registration = sdk.registerListener { nfcList ->
                    val copied = nfcList.orEmpty().map { nfc ->
                        CachedNfcModule(
                            sn = nfc.sn.orEmpty(),
                            sdkType = nfc.type,
                            selected = nfc.isSelect,
                            watermarkAlpha = nfc.waterMarkAlpha
                        )
                    }
                    serialDispatcher.post { handleModuleList(expectedGeneration, token, copied) }
                }
                if (generation != expectedGeneration || lifecycleToken !== token) {
                    registration.unregister()
                    return
                }
                listenerRegistration = registration
            } catch (_: Exception) {
                snapshotEngine.markUnavailable()
                refreshEnvironment()
                emitSnapshotIfChanged()
            }
        }
    }

    private fun handleModuleList(
        expectedGeneration: Long,
        token: Any,
        modules: List<CachedNfcModule>
    ) {
        synchronized(lifecycleLock) {
            if (generation != expectedGeneration || lifecycleToken !== token) return
            snapshotEngine.markReady(modules)
            refreshEnvironment()
            callback?.onEvent(MODULES_CHANGED, buildLegacyModulesJson(modules))
            emitSnapshotIfChanged()
            if (!snapshotEngine.hasTrustedHostSn()) {
                requestTrustedHostSerialResolutionLocked(expectedGeneration, token)
            }
        }
    }

    private fun handleEnvironmentChange(expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (generation != expectedGeneration) return
            refreshEnvironment()
            emitSnapshotIfChanged()
        }
    }

    private fun refreshEnvironment() {
        val settings = try {
            environment.readSettings()
        } catch (_: Exception) {
            SettingsSnapshot.UNREADABLE
        }
        val display = try {
            environment.readDisplay()
        } catch (_: Exception) {
            DisplaySnapshot.Unavailable
        }
        snapshotEngine.updateEnvironment(settings, display)
    }

    private fun emitSnapshotIfChanged() {
        val eventCallback = callback ?: return
        val json = snapshotEngine.snapshot().toJson()
        if (json == lastEmittedSnapshot) return
        lastEmittedSnapshot = json
        eventCallback.onEvent(ANTENNA_LOCATIONS_CHANGED, json)
    }

    private fun requestTrustedHostSerialResolutionLocked(
        expectedGeneration: Long,
        token: Any
    ) {
        if (hostSerialRetryJob != null || snapshotEngine.hasTrustedHostSn()) return
        if (generation != expectedGeneration || lifecycleToken !== token) return
        val scope = backgroundScope ?: return
        val contextForResolution = pluginContext
        hostSerialRetryJob = scope.launch {
            var attempt = 0
            while (isActive) {
                if (attempt > 0) delay(hostSerialRetryDelayMs(attempt))
                val trustedSn = try {
                    hostSerialResolver.resolve(contextForResolution)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (trustedSn != null) {
                    serialDispatcher.post {
                        synchronized(lifecycleLock) {
                            if (generation != expectedGeneration || lifecycleToken !== token) return@post
                            hostSerialRetryJob = null
                            snapshotEngine.updateTrustedHostSn(trustedSn)
                            refreshEnvironment()
                            emitSnapshotIfChanged()
                        }
                    }
                    return@launch
                }
                attempt++
            }
        }
    }

    private fun hostSerialRetryDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 5_000L
        else -> 30_000L
    }

    /** Compatibility path for switchModule only; ANDROID_ID is never used for reader identity. */
    private suspend fun resolveSwitchSerial(): String? {
        hostSerialResolver.resolve(pluginContext)?.let { return it }
        return Settings.Secure.getString(context?.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun releaseResourcesLocked() {
        backgroundScope?.cancel()
        backgroundScope = null
        hostSerialRetryJob = null
        listenerRegistration?.let { registration ->
            try {
                registration.unregister()
            } catch (_: Exception) {
                // The remote service may already be gone.
            }
        }
        listenerRegistration = null
        lifecycleToken = null
        try {
            environment.stop()
        } catch (_: Exception) {
            // Idempotent teardown.
        }
    }

    private fun buildLegacyModulesJson(modules: List<CachedNfcModule>): String = JSONObject().apply {
        put("modules", JSONArray().apply {
            modules.forEach { module -> put(JSONObject().put("sn", module.sn)) }
        })
    }.toString()

    private companion object {
        const val MODULES_CHANGED = "sunmi.nfc.modulesChanged"
        const val ANTENNA_LOCATIONS_CHANGED = "sunmi.nfc.antennaLocationsChanged"
        const val EXAMPLE_SNAPSHOT =
            """{"state":"ready","controller":"builtin","modules":[{"sn":"NFC-001","sdkType":0,"selected":true,"reader":"builtin","location":{"displayId":0,"displayWidthPx":1920,"displayHeightPx":1080,"rotationDegrees":0,"rect":{"x":1200,"y":470,"width":200,"height":200},"estimated":false},"locationUnavailableReason":null}]}"""
    }
}
