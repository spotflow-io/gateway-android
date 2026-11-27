package io.spotflow.ble.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import io.mockk.verify
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.NotificationListener
import io.spotflow.ble.protocol.GattProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The native adapter against a [FakePeripheral]: which callback completes which operation, the GATT
 * queue, notifications, and following the connection and its MTU from the host's events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35]) // both GATT dialects: values through the characteristic (before Android 13), and as arguments
class GattClientTest {

    private val peripheral = FakePeripheral()
    private val client = GattClient().apply { bind(peripheral.gatt) }
    private val link by lazy { client.link() }

    /** Delivers every callback the peripheral owes, letting the client react to each. */
    private fun TestScope.answerAll() {
        runCurrent()
        while (peripheral.deliver(client.callback)) runCurrent()
    }

    /** The host negotiates the MTU; the client sees the exchange through its callback. */
    private fun prepared() {
        client.callback.onMtuChanged(peripheral.gatt, 247, BluetoothGatt.GATT_SUCCESS)
    }

    private class Recorder : NotificationListener {
        val values = ArrayList<ByteArray>()
        override fun onValue(value: ByteArray) {
            values += value
        }
        override fun onError(error: BleLinkException) = Unit
    }

    @Test
    fun `the frame size follows the MTU exchanges the client sees, per connection`() = runTest {
        assertEquals("the minimum, until an exchange is seen", 20, link.maxWritePayloadBytes)
        prepared()
        assertEquals(244, link.maxWritePayloadBytes)

        client.callback.onMtuChanged(peripheral.gatt, 517, BluetoothGatt.GATT_FAILURE)
        assertEquals("a failed exchange changes nothing", 244, link.maxWritePayloadBytes)

        client.callback.onConnectionStateChange(peripheral.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        client.callback.onConnectionStateChange(peripheral.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertEquals("a new connection starts from the minimum", 20, client.link().maxWritePayloadBytes)
    }

    @Test
    fun `once bound to its GATT, the client ignores another GATT's events, and refuses to serve it`() = runTest {
        prepared()
        val other = FakePeripheral("11:22:33:44:55:66")
        val listener = Recorder()
        launch { link.enableNotifications(GattProfile.TX_STREAM_REF, listener) }
        answerAll()

        client.callback.onMtuChanged(other.gatt, 23, BluetoothGatt.GATT_SUCCESS)
        other.notify(client.callback, other.tx, byteArrayOf(6))
        client.callback.onConnectionStateChange(other.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()

        assertEquals(244, link.maxWritePayloadBytes)
        assertTrue(listener.values.isEmpty())
        val read = async { link.read(GattProfile.DEVICE_ID_REF) }
        answerAll()
        assertEquals("still up", "attached-device", read.await().toString(Charsets.UTF_8))
        assertTrue(runCatching { client.bind(other.gatt) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a read is completed only by the result for the characteristic it asked for`() = runTest {
        prepared()
        val read = async { link.read(GattProfile.DEVICE_ID_REF) }
        runCurrent()

        // The host reads a characteristic of its own without going through runExclusive, and forwards
        // the result as the contract says. It must not be taken for the device ID.
        peripheral.read(client.callback, peripheral.hostCharacteristic, "not-the-device-id".toByteArray())
        runCurrent()
        assertFalse(read.isCompleted)

        answerAll()
        assertEquals("attached-device", read.await().toString(Charsets.UTF_8))
    }

    @Test
    fun `a failed read reports its GATT status`() = runTest {
        prepared()
        val read = async { runCatching { link.read(GattProfile.DEVICE_ID_REF) } }
        runCurrent()
        peripheral.swallow()

        peripheral.read(client.callback, peripheral.deviceId, ByteArray(0), BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION)
        runCurrent()

        val error = read.await().exceptionOrNull()
        assertTrue("was $error", error is BleLinkException)
        assertEquals(BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION, (error as BleLinkException).gattStatus)
    }

    @Test
    fun `each value is written on its own, after the previous one's callback`() = runTest {
        prepared()
        val values = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val write = launch { link.writeWithoutResponse(GattProfile.RX_STREAM_REF, values) }
        runCurrent()
        assertEquals(1, peripheral.rxWrites.size)

        peripheral.deliver(client.callback); runCurrent()
        assertEquals(2, peripheral.rxWrites.size)
        assertFalse(write.isCompleted)

        answerAll()
        assertTrue("returns once the last value is out", write.isCompleted)
        values.forEachIndexed { i, value -> assertArrayEquals(value, peripheral.rxWrites[i]) }
    }

    @Test
    fun `a write result for another characteristic does not complete the gateway's write`() = runTest {
        prepared()
        val write = launch { link.writeWithoutResponse(GattProfile.RX_STREAM_REF, listOf(byteArrayOf(1, 2, 3))) }
        runCurrent()

        client.callback.onCharacteristicWrite(peripheral.gatt, peripheral.hostCharacteristic, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertFalse(write.isCompleted)

        answerAll()
        assertTrue(write.isCompleted)
    }

    @Test
    fun `a descriptor write on another characteristic does not complete enabling notifications`() = runTest {
        prepared()
        val enabling = launch { link.enableNotifications(GattProfile.TX_STREAM_REF, Recorder()) }
        runCurrent()

        client.callback.onDescriptorWrite(
            peripheral.gatt, peripheral.hostCharacteristic.getDescriptor(GattProfile.CCCD), BluetoothGatt.GATT_SUCCESS,
        )
        runCurrent()
        assertFalse(enabling.isCompleted)

        answerAll()
        assertTrue(enabling.isCompleted)
    }

    @Test
    fun `enabling and disabling notifications writes the CCCD`() = runTest {
        prepared()
        launch { link.enableNotifications(GattProfile.TX_STREAM_REF, Recorder()) }
        answerAll()
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, peripheral.cccdWrites.single())

        val disabling = launch { link.disableNotifications(GattProfile.TX_STREAM_REF) }
        answerAll()

        assertTrue(disabling.isCompleted)
        assertArrayEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, peripheral.cccdWrites.last())
        verify { peripheral.gatt.setCharacteristicNotification(peripheral.tx, false) }
    }

    @Test
    fun `notifications reach the listener of their characteristic only`() = runTest {
        prepared()
        val listener = Recorder()
        launch { link.enableNotifications(GattProfile.TX_STREAM_REF, listener) }
        answerAll()

        peripheral.notify(client.callback, peripheral.hostCharacteristic, byteArrayOf(9))
        peripheral.notify(client.callback, peripheral.tx, byteArrayOf(1))
        peripheral.notify(client.callback, peripheral.tx, byteArrayOf(2))

        assertEquals(listOf(1, 2), listener.values.map { it.single().toInt() })
    }

    @Test
    fun `an older link turning notifications off leaves a newer link's listener in place`() = runTest {
        prepared()
        val old = link
        val newer = client.link()
        val listener = Recorder()
        launch { old.enableNotifications(GattProfile.TX_STREAM_REF, Recorder()) }
        launch { newer.enableNotifications(GattProfile.TX_STREAM_REF, listener) }
        answerAll()

        launch { old.disableNotifications(GattProfile.TX_STREAM_REF) }
        answerAll()
        peripheral.notify(client.callback, peripheral.tx, byteArrayOf(7))

        assertEquals(1, listener.values.size)
    }

    @Test
    fun `a disconnect fails the pending operation and ends the link`() = runTest {
        prepared()
        val read = async { runCatching { link.read(GattProfile.DEVICE_ID_REF) } }
        val ended = async { link.awaitDisconnect() }
        runCurrent()

        client.callback.onConnectionStateChange(peripheral.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()

        assertTrue(read.await().isFailure)
        assertTrue(ended.isCompleted)
        assertTrue("a link of a connection that is gone stays gone", runCatching { link.read(GattProfile.DEVICE_ID_REF) }.isFailure)
    }

    @Test
    fun `a link made after reconnecting is up again`() = runTest {
        prepared()
        client.callback.onConnectionStateChange(peripheral.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        client.callback.onConnectionStateChange(peripheral.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        val fresh = client.link()
        val read = async { fresh.read(GattProfile.DEVICE_ID_REF) }
        answerAll()

        assertEquals("attached-device", read.await().toString(Charsets.UTF_8))
    }

    // ---- the queue -----------------------------------------------------------------------------------

    @Test
    fun `an operation whose caller stopped waiting holds the queue until it is answered`() = runTest {
        prepared()
        val read = launch { link.read(GattProfile.DEVICE_ID_REF) }
        runCurrent()
        read.cancel() // e.g. it timed out
        runCurrent()

        var hostRan = false
        launch { client.exclusive { hostRan = true } }
        runCurrent()
        assertFalse("the read is still outstanding: Android would reject the host's operation as busy", hostRan)

        answerAll()
        assertTrue(hostRan)
    }

    @Test
    fun `operations take the queue in the order they are issued`() = runTest {
        prepared()
        val order = ArrayList<String>()
        launch { link.writeWithoutResponse(GattProfile.RX_STREAM_REF, listOf(byteArrayOf(1), byteArrayOf(2))); order += "write" }
        launch { client.exclusive { order += "host" } }
        launch { link.read(GattProfile.DEVICE_ID_REF); order += "read" }
        runCurrent()

        answerAll()

        assertEquals(listOf("write", "host", "read"), order)
    }

    @Test
    fun `a host operation outside the queue cannot complete the gateway's`() = runTest {
        prepared()
        val read = async { link.read(GattProfile.DEVICE_ID_REF) }
        runCurrent()
        peripheral.read(client.callback, peripheral.hostCharacteristic, byteArrayOf(1), BluetoothGatt.GATT_FAILURE)
        runCurrent()
        assertFalse(read.isCompleted)
        answerAll()
        assertTrue(read.isCompleted)
    }

    @Test
    fun `the host's GATT is never disconnected by the client`() = runTest {
        prepared()
        launch { link.enableNotifications(GattProfile.TX_STREAM_REF, Recorder()) }
        launch { link.disableNotifications(GattProfile.TX_STREAM_REF) }
        launch { link.read(GattProfile.DEVICE_ID_REF) }
        answerAll()

        verify(exactly = 0) { peripheral.gatt.disconnect() }
        verify(exactly = 0) { peripheral.gatt.close() }
    }
}
