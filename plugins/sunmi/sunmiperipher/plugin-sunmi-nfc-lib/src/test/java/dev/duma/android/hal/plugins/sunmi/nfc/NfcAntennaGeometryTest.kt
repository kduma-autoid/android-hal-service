package dev.duma.android.hal.plugins.sunmi.nfc

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcAntennaGeometryTest {
    @Test
    fun `flex natural coordinates transform across every rotation`() {
        val expected = mapOf(
            0 to PixelRect(1200, 470, 200, 200),
            90 to PixelRect(470, 520, 200, 200),
            180 to PixelRect(520, 410, 200, 200),
            270 to PixelRect(410, 1200, 200, 200)
        )

        expected.forEach { (rotation, rect) ->
            val result = assertIs<LocationResolution.Resolved>(
                NfcAntennaGeometry.resolve(FLEX_LOCATION, display(1920, 1080, rotation))
            )
            assertEquals(rect, result.location.rect)
            assertFalse(result.location.estimated)
        }
    }

    @Test
    fun `cpad rotated source coordinates transform across every rotation`() {
        val expected = mapOf(
            0 to PixelRect(740, 1460, 460, 460),
            90 to PixelRect(1460, 0, 460, 460),
            180 to PixelRect(0, 0, 460, 460),
            270 to PixelRect(0, 740, 460, 460)
        )

        expected.forEach { (rotation, rect) ->
            val result = assertIs<LocationResolution.Resolved>(
                NfcAntennaGeometry.resolve(CPAD_LOCATION, display(1200, 1920, rotation))
            )
            assertEquals(rect, result.location.rect)
            assertFalse(result.location.estimated)
        }
    }

    @Test
    fun `nonsquare rectangle swaps dimensions during rotation`() {
        val result = assertIs<LocationResolution.Resolved>(
            NfcAntennaGeometry.resolve(
                """[{"posX":"100","posY":"200","width":"300","height":"100"}]""",
                display(1000, 2000, 90)
            )
        )

        assertEquals(PixelRect(200, 600, 100, 300), result.location.rect)
    }

    @Test
    fun `ambiguous source assumes natural and labels estimate`() {
        val result = assertIs<LocationResolution.Resolved>(
            NfcAntennaGeometry.resolve(
                """[{"posX":"10","posY":"20","width":"30","height":"40"}]""",
                display(1200, 1920, 0)
            )
        )

        assertEquals(PixelRect(10, 20, 30, 40), result.location.rect)
        assertTrue(result.location.estimated)
    }

    @Test
    fun `missing malformed negative and overflowing rectangles are rejected`() {
        assertReason(null, LocationUnavailableReason.MISSING_LOCATION)
        assertReason("[]", LocationUnavailableReason.MISSING_LOCATION)
        assertReason("not-json", LocationUnavailableReason.INVALID_LOCATION)
        assertReason(
            """[{"posX":-1,"posY":0,"width":1,"height":1}]""",
            LocationUnavailableReason.INVALID_LOCATION
        )
        assertReason(
            """[{"posX":2147483647,"posY":0,"width":1,"height":1}]""",
            LocationUnavailableReason.INVALID_LOCATION
        )
    }

    @Test
    fun `scaled display mismatch is explicitly unsupported`() {
        val result = assertIs<LocationResolution.Unavailable>(
            NfcAntennaGeometry.resolve(FLEX_LOCATION, DisplaySnapshot.UnsupportedMetrics)
        )
        assertEquals(LocationUnavailableReason.UNSUPPORTED_DISPLAY_METRICS, result.reason)
    }

    private fun assertReason(raw: String?, expected: LocationUnavailableReason) {
        val result = assertIs<LocationResolution.Unavailable>(
            NfcAntennaGeometry.resolve(raw, display(1920, 1080, 0))
        )
        assertEquals(expected, result.reason)
    }

    private fun display(naturalWidth: Int, naturalHeight: Int, rotation: Int): DisplaySnapshot.Available {
        val rotated = rotation == 90 || rotation == 270
        return DisplaySnapshot.Available(
            DisplayMetricsSnapshot(
                displayId = 0,
                naturalWidthPx = naturalWidth,
                naturalHeightPx = naturalHeight,
                displayWidthPx = if (rotated) naturalHeight else naturalWidth,
                displayHeightPx = if (rotated) naturalWidth else naturalHeight,
                rotationDegrees = rotation
            )
        )
    }

    private companion object {
        const val FLEX_LOCATION =
            """[{"posX":"1200","posY":"470","width":"200","height":"200"}]"""
        const val CPAD_LOCATION =
            """[{"posX":"1460","posY":"0","width":"460","height":"460"}]"""
    }
}
