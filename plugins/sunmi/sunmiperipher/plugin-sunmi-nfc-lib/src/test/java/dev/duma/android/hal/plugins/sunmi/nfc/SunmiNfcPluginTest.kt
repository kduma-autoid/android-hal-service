package dev.duma.android.hal.plugins.sunmi.nfc

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.sunmi.nfc.Nfc
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SunmiNfcPluginTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `descriptor v2 declares new getter and full snapshot event`() {
        val plugin = plugin(FakeSdk(), FakeEnvironment()).first
        val descriptor = plugin.getDescriptor()

        assertEquals(2, descriptor.version)
        assertTrue(descriptor.groups.flatMap { it.methods }.any { it.name == "sunmi.nfc.getAntennaLocations" })
        assertTrue(descriptor.groups.flatMap { it.events }.any { it.name == "sunmi.nfc.antennaLocationsChanged" })
        assertTrue(descriptor.groups.flatMap { it.methods }.any { it.name == "sunmi.nfc.switchModule" })
        assertTrue(descriptor.groups.flatMap { it.events }.any { it.name == "sunmi.nfc.modulesChanged" })
    }

    @Test
    fun `module callback publishes legacy event and deduplicated full snapshots`() {
        val sdk = FakeSdk()
        val environment = FakeEnvironment()
        val (plugin, events) = plugin(sdk, environment)
        plugin.initialize(FakePluginContext(context))

        sdk.completeInitialization(true)
        sdk.emit(listOf(module("HOST", type = 73, selected = true)))

        val legacy = events.events.single { it.first == "sunmi.nfc.modulesChanged" }
        assertEquals("HOST", JSONObject(legacy.second).getJSONArray("modules").getJSONObject(0).getString("sn"))
        val readyEvents = events.events.filter { (name, json) ->
            name == "sunmi.nfc.antennaLocationsChanged" && JSONObject(json).getString("state") == "ready"
        }
        assertEquals(1, readyEvents.size)
        val module = JSONObject(readyEvents.single().second).getJSONArray("modules").getJSONObject(0)
        assertEquals(73, module.getInt("sdkType"))
        assertTrue(module.getBoolean("selected"))
        assertEquals("builtin", module.getString("reader"))

        environment.fireChanged()
        environment.fireChanged()
        val readyAfterUnchangedNotifications = events.events.count { (name, json) ->
            name == "sunmi.nfc.antennaLocationsChanged" && JSONObject(json).getString("state") == "ready"
        }
        assertEquals(1, readyAfterUnchangedNotifications)

        environment.settings = environment.settings.copy(controller = NfcController.EXTERNAL)
        environment.fireChanged()
        val readyAfterEnvironmentChange = events.events.filter { (name, json) ->
            name == "sunmi.nfc.antennaLocationsChanged" && JSONObject(json).getString("state") == "ready"
        }
        assertEquals(2, readyAfterEnvironmentChange.size)
        assertEquals("external", JSONObject(readyAfterEnvironmentChange.last().second).getString("controller"))
    }

    @Test
    fun `null initial list becomes ready with no modules`() = runBlocking {
        val sdk = FakeSdk()
        val (plugin, _) = plugin(sdk, FakeEnvironment())
        plugin.initialize(FakePluginContext(context))
        sdk.completeInitialization(true)
        sdk.emit(null)

        val result = plugin.execute("sunmi.nfc.getAntennaLocations", "{}") as CommandResult.Success
        val json = JSONObject(result.body.orEmpty())
        assertEquals("ready", json.getString("state"))
        assertEquals(0, json.getJSONArray("modules").length())
    }

    @Test
    fun `failed sdk initialization becomes unavailable`() = runBlocking {
        val sdk = FakeSdk()
        val (plugin, _) = plugin(sdk, FakeEnvironment())
        plugin.initialize(FakePluginContext(context))
        sdk.completeInitialization(false)

        val result = plugin.execute("sunmi.nfc.getAntennaLocations", "{}") as CommandResult.Success
        assertEquals("unavailable", JSONObject(result.body.orEmpty()).getString("state"))
        assertEquals(0, sdk.registerCount)
    }

    @Test
    fun `dispose unregisters once and stops environment idempotently`() {
        val sdk = FakeSdk()
        val environment = FakeEnvironment()
        val (plugin, _) = plugin(sdk, environment)
        plugin.initialize(FakePluginContext(context))
        sdk.completeInitialization(true)

        plugin.dispose()
        plugin.dispose()

        assertEquals(1, sdk.unregisterCount)
        assertTrue(environment.stopCount >= 1)
        assertFalse(environment.started)
    }

    @Test
    fun `post-dispose getter cannot expose stale ready modules`() = runBlocking {
        val sdk = FakeSdk()
        val (plugin, _) = plugin(sdk, FakeEnvironment())
        plugin.initialize(FakePluginContext(context))
        sdk.completeInitialization(true)
        sdk.emit(listOf(module("HOST", 0, true)))

        plugin.dispose()

        val result = plugin.execute("sunmi.nfc.getAntennaLocations", "{}") as CommandResult.Success
        val snapshot = JSONObject(result.body.orEmpty())
        assertEquals("unavailable", snapshot.getString("state"))
        assertEquals(0, snapshot.getJSONArray("modules").length())
    }

    @Test
    fun `late initialization callback cannot register after disposal`() {
        val sdk = FakeSdk()
        val environment = FakeEnvironment()
        val (plugin, _) = plugin(sdk, environment)
        plugin.initialize(FakePluginContext(context))

        plugin.dispose()
        sdk.completeInitialization(true)

        assertEquals(0, sdk.registerCount)
        assertEquals(0, sdk.unregisterCount)
        assertFalse(environment.started)
    }

    @Test
    fun `unresolved host serial retries on schedule without parallel getter or module resolutions`() = runBlocking {
        val sdk = FakeSdk()
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val answers = ArrayDeque(listOf<String?>(null, null, null, "HOST"))
        var resolverCalls = 0
        val (plugin, events) = plugin(
            sdk,
            FakeEnvironment(),
            resolver = TrustedHostSerialResolver {
                resolverCalls++
                answers.removeFirstOrNull()
            },
            backgroundDispatcher = dispatcher
        )
        plugin.initialize(FakePluginContext(context))
        scheduler.runCurrent()
        assertEquals(1, resolverCalls)
        sdk.completeInitialization(true)
        sdk.emit(listOf(module("HOST", type = 4, selected = true)))

        val initial = plugin.execute("sunmi.nfc.getAntennaLocations", "{}") as CommandResult.Success
        assertEquals(
            "unknown",
            JSONObject(initial.body.orEmpty()).getJSONArray("modules").getJSONObject(0).getString("reader")
        )
        scheduler.runCurrent()
        assertEquals(1, resolverCalls)

        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals(2, resolverCalls)
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
        assertEquals(3, resolverCalls)
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        assertEquals(4, resolverCalls)

        val snapshots = events.events
            .filter { it.first == "sunmi.nfc.antennaLocationsChanged" }
            .map { JSONObject(it.second) }
        assertTrue(snapshots.any {
            val modules = it.getJSONArray("modules")
            modules.length() == 1 && modules.getJSONObject(0).getString("reader") == "unknown"
        })
        assertEquals(
            "builtin",
            snapshots.last().getJSONArray("modules").getJSONObject(0).getString("reader")
        )
        plugin.dispose()
    }

    @Test
    fun `reinitialization ignores queued callbacks from the old registered listener`() = runBlocking {
        val sdk = FakeSdk()
        val serialDispatcher = QueuedSerialDispatcher()
        val (plugin, _) = plugin(
            sdk,
            FakeEnvironment(),
            serialDispatcher = serialDispatcher
        )

        plugin.initialize(FakePluginContext(context))
        sdk.completeInitialization(index = 0, success = true)
        serialDispatcher.runAll()
        assertEquals(1, sdk.registerCount)

        sdk.emitToRegisteredListener(0, listOf(module("OLD", type = 1, selected = true)))
        plugin.initialize(FakePluginContext(context))
        assertEquals(1, sdk.unregisterCount)
        serialDispatcher.runAll()

        sdk.completeInitialization(index = 1, success = true)
        serialDispatcher.runAll()
        sdk.emitToRegisteredListener(1, listOf(module("NEW", type = 2, selected = true)))
        serialDispatcher.runAll()

        val result = plugin.execute("sunmi.nfc.getAntennaLocations", "{}") as CommandResult.Success
        val modules = JSONObject(result.body.orEmpty()).getJSONArray("modules")
        assertEquals(1, modules.length())
        assertEquals("NEW", modules.getJSONObject(0).getString("sn"))
        assertEquals(2, sdk.registerCount)
        assertEquals(1, sdk.unregisterCount)
        plugin.dispose()
    }

    @Test
    fun `switch command preserves android id fallback without using it for identity`() = runBlocking {
        val sdk = FakeSdk()
        val (plugin, _) = plugin(sdk, FakeEnvironment(), hostSn = null)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "COMPAT-ID")

        val result = plugin.execute("sunmi.nfc.switchModule", "{}")

        assertTrue(result is CommandResult.Success)
        assertEquals(listOf("COMPAT-ID"), sdk.switchedSerials)
    }

    @Test
    fun `trusted resolver rejects json null string null and unknown and propagates cancellation`() = runBlocking {
        listOf("null", "\"null\"", "\"unknown\"").forEach { resultValue ->
            val invalidContext = FakePluginContext(
                context,
                capabilities = setOf("sunmi.tms.device"),
                executeBlock = { _, _ -> CommandResult.Success("""{"result":$resultValue}""") }
            )
            assertNull(AndroidTrustedHostSerialResolver.resolve(invalidContext))
        }

        val cancelledContext = FakePluginContext(
            context,
            capabilities = setOf("sunmi.tms.device"),
            executeBlock = { _, _ -> throw CancellationException("cancel") }
        )
        assertFailsWith<CancellationException> {
            AndroidTrustedHostSerialResolver.resolve(cancelledContext)
        }
        Unit
    }

    private fun plugin(
        sdk: FakeSdk,
        environment: FakeEnvironment,
        hostSn: String? = "HOST",
        resolver: TrustedHostSerialResolver = TrustedHostSerialResolver { hostSn },
        serialDispatcher: SerialDispatcher = SerialDispatcher { it() },
        backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined
    ): Pair<SunmiNfcPlugin, RecordingEvents> {
        val events = RecordingEvents()
        val plugin = SunmiNfcPlugin(
            context = context,
            sdk = sdk,
            environment = environment,
            hostSerialResolver = resolver,
            serialDispatcher = serialDispatcher,
            backgroundDispatcher = backgroundDispatcher
        )
        plugin.setEventCallback(events)
        return plugin to events
    }

    private fun module(sn: String, type: Int, selected: Boolean): Nfc = Nfc().also {
        it.sn = sn
        it.type = type
        it.isSelect = selected
        it.waterMarkAlpha = 25
    }

    private class FakeSdk : NfcSdk {
        private val initCallbacks = mutableListOf<(Boolean) -> Unit>()
        private val registeredListeners = mutableListOf<(List<Nfc>?) -> Unit>()
        var registerCount = 0
        var unregisterCount = 0
        val switchedSerials = mutableListOf<String>()

        override fun init(context: Context, callback: (Boolean) -> Unit) {
            initCallbacks += callback
        }

        override fun registerListener(onChanged: (List<Nfc>?) -> Unit): NfcListenerRegistration {
            registerCount++
            registeredListeners += onChanged
            var registered = true
            return NfcListenerRegistration {
                if (registered) {
                    registered = false
                    unregisterCount++
                }
            }
        }

        override fun switchNfc(sn: String) {
            switchedSerials += sn
        }

        override fun setWatermarkAlpha(alpha: Int) = Unit

        fun completeInitialization(success: Boolean, index: Int = initCallbacks.lastIndex) {
            assertNotNull(initCallbacks.getOrNull(index)).invoke(success)
        }

        fun emit(modules: List<Nfc>?) {
            registeredListeners.lastOrNull()?.invoke(modules)
        }

        fun emitToRegisteredListener(index: Int, modules: List<Nfc>?) {
            assertNotNull(registeredListeners.getOrNull(index)).invoke(modules)
        }
    }

    private class QueuedSerialDispatcher : SerialDispatcher {
        private val blocks = ArrayDeque<() -> Unit>()

        override fun post(block: () -> Unit) {
            blocks.addLast(block)
        }

        fun runAll() {
            while (blocks.isNotEmpty()) blocks.removeFirst().invoke()
        }
    }

    private class FakeEnvironment : NfcAntennaEnvironment {
        var settings = SettingsSnapshot(
            readable = true,
            locationJson = """[{"posX":"1200","posY":"470","width":"200","height":"200"}]""",
            controller = NfcController.BUILTIN,
            externalDisplayInfoJson = """{"sn":"EXT","nfc_type":0}"""
        )
        var display: DisplaySnapshot = DisplaySnapshot.Available(
            DisplayMetricsSnapshot(0, 1920, 1080, 1920, 1080, 0)
        )
        var started = false
        var stopCount = 0
        private var changed: (() -> Unit)? = null

        override fun readSettings() = settings
        override fun readDisplay() = display

        override fun start(onChanged: () -> Unit) {
            started = true
            changed = onChanged
        }

        override fun stop() {
            stopCount++
            started = false
            changed = null
        }

        fun fireChanged() {
            changed?.invoke()
        }
    }

    private class RecordingEvents : HalPluginEventCallback {
        val events = mutableListOf<Pair<String, String>>()
        override fun onEvent(eventName: String, jsonData: String) {
            events += eventName to jsonData
        }
        override fun onError(deviceType: String, code: Int, message: String) = Unit
    }

    private class FakePluginContext(
        override val applicationContext: Context,
        private val capabilities: Set<String> = emptySet(),
        private val executeBlock: suspend (String, String) -> CommandResult = { method, _ ->
            CommandResult.unsupportedMethod(method)
        }
    ) : PluginContext {
        override suspend fun execute(method: String, params: String): CommandResult =
            executeBlock(method, params)
        override fun getAvailableCapabilities(): List<String> = capabilities.toList()
        override fun hasCapability(capability: String): Boolean = capability in capabilities
        override fun emitEvent(eventName: String, jsonData: String) = Unit
        override fun onEvent(pattern: String, callback: (String, String) -> Unit) = Unit
    }
}
