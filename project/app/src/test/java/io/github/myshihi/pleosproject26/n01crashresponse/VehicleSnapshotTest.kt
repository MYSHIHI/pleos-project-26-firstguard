package io.github.myshihi.pleosproject26.n01crashresponse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSnapshotTest {
    @Test fun zeroSpeedWithoutGearNeverPermitsVehicleControl() {
        val snapshot = VehicleSnapshot().apply { speedKph = 0f }
        assertFalse(snapshot.stopped)
        assertTrue(snapshot.stopText.contains("P 확인 대기"))
    }

    @Test fun standstillInDriveNeutralOrReverseStillWaitsForPark() {
        val snapshot = VehicleSnapshot().apply { speedKph = 0f }
        for (gear in listOf("D", "N", "R")) {
            snapshot.gear = gear
            assertFalse("Control must wait in $gear", snapshot.stopped)
        }
        snapshot.gear = "P"
        assertTrue(snapshot.stopped)
        snapshot.gear = null
        assertFalse(snapshot.stopped)
    }

    @Test fun missingSignalsNeverPermitVehicleControl() {
        assertFalse(VehicleSnapshot().stopped)
    }
}
