package io.spotflow.ble.protocol

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import io.mockk.every
import io.mockk.mockk
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.transport.FakeLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GattProfileTest {
    @Test
    fun `only a listed Spotflow UUID matches, including scan results without data`() {
        val result = mockk<ScanResult>()
        val record = mockk<ScanRecord>()
        every { result.scanRecord } returns record
        every { record.serviceUuids } returns listOf(ParcelUuid(GattProfile.SERVICE))
        assertTrue(GattProfile.advertisesService(result))

        every { record.serviceUuids } returns listOf(ParcelUuid(GattProfile.DEVICE_ID))
        assertFalse(GattProfile.advertisesService(result))
        every { record.serviceUuids } returns null
        assertFalse(GattProfile.advertisesService(result))
        every { result.scanRecord } returns null
        assertFalse(GattProfile.advertisesService(result))
    }

    @Test
    fun `probe trims the ID and rejects blank values`() = runTest {
        assertEquals("some-device", GattProfile.readDeviceId(FakeLink(deviceId = " some-device\n")))
        val error = runCatching { GattProfile.readDeviceId(FakeLink(deviceId = "  ")) }.exceptionOrNull()
        assertTrue(error is BleLinkException)
    }

    @Test
    fun `probe times out without starting notifications`() = runTest {
        val link = FakeLink().apply { gate = CompletableDeferred() }
        val error = runCatching { GattProfile.readDeviceId(link) }.exceptionOrNull()
        assertTrue(error is BleLinkException)
        assertEquals(10_000L, currentTime)
        assertEquals(listOf("read Device ID"), link.calls)
    }
}
