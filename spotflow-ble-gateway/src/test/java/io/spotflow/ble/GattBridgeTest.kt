package io.spotflow.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.verify
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.protocol.MessageType
import io.spotflow.ble.transport.FakePeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The public native adapter and gateway, with the host supplying a prepared GATT. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class GattBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val peripheral = FakePeripheral()
    private val address = peripheral.gatt.device.address
    private val bridge = GattBridge()

    private fun attachTest(test: suspend TestScope.(SpotflowGateway, FakePlatform) -> Unit) = runTest {
        val platform = FakePlatform()
        val gateway = SpotflowGateway(context, GatewayOptions.DEFAULT, platform, StandardTestDispatcher(testScheduler))
        try {
            test(gateway, platform)
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    private fun connected() {
        bridge.callback.onConnectionStateChange(peripheral.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }

    private fun TestScope.forwardAll() {
        runCurrent()
        while (peripheral.deliver(bridge.callback)) runCurrent()
    }

    @Test
    fun `the host prepares the GATT and the gateway relays it in protocol order`() = attachTest { gateway, platform ->
        connected()
        bridge.callback.onMtuChanged(peripheral.gatt, 247, BluetoothGatt.GATT_SUCCESS)
        val link = bridge.link(peripheral.gatt)
        assertEquals(244, link.maxWritePayloadBytes)
        verify(exactly = 0) { peripheral.gatt.readCharacteristic(any()) }

        val attachment = gateway.attach(link)
        forwardAll()

        assertEquals(address, attachment.address)
        assertEquals(AttachmentState.Ready("attached-device"), attachment.state.value)
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, peripheral.cccdWrites.single())
        assertTrue(platform.uplink("attached-device").publishedTo("ingest-cbor", "meta".toByteArray()))

        FrameCodec.fragment(MessageType.TELEMETRY, byteArrayOf(4, 5, 6), seq = 1, frameSize = 244).forEach {
            peripheral.notify(bridge.callback, peripheral.tx, it)
        }
        runCurrent()
        assertTrue(platform.uplink("attached-device").publishedTo("ingest-cbor", byteArrayOf(4, 5, 6)))
        verify(exactly = 0) { peripheral.gatt.discoverServices() }
        verify(exactly = 0) { peripheral.gatt.requestMtu(any()) }
        verify(exactly = 0) { peripheral.gatt.readRemoteRssi() }

        attachment.detach()
        forwardAll()
        assertEquals(AttachmentEnd.Reason.DETACHED, attachment.awaitEnd().reason)
        assertArrayEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, peripheral.cccdWrites.last())
        verify(exactly = 0) { peripheral.gatt.disconnect() }
        verify(exactly = 0) { peripheral.gatt.close() }
        assertTrue(gateway.diagnosticsSnapshot().devices.isEmpty())
    }

    @Test
    fun `the host's operations wait their turn inside runExclusive`() = attachTest { gateway, _ ->
        gateway.attach(bridge.link(peripheral.gatt))
        runCurrent() // the gateway's first characteristic read is outstanding

        var hostRan = false
        val hostOperation = launch { bridge.runExclusive { hostRan = true } }
        runCurrent()
        assertFalse(hostRan)

        forwardAll()
        assertTrue(hostRan)
        assertTrue(hostOperation.isCompleted)
    }

    @Test
    fun `a fresh link can be attached after reconnecting the same GATT`() = attachTest { gateway, platform ->
        connected()
        val first = gateway.attach(bridge.link(peripheral.gatt))
        forwardAll()

        bridge.callback.onConnectionStateChange(peripheral.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        assertEquals(AttachmentEnd.Reason.DISCONNECTED, first.awaitEnd().reason)

        connected()
        gateway.attach(bridge.link(peripheral.gatt))
        forwardAll()

        assertTrue(gateway.diagnosticsSnapshot().devices.single().attachmentState is AttachmentState.Ready)
        assertEquals(1, platform.uplink("attached-device").connectCount)
    }

    @Test
    fun `replacement on the same GATT queues teardown before the new session`() = attachTest { gateway, _ ->
        val first = gateway.attach(bridge.link(peripheral.gatt))
        forwardAll()

        gateway.attach(bridge.link(peripheral.gatt))
        forwardAll()

        assertEquals(AttachmentEnd.Reason.REPLACED, first.awaitEnd().reason)
        assertArrayEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, peripheral.cccdWrites[1])
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, peripheral.cccdWrites[2])
        assertTrue(gateway.diagnosticsSnapshot().devices.single().attachmentState is AttachmentState.Ready)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `the host can supply an MTU the bridge did not see`() {
        assertThrows(IllegalArgumentException::class.java) { bridge.link(peripheral.gatt, mtu = 22) }
        assertEquals(20, bridge.link(peripheral.gatt).maxWritePayloadBytes)
        assertEquals(244, bridge.link(peripheral.gatt, mtu = 247).maxWritePayloadBytes)
    }
}
