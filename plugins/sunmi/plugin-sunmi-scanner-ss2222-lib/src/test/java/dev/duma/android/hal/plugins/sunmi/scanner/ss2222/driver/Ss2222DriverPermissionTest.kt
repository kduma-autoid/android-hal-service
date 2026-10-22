package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import kotlin.test.Test
import kotlin.test.assertEquals

class Ss2222DriverPermissionTest {
    @Test
    fun `permission denial is distinct from stale and wrong-device results`() {
        fun result(
            generation: Int = 7,
            deviceId: Int = 42,
            deviceName: String = "/dev/ss2222",
            scanner: Boolean = true,
            granted: Boolean = true,
            hasPermission: Boolean = true,
        ) = evaluatePermissionResult(
            expectedGeneration = 7,
            expectedDeviceId = 42,
            expectedDeviceName = "/dev/ss2222",
            resultGeneration = generation,
            resultDeviceId = deviceId,
            resultDeviceName = deviceName,
            isScanner = scanner,
            granted = granted,
            hasPermission = hasPermission,
        )

        assertEquals(PermissionDisposition.STALE, result(generation = 6))
        assertEquals(PermissionDisposition.STALE, result(deviceId = 43))
        assertEquals(PermissionDisposition.STALE, result(deviceName = "/dev/other"))
        assertEquals(PermissionDisposition.STALE, result(scanner = false))
        assertEquals(PermissionDisposition.DENIED, result(granted = false))
        assertEquals(PermissionDisposition.DENIED, result(hasPermission = false))
        assertEquals(PermissionDisposition.GRANTED, result())
    }

    @Test
    fun `second matching attachment is classified as ambiguous`() {
        assertEquals(ScannerMultiplicity.NONE, scannerMultiplicity(0))
        assertEquals(ScannerMultiplicity.SINGLE, scannerMultiplicity(1))
        assertEquals(ScannerMultiplicity.AMBIGUOUS, scannerMultiplicity(2))
    }
}
