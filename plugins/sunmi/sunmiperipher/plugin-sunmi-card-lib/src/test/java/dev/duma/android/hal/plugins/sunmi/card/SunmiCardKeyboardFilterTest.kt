package dev.duma.android.hal.plugins.sunmi.card

import dev.duma.android.hal.contract.KeyboardDeviceId
import dev.duma.android.hal.contract.allMethods
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SunmiCardKeyboardFilterTest {

    @Test
    fun readerRegistersItsUsbIdWithTheKeyboardFilter() {
        assertEquals(listOf(KeyboardDeviceId(0x1A86, 0x7523)), SunmiCardPlugin().getKeyboardFilterDevices())
    }

    @Test
    fun inputDeviceDiagnosticsExistOnlyInDevelopmentBuilds() {
        val methods = SunmiCardPlugin().getDescriptor().allMethods.map { it.name }
        assertEquals(BuildConfig.WITH_EXPERIMENTAL, "sunmi.card.listInputDevices" in methods)
    }
}
