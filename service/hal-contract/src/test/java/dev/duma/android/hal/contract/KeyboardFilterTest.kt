package dev.duma.android.hal.contract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardFilterTest {

    private val component = "${KeyboardFilter.HAL_PACKAGE}/${KeyboardFilter.SERVICE_CLASS_NAME}"

    @Test
    fun `enabled requires the global switch and the exact HAL component`() {
        assertTrue(KeyboardFilter.isEnabledIn(1, component))
        assertTrue(KeyboardFilter.isEnabledIn(1, "com.other/com.other.Service:$component"))
        assertTrue(
            KeyboardFilter.isEnabledIn(1, "${KeyboardFilter.HAL_PACKAGE}/.keyboardfilter.HalKeyboardFilterService"),
        )
    }

    @Test
    fun `disabled globally or missing means disabled`() {
        assertFalse(KeyboardFilter.isEnabledIn(0, component))
        assertFalse(KeyboardFilter.isEnabledIn(1, null))
        assertFalse(KeyboardFilter.isEnabledIn(1, ""))
    }

    @Test
    fun `other packages and similar class names do not count`() {
        assertFalse(KeyboardFilter.isEnabledIn(1, "dev.duma.android.hal.plugins.sunmi/${KeyboardFilter.SERVICE_CLASS_NAME}"))
        assertFalse(KeyboardFilter.isEnabledIn(1, "${KeyboardFilter.HAL_PACKAGE}/HalKeyboardFilterService"))
        assertFalse(KeyboardFilter.isEnabledIn(1, "${KeyboardFilter.HAL_PACKAGE}/${KeyboardFilter.SERVICE_CLASS_NAME}2"))
        assertFalse(KeyboardFilter.isEnabledIn(1, "${KeyboardFilter.HAL_PACKAGE}/"))
        assertFalse(KeyboardFilter.isEnabledIn(1, "/${KeyboardFilter.SERVICE_CLASS_NAME}"))
    }

    @Test
    fun `rules print what they match`() {
        assertEquals("USB 1A86:7523", KeyboardFilterRule.UsbDevice(0x1A86, 0x7523).toString())
        assertEquals("event source 0x98123456", KeyboardFilterRule.EventSource(0x98123456.toInt()).toString())
    }

    @Test
    fun `usb rule matches its device whatever the event source`() {
        val rule = KeyboardFilterRule.UsbDevice(0x1A86, 0x7523)
        assertTrue(rule.matches(KeyOrigin(0x1A86, 0x7523, source = 0x101)))
        assertTrue(rule.matches(KeyOrigin(0x1A86, 0x7523, source = 0x201)))
        assertFalse(rule.matches(KeyOrigin(0x1A86, 0x0001, source = 0x101)))
        assertFalse(rule.matches(KeyOrigin(0, 0, source = 0x101)))
    }

    @Test
    fun `source rule matches only its exact source`() {
        val rule = KeyboardFilterRule.EventSource(0x98123456.toInt())
        // Injected by the vendor service on Android's virtual keyboard (USB id 0000:0000).
        assertTrue(rule.matches(KeyOrigin(0, 0, source = 0x98123456.toInt())))
        // Other injected input, e.g. `adb shell input` (SOURCE_KEYBOARD), passes.
        assertFalse(rule.matches(KeyOrigin(0, 0, source = 0x101)))
        assertFalse(rule.matches(KeyOrigin(0, 0, source = 0x98123457.toInt())))
    }
}
