package dev.duma.android.hal.plugins.sunmi.nfc

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcAntennaSnapshotEngineTest {
    @Test
    fun `initial callback including null or empty establishes ready empty state`() {
        val engine = NfcAntennaSnapshotEngine()
        assertEquals(AntennaState.INITIALIZING, engine.snapshot().state)

        engine.markReady(emptyList())

        assertEquals(AntennaState.READY, engine.snapshot().state)
        assertEquals(emptyList(), engine.snapshot().modules)
    }

    @Test
    fun `trusted host and NFC external display serials assign locations without sdk type inference`() {
        val engine = configuredEngine()
        engine.updateTrustedHostSn("HOST")
        engine.markReady(
            listOf(
                CachedNfcModule("HOST", 91, true, 50),
                CachedNfcModule("EXT", 0, false, 10),
                CachedNfcModule("OTHER", 1, false, 10)
            )
        )

        val modules = engine.snapshot().modules
        assertEquals(NfcReader.BUILTIN, modules[0].reader)
        assertEquals(PixelRect(1200, 470, 200, 200), modules[0].location?.rect)
        assertEquals(NfcReader.EXTERNAL, modules[1].reader)
        assertNull(modules[1].location)
        assertEquals(LocationUnavailableReason.EXTERNAL_LOCATION_UNKNOWN, modules[1].locationUnavailableReason)
        assertEquals(NfcReader.UNKNOWN, modules[2].reader)
        assertEquals(LocationUnavailableReason.READER_IDENTITY_UNKNOWN, modules[2].locationUnavailableReason)
        assertEquals(91, modules[0].sdkType)
        assertEquals(true, modules[0].selected)
    }

    @Test
    fun `host external and duplicate serial collisions remain unknown`() {
        val engine = configuredEngine(externalJson = """{"sn":"COLLIDE","nfc_type":0}""")
        engine.updateTrustedHostSn("COLLIDE")
        engine.markReady(
            listOf(
                CachedNfcModule("COLLIDE", 0, true, 0),
                CachedNfcModule("DUP", 0, false, 0),
                CachedNfcModule("DUP", 0, false, 0)
            )
        )

        engine.snapshot().modules.forEach {
            assertEquals(NfcReader.UNKNOWN, it.reader)
            assertEquals(LocationUnavailableReason.READER_IDENTITY_UNKNOWN, it.locationUnavailableReason)
        }
    }

    @Test
    fun `controller and selection never infer reader identity`() {
        val engine = configuredEngine(controller = NfcController.BUILTIN)
        engine.markReady(listOf(CachedNfcModule("UNMATCHED", 0, true, 0)))

        val snapshot = engine.snapshot()
        assertEquals(NfcController.BUILTIN, snapshot.controller)
        assertEquals(NfcReader.UNKNOWN, snapshot.modules.single().reader)
    }

    @Test
    fun `settings and display failures produce exact location reasons for known builtin`() {
        val engine = configuredEngine()
        engine.updateTrustedHostSn("HOST")
        engine.markReady(listOf(CachedNfcModule("HOST", 0, true, 0)))

        engine.updateEnvironment(SettingsSnapshot.UNREADABLE, DisplaySnapshot.Unavailable)
        assertEquals(
            LocationUnavailableReason.SETTINGS_UNREADABLE,
            engine.snapshot().modules.single().locationUnavailableReason
        )

        engine.updateEnvironment(
            SettingsSnapshot(true, "[]", NfcController.UNKNOWN, null),
            DisplaySnapshot.Unavailable
        )
        assertEquals(
            LocationUnavailableReason.MISSING_LOCATION,
            engine.snapshot().modules.single().locationUnavailableReason
        )
    }

    @Test
    fun `markReady copies caller module list`() {
        val engine = configuredEngine()
        val source = mutableListOf(CachedNfcModule("ONE", 0, false, 0))
        engine.markReady(source)
        source += CachedNfcModule("TWO", 0, false, 0)

        assertEquals(listOf("ONE"), engine.snapshot().modules.map { it.sn })
    }

    private fun configuredEngine(
        externalJson: String? = """[{"sn":"EXT","nfc_type":0},{"sn":"NO_NFC","nfc_type":-1}]""",
        controller: NfcController = NfcController.EXTERNAL
    ) = NfcAntennaSnapshotEngine().apply {
        updateEnvironment(
            SettingsSnapshot(true, LOCATION, controller, externalJson),
            DisplaySnapshot.Available(DisplayMetricsSnapshot(0, 1920, 1080, 1920, 1080, 0))
        )
    }

    private companion object {
        const val LOCATION =
            """[{"posX":"1200","posY":"470","width":"200","height":"200"}]"""
    }
}
