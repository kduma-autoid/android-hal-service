package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Ss2222KeyboardFilterServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearAccessibilitySetting() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            null,
        )
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    @Test
    fun onlyDownAndUpFromSs2222AreConsumed() {
        assertTrue(
            Ss2222KeyboardFilterService.isSs2222KeyEvent(
                KeyEvent.ACTION_DOWN,
                Ss2222KeyboardFilterService.USB_VENDOR_ID,
                Ss2222KeyboardFilterService.USB_PRODUCT_ID,
            ),
        )
        assertTrue(
            Ss2222KeyboardFilterService.isSs2222KeyEvent(
                KeyEvent.ACTION_UP,
                Ss2222KeyboardFilterService.USB_VENDOR_ID,
                Ss2222KeyboardFilterService.USB_PRODUCT_ID,
            ),
        )
        assertFalse(
            Ss2222KeyboardFilterService.isSs2222KeyEvent(
                KeyEvent.ACTION_MULTIPLE,
                Ss2222KeyboardFilterService.USB_VENDOR_ID,
                Ss2222KeyboardFilterService.USB_PRODUCT_ID,
            ),
        )
        assertFalse(
            Ss2222KeyboardFilterService.isSs2222KeyEvent(
                KeyEvent.ACTION_DOWN,
                0x1234,
                Ss2222KeyboardFilterService.USB_PRODUCT_ID,
            ),
        )
        assertFalse(
            Ss2222KeyboardFilterService.isSs2222KeyEvent(
                KeyEvent.ACTION_UP,
                Ss2222KeyboardFilterService.USB_VENDOR_ID,
                null,
            ),
        )
    }

    @Test
    fun isEnabledRequiresExactClassAndKnownApplicationPackage() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "dev.duma.android.hal.plugins.sunmi/Ss2222KeyboardFilterService",
        )
        assertFalse(Ss2222KeyboardFilterService.isEnabled(context))

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "dev.duma.android.hal.plugins.sunmi/${Ss2222KeyboardFilterService.SERVICE_CLASS_NAME}",
        )
        assertTrue(Ss2222KeyboardFilterService.isEnabled(context))
    }

    @Test
    fun isEnabledIsFalseWhenAccessibilityIsGloballyDisabled() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "dev.duma.android.hal.plugins.sunmi/${Ss2222KeyboardFilterService.SERVICE_CLASS_NAME}",
        )

        assertFalse(Ss2222KeyboardFilterService.isEnabled(context))
    }
}
