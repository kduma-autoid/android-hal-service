package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.PluginContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for [GenericPrinterPlugin] — vendor delegation, fallback behavior,
 * and priority ordering when multiple vendor printers are available.
 */
class GenericPrinterPluginTest {

    private val mockContext = mockk<PluginContext>(relaxed = true)
    private val plugin = GenericPrinterPlugin().also { it.initialize(mockContext) }

    @Test
    fun `delegates to sunmi when available`() = runTest {
        every { mockContext.hasCapability("sunmi.printer") } returns true
        coEvery { mockContext.execute("sunmi.printer.print", any()) } returns
            """{"jobId":"123","status":"queued"}"""

        val result = plugin.execute("printer.print", """{"template":"receipt"}""")
        assertTrue(result.contains("jobId"))
        coVerify { mockContext.execute("sunmi.printer.print", any()) }
    }

    @Test
    fun `returns error when no vendor available`() = runTest {
        every { mockContext.hasCapability(any()) } returns false

        val result = plugin.execute("printer.print", "{}")
        assertTrue(result.contains("no_printer_backend"))
    }

    @Test
    fun `tries vendors in priority order`() = runTest {
        every { mockContext.hasCapability("sunmi.printer") } returns false
        every { mockContext.hasCapability("zebra.printer") } returns true
        coEvery { mockContext.execute("zebra.printer.print", any()) } returns "{}"

        plugin.execute("printer.print", "{}")
        coVerify { mockContext.execute("zebra.printer.print", any()) }
    }
}
