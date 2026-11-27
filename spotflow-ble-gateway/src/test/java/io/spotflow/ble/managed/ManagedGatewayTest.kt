package io.spotflow.ble.managed

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.verify
import io.spotflow.ble.DeviceError
import io.spotflow.ble.FakePlatform
import io.spotflow.ble.diagnostics.DeviceReport
import io.spotflow.ble.GatewayOptions
import io.spotflow.ble.SpotflowGateway
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.protocol.GattProfile
import io.spotflow.ble.protocol.MessageType
import io.spotflow.ble.AttachmentState
import io.spotflow.ble.endIfFinished
import io.spotflow.ble.transport.FakeLink
import io.spotflow.ble.transport.FakePeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The managed layer — discovery, the per-device reconnect loop — driving the real gateway over simulated
 * peripherals, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ManagedGatewayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val address = "AA:BB:CC:DD:EE:FF"

    private val platform = FakeManagedPlatform()

    private val corePlatform = FakePlatform()

    private fun managedTest(
        filter: DeviceFilter = DeviceFilter.AcceptAll,
        test: suspend TestScope.(managed: ManagedGateway) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = SpotflowGateway(context, GatewayOptions.DEFAULT, corePlatform, dispatcher)
        val managed = ManagedGateway(gateway, filter, platform, dispatcher)
        try {
            test(managed)
        } finally {
            managed.shutdown()
            advanceUntilIdle()
        }
    }

    /** Runs until nothing is left to do, delivering every callback the peripherals owe on the way. */
    private fun TestScope.settle() {
        runCurrent()
        do {
            var delivered = false
            for ((peripheralAddress, peripheral) in platform.peripherals) {
                val callback = platform.attempts(peripheralAddress).lastOrNull()?.callback ?: continue
                while (peripheral.deliver(callback)) {
                    delivered = true
                    runCurrent()
                }
            }
        } while (delivered)
    }

    private fun ManagedGateway.state(of: String = address): DeviceReport? = diagnosticsSnapshot().devices.firstOrNull { it.address == of }

    private val DeviceReport.ended get() = (attachmentState as? AttachmentState.Ended)?.result

    private fun TestScope.started(managed: ManagedGateway) {
        managed.startScanning()
        runCurrent()
    }

    // ---- discovery -------------------------------------------------------------------------------

    @Test
    fun `a failed scan is retried with backoff and reported meanwhile`() = managedTest { managed ->
        platform.failingScans = 2
        started(managed)

        assertEquals(ScanState.RETRYING, managed.scanStatus.value.state)
        assertNotNull(managed.scanStatus.value.error)

        advanceTimeBy(5_001); runCurrent() // the second attempt fails too
        assertEquals(ScanState.RETRYING, managed.scanStatus.value.state)
        advanceTimeBy(10_001); runCurrent() // the third works

        assertEquals(3, platform.scans.size)
        assertEquals(ScanStatus(ScanState.SCANNING), managed.scanStatus.value)
    }

    @Test
    fun `a missing scan permission is reported, not thrown, and discovery starts once it is granted`() =
        managedTest { managed ->
            platform.scanPermissionDenied = true
            started(managed) // does not throw

            assertEquals(ScanState.RETRYING, managed.scanStatus.value.state)
            assertTrue(managed.scanStatus.value.error.orEmpty().contains("BLUETOOTH_SCAN"))

            platform.scanPermissionDenied = false // the user granted it
            advanceTimeBy(5_001); runCurrent()
            assertEquals(ScanStatus(ScanState.SCANNING), managed.scanStatus.value)
            platform.advertise(address)
            settle()
            assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        }

    @Test
    fun `a scan that dies while running is restarted`() = managedTest { managed ->
        started(managed)

        platform.failRunningScan(IllegalStateException("BLE scan failed (error 3)"))
        runCurrent()
        assertEquals(ScanState.RETRYING, managed.scanStatus.value.state)

        advanceTimeBy(5_001); runCurrent()
        assertEquals(ScanState.SCANNING, managed.scanStatus.value.state)
        assertEquals(2, platform.scans.size)
    }

    @Test
    fun `the scan starts quick, then runs low-power and is restarted before Android throttles it`() =
        managedTest { managed ->
            started(managed)
            assertEquals(listOf(false), platform.scans)

            advanceTimeBy(2 * 60_000L + 1); runCurrent()
            assertEquals(listOf(false, true), platform.scans)

            advanceTimeBy(25 * 60_000L + 1); runCurrent()
            assertEquals("restarted inside Android's 30-minute limit", listOf(false, true, true), platform.scans)
            assertEquals(ScanState.SCANNING, managed.scanStatus.value.state)
        }

    @Test
    fun `scanning waits for Bluetooth and resumes when it is turned on`() = managedTest { managed ->
        platform.isBluetoothEnabled = false
        started(managed)
        assertEquals(ScanState.WAITING_FOR_BLUETOOTH, managed.scanStatus.value.state)
        assertTrue(platform.scans.isEmpty())

        platform.turnBluetoothOn()
        runCurrent()
        assertEquals(ScanState.SCANNING, managed.scanStatus.value.state)

        managed.stopScanning()
        assertEquals(ScanState.STOPPED, managed.scanStatus.value.state)
    }

    // ---- the reconnect loop ----------------------------------------------------------------------

    @Test
    fun `managed mode prepares the GATT before attaching it`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        runCurrent()
        val peripheral = platform.peripheral(address)
        val callback = platform.attempts(address).single().callback
        verify(exactly = 0) { peripheral.gatt.discoverServices() }

        assertTrue(peripheral.deliver(callback)) // connected
        runCurrent()
        verify(exactly = 1) { peripheral.gatt.discoverServices() }
        verify(exactly = 0) { peripheral.gatt.requestMtu(any()) }

        assertTrue(peripheral.deliver(callback)) // services discovered
        runCurrent()
        verify(exactly = 1) { peripheral.gatt.requestMtu(517) }
        verify(exactly = 0) { peripheral.gatt.readCharacteristic(any()) }
        assertNull(managed.state())

        settle() // MTU exchange, then the gateway handshake
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
    }

    @Test
    fun `a stalled MTU exchange closes the GATT without starting relay operations`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        runCurrent()
        val peripheral = platform.peripheral(address)
        val callback = platform.attempts(address).single().callback
        repeat(2) {
            assertTrue(peripheral.deliver(callback))
            runCurrent()
        }
        peripheral.swallow() // Android accepted the MTU request but never called back

        advanceTimeBy(10_000)
        runCurrent()
        verify(exactly = 1) { peripheral.gatt.close() }
        verify(exactly = 0) { peripheral.gatt.readCharacteristic(any()) }
        assertNull(managed.state())
    }

    @Test
    fun `a host attachment that starts during managed setup isn't replaced`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        runCurrent()
        val hosted = managed.attach(FakeLink())
        runCurrent()

        settle()
        verify(exactly = 1) { platform.peripheral(address).gatt.close() }
        verify(exactly = 0) { platform.peripheral(address).gatt.readCharacteristic(any()) }
        assertNull(hosted.endIfFinished())
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
    }

    @Test
    fun `a discovered device is relayed, and reconnected directly after a drop`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        settle()

        val first = platform.attempts(address).single()
        assertFalse("found by the scan, so in range: a direct connect", first.autoConnect)
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        assertEquals("test-device", managed.state()?.deviceId)

        val peripheral = platform.peripheral(address)
        FrameCodec.fragment(MessageType.TELEMETRY, byteArrayOf(1, 2, 3), seq = 0, frameSize = 244)
            .forEach { peripheral.notify(first.callback, peripheral.tx, it) }
        runCurrent()
        assertTrue(corePlatform.uplink("test-device").publishedTo("ingest-cbor", byteArrayOf(1, 2, 3)))

        platform.drop(address)
        settle()
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ended)
        verify { peripheral.gatt.close() }
        advanceTimeBy(501); settle()

        assertEquals(2, platform.attempts(address).size)
        assertFalse("a working session just dropped: try a direct connect first", platform.attempts(address)[1].autoConnect)
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        assertEquals("the cloud connection was kept across the drop", 1, corePlatform.uplink("test-device").connectCount)
    }

    @Test
    fun `after a failed attempt it waits with autoConnect, and a device that stays away is let go`() =
        managedTest { managed ->
            platform.outcomes(address) += FakeManagedPlatform.Outcome.FAILS
            platform.outcomes(address) += FakeManagedPlatform.Outcome.SILENT
            started(managed)
            platform.advertise(address)
            settle()

            assertNull("a failed connection was never attached", managed.state())

            advanceTimeBy(2_001); settle()
            assertEquals(2, platform.attempts(address).size)
            assertTrue("the device may be away: let Android connect when it is back", platform.attempts(address)[1].autoConnect)

            advanceTimeBy(10 * 60_000L + 1); settle()
            assertNull("gone for good: no longer listed", managed.state())

            platform.advertise(address) // it is back after all
            settle()
            assertEquals(3, platform.attempts(address).size)
            assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        }

    @Test
    fun `a rejected key is retried after a pause, with the credentials asked for anew`() = managedTest { managed ->
        corePlatform.uplink("test-device").authFail = true
        started(managed)
        platform.advertise(address)
        settle()

        assertEquals(DeviceError.Kind.AUTH_REJECTED, managed.state()?.ended?.error?.kind)
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ended)
        advanceTimeBy(30_000); settle()
        assertEquals("not hammering the broker", 1, corePlatform.uplink("test-device").connectAttempts)

        corePlatform.uplink("test-device").authFail = false // the key was fixed
        advanceTimeBy(30_001); settle()

        assertEquals(2, platform.attempts(address).size)
        assertEquals(true, managed.state()?.cloudConnected)
        assertNull(managed.state()?.ended)
    }

    @Test
    fun `retry ends the pause after a rejected key`() = managedTest { managed ->
        corePlatform.uplink("test-device").authFail = true
        started(managed)
        platform.advertise(address)
        settle()
        assertEquals(DeviceError.Kind.AUTH_REJECTED, managed.state()?.ended?.error?.kind)

        corePlatform.uplink("test-device").authFail = false
        managed.retry()
        settle() // no time passes

        assertEquals(true, managed.state()?.cloudConnected)
        assertNull(managed.state()?.ended)
    }

    @Test
    fun `a device the filter rejects is hidden, disconnected, left alone, and asked about again later`() {
        val accepted = HashSet<String>()
        managedTest(filter = { _, deviceId -> deviceId in accepted }) { managed ->
            started(managed)
            platform.advertise(address)
            settle()

            assertNull("not ours: not listed", managed.state())
            val peripheral = platform.peripheral(address)
            verify { peripheral.gatt.close() }
            verify(exactly = 1) { peripheral.gatt.readCharacteristic(peripheral.deviceId) }
            verify(exactly = 0) {
                peripheral.gatt.readCharacteristic(peripheral.sessionMetadata)
                peripheral.gatt.setCharacteristicNotification(peripheral.tx, true)
            }
            assertEquals(0, corePlatform.uplink("test-device").connectAttempts)

            platform.advertise(address) // it keeps advertising
            advanceTimeBy(60_000); settle()
            assertEquals("left alone for a while", 1, platform.attempts(address).size)

            accepted += "test-device" // e.g. the user who owns it signed in
            managed.retry()
            runCurrent()
            platform.advertise(address)
            settle()

            assertEquals(2, platform.attempts(address).size)
            assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        }
    }

    @Test
    fun `the relay uses the same device ID the managed filter accepted, without rereading`() {
        managedTest(filter = { address, deviceId ->
            platform.peripheral(address).values[GattProfile.DEVICE_ID] = "not-allowed".toByteArray()
            deviceId == "test-device"
        }) { managed ->
            started(managed)
            platform.advertise(address)
            settle()

            val peripheral = platform.peripheral(address)
            verify(exactly = 1) { peripheral.gatt.readCharacteristic(peripheral.deviceId) }
            assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
            assertEquals("test-device", managed.state()?.deviceId)
            assertEquals(1, corePlatform.uplink("test-device").connectAttempts)
            assertEquals(0, corePlatform.uplink("not-allowed").connectAttempts)
        }
    }

    @Test
    fun `a rejected device is looked at afresh after ten minutes by itself`() {
        val accepted = HashSet<String>()
        managedTest(filter = { _, deviceId -> deviceId in accepted }) { managed ->
            started(managed)
            platform.advertise(address)
            settle()
            accepted += "test-device"

            advanceTimeBy(10 * 60_000L + 1); settle()
            platform.advertise(address)
            settle()

            assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        }
    }

    @Test
    fun `a second device reporting the same id is refused and says why`() = managedTest { managed ->
        val impostor = "11:22:33:44:55:66"
        started(managed)
        platform.advertise(address)
        settle()
        platform.advertise(impostor)
        settle()

        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        assertEquals(DeviceError.Kind.DUPLICATE_DEVICE, managed.state(impostor)?.ended?.error?.kind)
    }

    @Test
    fun `turning Bluetooth off disconnects every device, and they come back with it`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        settle()
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)

        platform.turnBluetoothOff()
        corePlatform.turnBluetoothOff()
        settle()
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ended)
        assertEquals(ScanState.WAITING_FOR_BLUETOOTH, managed.scanStatus.value.state)
        verify { platform.peripheral(address).gatt.close() }
        advanceTimeBy(60_000); settle()
        assertEquals("nothing is attempted while Bluetooth is off", 1, platform.attempts(address).size)

        platform.turnBluetoothOn()
        runCurrent()
        platform.advertise(address)
        settle()
        assertEquals(ScanState.SCANNING, managed.scanStatus.value.state)
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
    }

    @Test
    fun `ignoring a device disconnects it and retry does not undo the exclusion`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        settle()

        managed.ignoreDevice(address)
        settle()
        assertNull(managed.state())
        verify { platform.peripheral(address).gatt.close() }

        managed.retry()
        platform.advertise(address) // it advertises again as soon as it is disconnected
        advanceTimeBy(60_000); settle()
        assertEquals("ignored means ignored", 1, platform.attempts(address).size)

        managed.allowDevice(address)
        platform.advertise(address)
        settle()
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
    }

    // ---- sharing the gateway with the host's own attachments ---------------------------------------

    @Test
    fun `a device the host attaches is the host's until it lets go`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        settle()

        val link = FakeLink()
        val hosted = managed.attach(link)
        settle()

        verify { platform.peripheral(address).gatt.close() }
        assertNull(hosted.endIfFinished())
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)

        platform.advertise(address)
        advanceTimeBy(60_000); settle()
        assertEquals("the host's now", 1, platform.attempts(address).size)
        assertNull(hosted.endIfFinished())

        link.drop() // the host's link went down, and the host does not attach it again
        settle()
        platform.advertise(address)
        settle()

        assertEquals("nobody relays it: the scan picks it up", 2, platform.attempts(address).size)
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
    }

    @Test
    fun `a device the host relays already is not connected by the scan`() = managedTest { managed ->
        val hosted = managed.attach(FakeLink())
        runCurrent()
        started(managed)

        platform.advertise(address)
        settle()

        assertTrue(platform.attempts(address).isEmpty())
        assertNull(hosted.endIfFinished())
    }

    @Test
    fun `ignoring an address leaves a host attachment alone and prevents managed takeover`() = managedTest { managed ->
        started(managed)
        val hosted = managed.attach(FakeLink())
        settle()

        managed.ignoreDevice(address)
        settle()
        assertNull(hosted.endIfFinished())
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
        hosted.detach()
        settle()

        platform.advertise(address)
        advanceTimeBy(60_000); settle()
        assertEquals(0, platform.attempts(address).size)

        managed.allowDevice(address)
        platform.advertise(address)
        settle()
        assertTrue(managed.state()?.attachmentState is AttachmentState.Ready)
    }

    // ---- the link --------------------------------------------------------------------------------

    @Test
    fun `a peripheral's too-short supervision timeout is countered`() = managedTest { managed ->
        started(managed)
        platform.advertise(address)
        settle()
        val callback = platform.attempts(address).single().callback as ManagedGattCallback
        val gatt = platform.peripheral(address).gatt

        callback.onConnectionUpdated(gatt, interval = 24, latency = 0, timeout = 42, status = BluetoothGatt.GATT_SUCCESS)
        callback.onConnectionUpdated(gatt, interval = 24, latency = 0, timeout = 500, status = BluetoothGatt.GATT_SUCCESS)

        verify(exactly = 1) { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
    }
}

