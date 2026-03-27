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
        assertTrue(WsProtocol.matchesAnySubscription(subs, "scanner.barcode"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "rfid.tag"))
    }

    @Test
    fun `event matches wildcard subscription`() {
        val subs = setOf("rfid.*")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "rfid.tag"))
        assertTrue(WsProtocol.matchesAnySubscription(subs, "rfid.batch"))
        assertFalse(WsProtocol.matchesAnySubscription(subs, "scanner.barcode"))
    }

    @Test
    fun `event matches global wildcard`() {
        val subs = setOf("*")
        assertTrue(WsProtocol.matchesAnySubscription(subs, "anything.here"))
    }

    @Test
    fun `subscribe checks permissions`() {
        val tokenPermissions = listOf("printer", "scanner")
        val events = listOf("scanner.barcode", "rfid.*")

        val result = WsProtocol.validateSubscriptions(events, tokenPermissions)
        assertEquals(listOf("scanner.barcode"), result.allowed)
        assertEquals(listOf("rfid.*"), result.denied)
    }
}
