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

/** Keyboard filter rules crossing AIDL, including bundles and hosts of different ages. */
class KeyboardFilterRulesAidlTest {

    private val rules = listOf(KeyboardFilterRule.UsbDevice(0x324F, 0x0113), KeyboardFilterRule.EventSource(0x98123456.toInt()))

    @BeforeEach
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @AfterEach
    fun unmockLog() = unmockkStatic(Log::class)

    @Test
    fun `adapter decodes the list the bundle encodes`() {
        val json = Json.encodeToString(rules)
        val binder = mockk<IHardwarePlugin> { every { keyboardFilterRulesJson } returns json }
        assertEquals(rules, AidlPluginAdapter(binder).getKeyboardFilterRules())
    }

    @Test
    fun `each rule is tagged with its kind`() {
        assertEquals(
            """[{"type":"usb","vendorId":12879,"productId":275},{"type":"source","source":-1743637418}]""",
            Json.encodeToString(rules),
        )
    }

    @Test
    fun `a rule of an unknown kind makes the list unreadable rather than half-applied`() {
        assertEquals(
            emptyList(),
            AidlPluginAdapter.decodeKeyboardFilterRules("""[{"type":"usb","vendorId":1,"productId":2},{"type":"future","x":1}]"""),
        )
    }

    @Test
    fun `bundle without the transaction yields no devices`() {
        // An unhandled transaction leaves the reply empty, which reads back as null.
        val silent = mockk<IHardwarePlugin> { every { keyboardFilterRulesJson } returns null }
        assertEquals(emptyList(), AidlPluginAdapter(silent).getKeyboardFilterRules())

        val throwing = mockk<IHardwarePlugin> { every { keyboardFilterRulesJson } throws IllegalStateException("binder died") }
        assertEquals(emptyList(), AidlPluginAdapter(throwing).getKeyboardFilterRules())
    }

    @Test
    fun `malformed or empty payload yields no devices`() {
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardFilterRules(""))
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardFilterRules("{not json"))
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardFilterRules("""[{"type":"usb","vendorId":"x"}]"""))
        assertEquals(emptyList(), AidlPluginAdapter.decodeKeyboardFilterRules("[]"))
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
        assertEquals(emptyList(), plugin.getKeyboardFilterRules())
    }
}
