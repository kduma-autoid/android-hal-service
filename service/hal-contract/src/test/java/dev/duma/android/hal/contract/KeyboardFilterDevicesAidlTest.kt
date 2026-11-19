package dev.duma.android.hal.contract

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Keyboard device lists crossing AIDL, including bundles and hosts of different ages. */
class KeyboardFilterDevicesAidlTest {

    private val devices = listOf(KeyboardDeviceId(0x1A86, 0x7523), KeyboardDeviceId(0x324F, 0x0113))

    @BeforeEach
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @AfterEach
    fun unmockLog() = unmockkStatic(Log::class)

    @Test
    fun `adapter decodes the list the bundle encodes`() {
        val json = Json.encodeToString(devices)
        val binder = mockk<IHardwarePlugin> { every { keyboardFilterDevicesJson } returns json }
        assertEquals(devices, AidlPluginAdapter(binder).getKeyboardFilterDevices())
    }

    @Test
    fun `bundle without the transaction yields no devices`() {
        // An unhandled transaction leaves the reply empty, which reads back as null.
        val silent = mockk<IHardwarePlugin> { every { keyboardFilterDevicesJson } returns null }
        assertEquals(emptyList(), AidlPluginAdapter(silent).getKeyboardFilterDevices())

        val throwing = mockk<IHardwarePlugin> { every { keyboardFilterDevicesJson } throws IllegalStateException("binder died") }
        assertEquals(emptyList(), AidlPluginAdapter(throwing).getKeyboardFilterDevices())
    }

    @Test
    fun `malformed or empty payload yields no devices`() {
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardDevices(""))
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardDevices("{not json"))
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardDevices("""[{"vendorId":"x"}]"""))
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardDevices("[]"))
    }

    @Test
    fun `plugins without keyboard devices report none by default`() {
        val plugin = object : BaseHalPlugin() {
            override val pluginId = "p"
            override val version = 1
            override fun isSupported() = true
            override fun getCapabilities() = emptyList<String>()
            override fun getDescriptor() = PluginDescriptor.withFlatLists("p", "p", 1, capabilities = emptyList())
            override fun initialize(pluginContext: PluginContext) {}
            override fun setEventCallback(callback: HalPluginEventCallback?) {}
            override suspend fun onExecute(method: String, params: String) = CommandResult.unsupportedMethod(method)
        }
        assertEquals(emptyList(), plugin.getKeyboardFilterDevices())
    }
}