/** A [ManagedPlatform] under the test's control, with a simulated peripheral behind every address. */
internal class FakeManagedPlatform : ManagedPlatform {
    override var isBluetoothEnabled = true

    private var onBluetoothOff: (() -> Unit)? = null
    private var onBluetoothOn: (() -> Unit)? = null

    override fun start(onBluetoothOff: () -> Unit, onBluetoothOn: () -> Unit) {
        this.onBluetoothOff = onBluetoothOff
        this.onBluetoothOn = onBluetoothOn
    }

    override fun stop() {
        onBluetoothOff = null
        onBluetoothOn = null
    }

    fun turnBluetoothOff() {
        isBluetoothEnabled = false
        onBluetoothOff?.invoke()
    }

    fun turnBluetoothOn() {
        isBluetoothEnabled = true
        onBluetoothOn?.invoke()
    }

    /** One entry per scan started: whether it was low-power. */
    val scans = ArrayList<Boolean>()

    /** The next this-many scans fail to start. */
    var failingScans = 0

    /** While set, scans fail as Android fails them without the scan permission. */
    var scanPermissionDenied = false

    private val advertisements = Channel<Any>(Channel.UNLIMITED)

    override fun scan(lowPower: Boolean): Flow<String> = flow {
        scans += lowPower
        if (scanPermissionDenied) {
            throw SecurityException("Need android.permission.BLUETOOTH_SCAN permission for AttributionSource")
        }
        if (failingScans > 0) {
            failingScans--
            throw IllegalStateException("BLE scan failed (error 2)")
        }
        for (event in advertisements) {
            if (event is Throwable) throw event
            emit(event as String)
        }
    }

