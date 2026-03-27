package dev.duma.android.hal.contract

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventBusPatternTest {
    @Test
    fun `exact match`() {
        assertTrue(EventBus.matchesPattern("scanner.barcode", "scanner.barcode"))
        assertFalse(EventBus.matchesPattern("scanner.barcode", "scanner.stop"))
    }

    @Test
    fun `wildcard prefix`() {
        assertTrue(EventBus.matchesPattern("rfid.*", "rfid.tag"))
        assertTrue(EventBus.matchesPattern("rfid.*", "rfid.batch"))
        assertFalse(EventBus.matchesPattern("rfid.*", "scanner.barcode"))
    }

    @Test
    fun `global wildcard`() {
        assertTrue(EventBus.matchesPattern("*", "rfid.tag"))
        assertTrue(EventBus.matchesPattern("*", "scanner.barcode"))
    }

    @Test
    fun `vendor prefix wildcard`() {
        assertTrue(EventBus.matchesPattern("sunmi.scanner.*", "sunmi.scanner.barcode"))
        assertFalse(EventBus.matchesPattern("sunmi.scanner.*", "zebra.scanner.barcode"))
    }

    @Test
    fun `pattern does not partial match`() {
        assertFalse(EventBus.matchesPattern("scan", "scanner.barcode"))
        assertFalse(EventBus.matchesPattern("scanner", "scanner.barcode"))
    }
}
