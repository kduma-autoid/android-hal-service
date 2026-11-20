package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.DriverException
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.Ss2222Driver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SunmiSs2222PluginLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val listeners = mutableListOf<Ss2222Driver.Listener>()
    private val drivers = mutableListOf<Ss2222Driver>()
    private val events = mutableListOf<Pair<String, String>>()
    private val callback = object : HalPluginEventCallback {
        override fun onEvent(eventName: String, jsonData: String) { events += eventName to jsonData }
        override fun onError(deviceType: String, code: Int, message: String) = Unit
    }
    private val plugin = SunmiSs2222ScannerPlugin(context) { _, listener ->
        listeners += listener
        mockk<Ss2222Driver>(relaxed = true).also {
            every { it.state } returns Ss2222Driver.State.IDLE
            every { it.deviceInfo } returns null
            drivers += it
        }
    }

    private fun pluginContext() = mockk<PluginContext>(relaxed = true).also {
        every { it.applicationContext } returns context
    }

    @Test fun supportDependsOnUsbHostNotCurrentAttachment() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST, false)
        assertFalse(plugin.isSupported())
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST, true)
        assertTrue(plugin.isSupported())
        val owner = pluginContext()
        plugin.initialize(owner)
        verify { owner.setPluginAvailable(false) }
        listeners.single().onStateChanged(Ss2222Driver.State.IDLE)
        verify { owner.setPluginAvailable(true) }
    }

    @Test fun barcodePreservesRawBytesAndNeverGuessesFormat() {
        plugin.setEventCallback(callback)
        plugin.initialize(pluginContext())
        listeners.single().onBarcode("  QR\n\r".encodeToByteArray())
        assertEquals(listOf("sunmi.scanner.ss2222.barcode", "barcodeScanner.onScan"), events.map { it.first })
        val body = Json.parseToJsonElement(events.first().second).jsonObject
        assertEquals("  QR\n", body.getValue("data").jsonPrimitive.content)
        assertEquals("UNKNOWN", body.getValue("format").jsonPrimitive.content)
        assertEquals("ICBRUgoN", body.getValue("rawData").jsonPrimitive.content)
        assertEquals(events[0].second, events[1].second)
    }

    @Test fun disposedAndReplacedDriverCallbacksCannotChangeNewGeneration() {
        plugin.setEventCallback(callback)
        val oldOwner = pluginContext()
        plugin.initialize(oldOwner)
        val old = listeners.single()
        plugin.dispose()
        verify { drivers.first().close() }
        plugin.setEventCallback(callback)
        val currentOwner = pluginContext()
        plugin.initialize(currentOwner)
        old.onStateChanged(Ss2222Driver.State.IDLE)
        old.onBarcode("old".encodeToByteArray())
        old.onError("old", "old")
        assertTrue(events.isEmpty())
        verify(exactly = 0) { oldOwner.setPluginAvailable(true) }
        listeners.last().onStateChanged(Ss2222Driver.State.IDLE)
        verify { currentOwner.setPluginAvailable(true) }
        assertEquals("sunmi.scanner.ss2222.stateChanged", events.single().first)
        verify { drivers.last().open() }
    }

    @Test fun diagnosticsAndNamedWritesShareValidationBeforeUsb() = runTest {
        plugin.initialize(pluginContext())
        val calls = listOf(
            "setConfig" to """{"symbologies":{"MICROPDF417":true}}""",
            "setParams" to """{"parameters":[{"id":138,"value":12}]}""",
            "rawCommand" to """{"opcode":198,"data":"FF F3 03 01"}""",
            "rawCommand" to """{"opcode":198,"data":"FF 03 01","status":8}""",
            "rawCommand" to """{"opcode":200}""",
        )
        for ((method, params) in calls) {
            val result = plugin.execute("sunmi.scanner.ss2222.$method", params) as CommandResult.Failure
            assertEquals(if (!BuildConfig.WITH_EXPERIMENTAL && method != "setConfig") "unsupported_method" else "bad_request", result.code)
        }
        coVerify(exactly = 0) { drivers.single().writeParams(any()) }
    }

    @Test fun controlRepliesWaitForDriverAndExposeTimeoutAsTimeout() = runTest {
        plugin.initialize(pluginContext())
        coEvery { drivers.single().start(true) } throws DriverException("command_timeout", "No ACK")
        val result = plugin.execute("barcodeScanner.trigger", "{}") as CommandResult.Failure
        assertEquals(CommandResult.ErrorType.TIMEOUT, result.type)
        assertEquals("command_timeout", result.code)
    }
}