    fun advertise(address: String) {
        advertisements.trySend(address)
    }

    /** Makes the scan that is running fail, as `onScanFailed` would. */
    fun failRunningScan(error: Throwable) {
        advertisements.trySend(error)
    }

    // ---- connections -----------------------------------------------------------------------------

    /** How a connection attempt goes. */
    enum class Outcome {
        CONNECTS,

        /** Android reports the attempt failed (status 133). */
        FAILS,

        /** Android says nothing: the device is away. */
        SILENT,
    }

    class Attempt(val autoConnect: Boolean, val callback: BluetoothGattCallback)

    val peripherals = LinkedHashMap<String, FakePeripheral>()
    private val attemptsByAddress = HashMap<String, MutableList<Attempt>>()
    private val outcomesByAddress = HashMap<String, ArrayDeque<Outcome>>()

    /** The peripheral at [address]: every one reports the device ID `test-device`. */
    fun peripheral(address: String): FakePeripheral = peripherals.getOrPut(address) {
        FakePeripheral(address).apply {
            values[GattProfile.DEVICE_ID] = "test-device".toByteArray()
            // Closing a GATT silences its callback: whatever it still owed is never delivered.
            every { gatt.close() } answers { owed.clear() }
        }
    }

    fun attempts(address: String): List<Attempt> = attemptsByAddress[address].orEmpty()

    /** How the next attempts to [address] go, in order; once used up, attempts connect. */
    fun outcomes(address: String): ArrayDeque<Outcome> = outcomesByAddress.getOrPut(address) { ArrayDeque() }

    override fun connectGatt(address: String, autoConnect: Boolean, callback: BluetoothGattCallback): BluetoothGatt? {
        if (!isBluetoothEnabled) return null
        val peripheral = peripheral(address)
        attemptsByAddress.getOrPut(address) { ArrayList() } += Attempt(autoConnect, callback)
        val gatt = peripheral.gatt
        when (outcomes(address).removeFirstOrNull() ?: Outcome.CONNECTS) {
            Outcome.CONNECTS -> peripheral.owed += {
                it.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            }
            Outcome.FAILS -> peripheral.owed += { it.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED) }
            Outcome.SILENT -> Unit
        }
        return gatt
    }

    /** The link to [address] drops. */
    fun drop(address: String) {
        val peripheral = peripheral(address)
        attempts(address).last().callback.onConnectionStateChange(peripheral.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
    }
}
