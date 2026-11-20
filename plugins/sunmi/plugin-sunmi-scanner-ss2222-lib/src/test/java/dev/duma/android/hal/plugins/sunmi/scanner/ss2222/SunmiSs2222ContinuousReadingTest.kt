package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.DriverException
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.Ss2222Driver
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver.Ss2222Driver.State
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Read barcodes continuously" setting: the scanner reads in the background whenever idle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SunmiSs2222ContinuousReadingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var listener: Ss2222Driver.Listener
    @Volatile private var state = State.CONNECTING
    private var failStarts = 0

    /** Like the real driver: start/stop move the state and report it to the plugin. */
    private val driver = mockk<Ss2222Driver>(relaxed = true).also { d ->
        every { d.state } answers { state }
        every { d.deviceInfo } returns null
        coEvery { d.start(any()) } answers {
            if (failStarts > 0) {
                failStarts--
                throw DriverException("command_timeout", "No ACK")
            }
            move(State.SCANNING)
        }
        coEvery { d.stop() } answers { move(State.IDLE) }
    }

    private fun move(to: State) {
        state = to
        listener.onStateChanged(to)
    }

    private val plugin = SunmiSs2222ScannerPlugin(context) { _, l -> listener = l; driver }

    private fun connect(continuous: Boolean) {
        val owner = mockk<PluginContext>(relaxed = true).also {
            every { it.applicationContext } returns context
            every { it.getBooleanSetting("continuousReading", true) } returns continuous
        }
        plugin.initialize(owner)
        move(State.IDLE)
    }

    @Test fun `the setting is declared and on by default`() {
        val setting = plugin.getSettings().single()
        assertEquals("continuousReading", setting.key)
        assertTrue(setting.default)
    }

    @Test fun `a connected scanner starts reading continuously on its own`() = runBlocking {
        connect(continuous = true)
        coVerify(timeout = 2_000) { driver.start(singleShot = false) }
        val status = Json.parseToJsonElement((plugin.execute("sunmi.scanner.ss2222.getStatus", "{}") as CommandResult.Success).body!!).jsonObject
        assertEquals("true", status.getValue("continuous").jsonPrimitive.content)
        assertEquals("SCANNING", status.getValue("state").jsonPrimitive.content)
    }

    @Test fun `the driver hands the scanner back reading only while the setting is on`() {
        connect(continuous = true)
        io.mockk.verify { driver.keepScanningEnabled = true }
        plugin.onSettingChanged("continuousReading", false)
        io.mockk.verify { driver.keepScanningEnabled = false }
    }

    @Test fun `with the setting off the scanner stays idle`() = runBlocking {
        connect(continuous = false)
        Thread.sleep(300)
        coVerify(exactly = 0) { driver.start(any()) }
    }

    @Test fun `trigger pauses background reading and it resumes after the single read`() = runBlocking {
        connect(continuous = true)
        coVerify(timeout = 2_000) { driver.start(singleShot = false) }

        assertTrue(plugin.execute("barcodeScanner.trigger", "{}") is CommandResult.Success)
        coVerifyOrder {
            driver.start(singleShot = false)
            driver.stop()
            driver.start(singleShot = true)
        }
        Thread.sleep(200)
        coVerify(exactly = 1) { driver.start(singleShot = false) } // not while the single read waits

        move(State.IDLE) // the single read decoded a barcode and auto-stopped
        coVerify(timeout = 2_000, exactly = 2) { driver.start(singleShot = false) }
    }

    @Test fun `an idle-only write runs between background sessions`() = runBlocking {
        connect(continuous = true)
        coVerify(timeout = 2_000) { driver.start(singleShot = false) }
        coEvery { driver.writeParams(any()) } answers {
            check(state == State.IDLE) { "writeParams while $state" }
        }

        val result = plugin.execute("sunmi.scanner.ss2222.setConfig", """{"goodReadBeep":false}""")

        assertTrue(result.toString(), result is CommandResult.Success)
        coVerify(timeout = 2_000, exactly = 2) { driver.start(singleShot = false) }
    }

    @Test fun `turning the setting off stops the background session for good`() = runBlocking {
        connect(continuous = true)
        coVerify(timeout = 2_000) { driver.start(singleShot = false) }

        plugin.onSettingChanged("continuousReading", false)
        coVerify(timeout = 2_000) { driver.stop() }
        Thread.sleep(300)
        coVerify(exactly = 1) { driver.start(singleShot = false) }

        plugin.onSettingChanged("continuousReading", true)
        coVerify(timeout = 2_000, exactly = 2) { driver.start(singleShot = false) }
    }

    @Test fun `a failed start is retried after a back-off, not in a tight loop`() = runBlocking {
        failStarts = 1
        connect(continuous = true)
        coVerify(timeout = 2_000, exactly = 1) { driver.start(singleShot = false) }
        Thread.sleep(400)
        coVerify(exactly = 1) { driver.start(singleShot = false) } // still waiting out the back-off
        coVerify(timeout = 3_000, exactly = 2) { driver.start(singleShot = false) }
        assertEquals(State.SCANNING, state)
    }
}
