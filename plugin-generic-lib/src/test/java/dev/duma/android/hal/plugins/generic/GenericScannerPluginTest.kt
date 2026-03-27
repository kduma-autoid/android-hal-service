package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.PluginContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Tests for [GenericScannerPlugin] — vendor event transformation from
 * vendor-specific format (e.g. "sunmi.scanner.barcode") to unified format ("scanner.barcode").
 */
class GenericScannerPluginTest {

    @Test
    fun `transforms vendor event to unified`() = runTest {
        val mockContext = mockk<PluginContext>(relaxed = true)
        val plugin = GenericScannerPlugin()
        plugin.initialize(mockContext)

        // Capture the onEvent callback registered for "sunmi.scanner.*"
        val callbackSlot = slot<(String, String) -> Unit>()
        verify { mockContext.onEvent("sunmi.scanner.*", capture(callbackSlot)) }

        // Simulate a vendor event
        callbackSlot.captured("sunmi.scanner.barcode", """{"data":"590"}""")

        // Verify the unified event was emitted
        verify { mockContext.emitEvent("scanner.barcode", """{"data":"590"}""") }
    }
}
