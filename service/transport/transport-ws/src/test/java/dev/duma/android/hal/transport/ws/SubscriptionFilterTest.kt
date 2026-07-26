package dev.duma.android.hal.transport.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for subscription filtering — pattern matching and permission validation
 * for WebSocket event subscriptions.
 */
class SubscriptionFilterTest {
    @Test
    fun `event matches exact subscription`() {
        val subs = setOf("scanner.barcode")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "scanner.barcode", "sunmi.inner"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "rfid.tag", "sunmi.inner"))
    }

    @Test
    fun `event matches wildcard subscription`() {
        val subs = setOf("rfid.*")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "rfid.tag", "sunmi.rfid"))
        assertTrue(WsProtocol.matchesAnySubscription(subs, "rfid.batch", "sunmi.rfid"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "scanner.barcode", "sunmi.rfid"))
    }

    @Test
    fun `event matches global wildcard`() {
        val subs = setOf("*")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "anything.here", "any.plugin"))
    }

    @Test
    fun `source-filtered subscription matches only the given emitter`() {
        val subs = setOf("demo.notice@demo.beta")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "demo.notice", "demo.beta"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "demo.notice", "demo.alpha"))
    }

    @Test
    fun `source filter supports wildcards on both halves`() {
        val subs = setOf("scanner.*@sunmi.*")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "scanner.barcode", "sunmi.inner"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "scanner.barcode", "honeywell.ext"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "rfid.tag", "sunmi.rfid"))
    }

    @Test
    fun `subscribe checks permissions`() {
        val tokenPermissions = listOf("printer", "scanner")
        val events = listOf("scanner.barcode", "rfid.*")

        val result = WsProtocol.validateSubscriptions(events, tokenPermissions)
        assertEquals(listOf("scanner.barcode"), result.allowed)
        assertEquals(listOf("rfid.*"), result.denied)
    }

    @Test
    fun `source filter does not widen permission`() {
        // Permission is derived from the event-name half; the `@source` suffix must not grant access.
        val result = WsProtocol.validateSubscriptions(
            listOf("scanner.barcode@sunmi.inner", "rfid.tag@sunmi.rfid"),
            listOf("scanner"),
        )
        assertEquals(listOf("scanner.barcode@sunmi.inner"), result.allowed)
        assertEquals(listOf("rfid.tag@sunmi.rfid"), result.denied)
    }
}
