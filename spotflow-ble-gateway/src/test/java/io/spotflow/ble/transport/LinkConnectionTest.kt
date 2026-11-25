package io.spotflow.ble.transport

import io.spotflow.ble.BleLinkException
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.protocol.Message
import io.spotflow.ble.protocol.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The protocol core over a [FakeLink]: framing, ACK/NACK, timing every call, and how a connection ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkConnectionTest {

    private val link = FakeLink()

    /** Like the gateway's scope, a supervisor: a call that fails is the caller's to hear about. */
    private val TestScope.callScope get() = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))

    private fun TestScope.connection() = LinkConnection(link.address, callScope, link)

    private suspend fun TestScope.prepared() = connection().also { it.prepare() }

    private suspend fun TestScope.streaming() = prepared().also { it.startStreaming() }

    @Test
    fun `reads the device's characteristics`() = runTest {
        val connection = prepared()

        assertEquals(ConnectionState.PREPARING, connection.state.value)
        assertEquals("test-device", connection.readDeviceId())
        assertEquals(1, connection.readCapabilities().major)
        assertArrayEquals("meta".toByteArray(), connection.readSessionMetadata())
        assertEquals(listOf("read Device ID", "read Capabilities", "read Session Metadata"), link.calls)
    }

    @Test
    fun `starting the stream enables notifications and reports ready`() = runTest {
        val connection = streaming()

        assertTrue(link.notifying)
        assertEquals(ConnectionState.STREAMING, connection.state.value)
    }

    // ---- framing -----------------------------------------------------------------------------------

    @Test
    fun `a message goes out as one write of all its frames, each the link's frame size at most`() = runTest {
        val connection = prepared()
        link.maxWritePayloadBytes = 20

        val payload = ByteArray(100) { it.toByte() }
        connection.sendDesiredConfiguration(payload)

        val frames = link.writes.single()
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= 20 })
        val reassembler = FrameCodec.Reassembler()
        assertEquals(Message(MessageType.DESIRED_CONFIGURATION, payload), frames.map { reassembler.onFragment(it) }.last())
    }

    @Test
    fun `the frame size is read for each message, so a later MTU exchange takes effect`() = runTest {
        val connection = prepared()
        link.maxWritePayloadBytes = 20
        connection.sendDesiredConfiguration(ByteArray(100))
        link.maxWritePayloadBytes = 244
        connection.sendDesiredConfiguration(ByteArray(100))

        assertTrue(link.writes[0].size > 1)
        assertEquals(1, link.writes[1].size)
    }

    @Test
    fun `frames stay within 20 to 512 bytes whatever the link reports`() = runTest {
        val connection = prepared()
        link.maxWritePayloadBytes = 600
        connection.sendArtifactResponse(ByteArray(2000))
        link.maxWritePayloadBytes = 0
        connection.sendArtifactResponse(ByteArray(100))

        assertEquals(512, link.writes[0].maxOf { it.size })
        assertEquals(20, link.writes[1].maxOf { it.size })
    }

    @Test
    fun `TX stream fragments are reassembled into messages`() = runTest {
        val connection = streaming()
        val received = async { connection.incoming.first() }
        runCurrent()

        val payload = ByteArray(60) { it.toByte() }
        FrameCodec.fragment(MessageType.TELEMETRY, payload, seq = 5, frameSize = 20).forEach(link::notify)
        runCurrent()

        assertEquals(Message(MessageType.TELEMETRY, payload), received.await())
    }

    // ---- timing --------------------------------------------------------------------------------------

    @Test
    fun `a read that times out fails the connection, and the call is left to settle`() = runTest {
        val connection = prepared()
        val gate = CompletableDeferred<Unit>().also { link.gate = it }
        val read = async { runCatching { connection.readDeviceId() } }

        advanceTimeBy(9_999); runCurrent()
        assertFalse(read.isCompleted)
        advanceTimeBy(2); runCurrent()

        assertTrue(read.await().exceptionOrNull() is IOException)
        assertEquals(ConnectionState.FAILED, connection.state.value)
        assertTrue(connection.failure is IOException)
        assertEquals("not cancelled: the adapter still runs it, in order", 0, link.cancelledCalls)

        gate.complete(Unit); runCurrent()
        assertTrue("and later calls are refused", runCatching { connection.readSessionMetadata() }.isFailure)
        assertEquals(1, link.calls.size)
    }

    @Test
    fun `a write gets 10 s plus 100 ms per frame`() = runTest {
        val connection = prepared()
        link.maxWritePayloadBytes = 20
        link.gate = CompletableDeferred()
        val payload = ByteArray(4096)
        val frames = FrameCodec.fragment(MessageType.ARTIFACT_RESPONSE, payload, seq = 0, frameSize = 20).size
        val budget = 10_000L + 100L * frames
        val write = async { runCatching { connection.sendArtifactResponse(payload) } }

        advanceTimeBy(budget - 1); runCurrent()
        assertFalse("about 240 frames at 20 bytes get about 34 s", write.isCompleted)
        advanceTimeBy(2); runCurrent()

        assertTrue(write.await().exceptionOrNull() is IOException)
        assertEquals(ConnectionState.FAILED, connection.state.value)
    }

    @Test
    fun `only one call is issued at a time`() = runTest {
        val connection = prepared()
        val gate = CompletableDeferred<Unit>().also { link.gate = it }
        launch { connection.sendDesiredConfiguration(byteArrayOf(1)) }
        launch { connection.sendArtifactResponse(byteArrayOf(2)) }
        launch { connection.readDeviceId() }
        runCurrent()
        assertEquals(1, link.submittedCalls)

        gate.complete(Unit); runCurrent()
        assertEquals(3, link.submittedCalls)
    }

    @Test
    fun `a call's time includes the wait behind the host's operations`() = runTest {
        val connection = prepared()
        val gate = CompletableDeferred<Unit>().also { link.gate = it } // the host holds its queue
        val read = async { runCatching { connection.readDeviceId() } }

        advanceTimeBy(8_000); runCurrent()
        gate.complete(Unit); runCurrent() // the host is done, the read goes through in time

        assertEquals("test-device", read.await().getOrNull())
        assertEquals(ConnectionState.PREPARING, connection.state.value)
    }

    // ---- how it ends ---------------------------------------------------------------------------------

    @Test
    fun `when the link drops, the pending call is no longer waited for`() = runTest {
        val connection = prepared()
        link.gate = CompletableDeferred()
        val read = async { runCatching { connection.readDeviceId() } }
        runCurrent()

        link.drop(); runCurrent()

        assertTrue(read.await().isFailure)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(ConnectionState.IDLE, connection.state.value)
        assertNull(connection.failure)
    }

    @Test
    fun `a link that drops while the stream is being started is not reported as ready`() = runTest {
        val connection = prepared()
        val gate = CompletableDeferred<Unit>().also { link.gate = it }
        val starting = async { runCatching { connection.startStreaming() } }
        runCurrent()

        link.drop()
        gate.complete(Unit)
        runCurrent()

        assertTrue(starting.await().isFailure)
        assertEquals("or the session would wait forever for an end it already missed", ConnectionState.IDLE, connection.state.value)
    }

    @Test
    fun `a subscription error fails the connection`() = runTest {
        val connection = streaming()

        link.listener!!.onError(BleLinkException("notifications stopped"))

        assertEquals(ConnectionState.FAILED, connection.state.value)
        assertEquals("notifications stopped", connection.failure?.message)
    }

    @Test
    fun `a GATT status that calls for pairing says so`() = runTest {
        val connection = prepared()
        link.failNext = BleLinkException("read failed", gattStatus = 5)

        val error = runCatching { connection.readDeviceId() }.exceptionOrNull()

        assertTrue(error is BleLinkException)
        assertEquals(5, (error as BleLinkException).gattStatus)
        assertTrue(error.message!!, error.message!!.contains("pair (bond) it with this phone"))
    }

    @Test
    fun `closing turns the stream off and leaves the link up`() = runTest {
        val connection = streaming()

        connection.close()

        assertFalse(link.notifying)
        assertEquals("disable", link.calls.last())
        assertEquals(ConnectionState.IDLE, connection.state.value)
    }

    @Test
    fun `closing waits at most 2 s for the stream to turn off, and leaves the write to run`() = runTest {
        val connection = streaming()
        val gate = CompletableDeferred<Unit>().also { link.gate = it }

        val closing = launch { connection.close() }
        advanceTimeBy(2_001); runCurrent()
        assertTrue("detaching must not hang on it", closing.isCompleted)
        assertTrue(link.notifying)

        gate.complete(Unit); runCurrent()
        assertFalse("the disable still ran", link.notifying)
        assertEquals(0, link.cancelledCalls)
    }

    @Test
    fun `closing turns the stream off even after a call timed out`() = runTest {
        val connection = streaming()
        val gate = CompletableDeferred<Unit>().also { link.gate = it }
        launch { runCatching { connection.readSessionMetadata() } }
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ConnectionState.FAILED, connection.state.value)

        launch { connection.close() }
        runCurrent()
        assertEquals("disable waits for the abandoned read", "read Session Metadata", link.calls.last())
        gate.complete(Unit); runCurrent()
        assertEquals("disable", link.calls.last())
        assertFalse(link.notifying)
    }

    @Test
    fun `closing a stream that never started, or a dropped link, writes nothing`() = runTest {
        prepared().close()
        val dropped = streaming()
        link.drop(); runCurrent()
        dropped.close()

        assertFalse(link.calls.contains("disable"))
    }

    // ---- NEEDS_ACK messages (OTA commands) -----------------------------------------------------------

    private fun TestScope.sendOta(connection: LinkConnection, payload: ByteArray) =
        async { runCatching { connection.sendOtaCommand(payload) } }.also { runCurrent() }

    private fun control(type: MessageType, ackedType: MessageType, seq: Int) {
        link.notify(byteArrayOf(type.value.toByte(), ackedType.value.toByte(), seq.toByte()))
    }

    @Test
    fun `an acknowledged message is written with NEEDS_ACK and completes on the device's ACK for it`() = runTest {
        val connection = streaming()
        val sending = sendOta(connection, byteArrayOf(1, 2, 3))

        val frame = link.writes.single().single()
        assertEquals(MessageType.OTA_C2D.value, frame[0].toInt())
        assertEquals(FrameCodec.FLAG_NEEDS_ACK, frame[1].toInt() and FrameCodec.FLAG_NEEDS_ACK)
        val seq = frame[2].toInt()

        // Not an answer to it: another type, another sequence number.
        control(MessageType.ACK, MessageType.DESIRED_CONFIGURATION, seq)
        control(MessageType.ACK, MessageType.OTA_C2D, seq + 1)
        runCurrent()
        assertFalse(sending.isCompleted)

        control(MessageType.ACK, MessageType.OTA_C2D, seq)
        assertTrue(sending.await().isSuccess)
    }

    @Test
    fun `the wait for an ACK starts once the write has returned`() = runTest {
        val connection = streaming()
        val gate = CompletableDeferred<Unit>().also { link.gate = it } // the frames wait behind the host
        val sending = sendOta(connection, byteArrayOf(1))

        advanceTimeBy(8_000); runCurrent()
        gate.complete(Unit); runCurrent()
        advanceTimeBy(4_000); runCurrent()

        assertEquals("not retransmitted: the device has had only 4 s to answer", 1, link.writes.size)
        control(MessageType.ACK, MessageType.OTA_C2D, link.writes[0][0][2].toInt())
        assertTrue(sending.await().isSuccess)
    }

    @Test
    fun `a NACK retransmits the same message, with the same sequence number, after a backoff`() = runTest {
        val connection = streaming()
        val sending = sendOta(connection, byteArrayOf(4, 5))
        val first = link.writes.single()

        control(MessageType.NACK, MessageType.OTA_C2D, first[0][2].toInt())
        advanceTimeBy(999); runCurrent()
        assertEquals("backs off before retransmitting", 1, link.writes.size)
        advanceTimeBy(2); runCurrent()

        assertEquals(2, link.writes.size)
        assertArrayEquals(first[0], link.writes[1][0])
        control(MessageType.ACK, MessageType.OTA_C2D, first[0][2].toInt())
        assertTrue(sending.await().isSuccess)
    }

    @Test
    fun `no answer retransmits after the ACK timeout`() = runTest {
        val connection = streaming()
        val sending = sendOta(connection, byteArrayOf(6))

        advanceTimeBy(LinkConnection.ACK_TIMEOUT_MS + 1_001); runCurrent()

        assertEquals(2, link.writes.size)
        assertArrayEquals(link.writes[0][0], link.writes[1][0])
        control(MessageType.ACK, MessageType.OTA_C2D, link.writes[0][0][2].toInt())
        assertTrue(sending.await().isSuccess)
    }

    @Test
    fun `the next acknowledged message of a type gets the next sequence number`() = runTest {
        val connection = streaming()
        val first = sendOta(connection, byteArrayOf(1))
        control(MessageType.ACK, MessageType.OTA_C2D, link.writes[0][0][2].toInt())
        first.await()
        val second = sendOta(connection, byteArrayOf(2))

        assertEquals((link.writes[0][0][2] + 1).toByte(), link.writes[1][0][2])
        control(MessageType.ACK, MessageType.OTA_C2D, link.writes[1][0][2].toInt())
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun `a message waiting for its ACK fails when the link goes down`() = runTest {
        val connection = streaming()
        val sending = sendOta(connection, byteArrayOf(7))

        link.drop(); runCurrent()

        assertTrue(sending.await().isFailure)
    }

    @Test
    fun `control frames never reach the incoming messages`() = runTest {
        val connection = streaming()
        val received = async { connection.incoming.first() }
        runCurrent()

        control(MessageType.ACK, MessageType.OTA_C2D, 0)
        control(MessageType.NACK, MessageType.OTA_C2D, 0)
        FrameCodec.fragment(MessageType.OTA_D2C, byteArrayOf(9, 9), seq = 0, frameSize = 244).forEach(link::notify)
        runCurrent()

        assertEquals(Message(MessageType.OTA_D2C, byteArrayOf(9, 9)), received.await())
    }
}
