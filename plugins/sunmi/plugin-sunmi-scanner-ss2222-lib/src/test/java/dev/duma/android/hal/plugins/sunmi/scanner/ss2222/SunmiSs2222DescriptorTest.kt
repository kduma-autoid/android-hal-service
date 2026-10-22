package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.allMethods
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SunmiSs2222DescriptorTest {
    @Test fun `provider is available in both editions while diagnostics need development and super`() = runTest {
        val plugin = SunmiSs2222ScannerPlugin()
        val descriptor = plugin.getDescriptor()
        assertFalse(descriptor.experimental)
        assertEquals(110, descriptor.interfaces.single().priority)
        assertEquals("barcodeScanner", descriptor.interfaces.single().interfaceId)
        for (operation in listOf("readParam", "setParams", "rawCommand")) {
            val method = descriptor.allMethods.find { it.name == "${plugin.pluginId}.$operation" }
            if (BuildConfig.WITH_EXPERIMENTAL) {
                assertTrue(method!!.experimental)
                assertTrue(method.superRequired)
            } else {
                assertEquals(null, method)
                assertEquals("unsupported_method", (plugin.execute("${plugin.pluginId}.$operation", "{}") as CommandResult.Failure).code)
            }
        }
    }

    @Test fun `invalid writes are rejected even with no hardware and unknown interface methods stay unsupported`() = runTest {
        val plugin = SunmiSs2222ScannerPlugin()
        assertEquals("bad_request", (plugin.execute("${plugin.pluginId}.setConfig", """{"permanent":true}""") as CommandResult.Failure).code)
        assertEquals("unsupported_method", (plugin.execute("barcodeScanner.factoryReset", "{}") as CommandResult.Failure).code)
    }
}
