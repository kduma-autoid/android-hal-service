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
    fun `device id prints as hex usb id`() {
        assertEquals("1A86:7523", KeyboardDeviceId(0x1A86, 0x7523).toString())
    }
}
