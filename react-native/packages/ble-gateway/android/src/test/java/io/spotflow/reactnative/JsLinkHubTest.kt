package io.spotflow.reactnative

import io.spotflow.ble.BleLinkException
import io.spotflow.ble.GattRef
import io.spotflow.ble.NotificationListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class JsLinkHubTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val ref = GattRef(UUID.randomUUID(), UUID.randomUUID())
    private val sent = mutableListOf<LinkRequest>()

    private fun TestScope.hub() = JsLinkHub(backgroundScope, { sent += it }, ConcurrentHashMap())

    private fun LinkRequest.describe() = "$linkId/$requestId ${op.wireName}"

    @Test
    fun `calls for one address reach JS one at a time, in issue order, across links`() = runTest {
        val hub = hub()
        val first = hub.open(address, 20)
        val second = hub.open(address, 20)
        val other = hub.open("11:22:33:44:55:66", 20)

        val a = async { first.read(ref) }
        val b = async { second.writeWithoutResponse(ref, listOf(byteArrayOf(1), byteArrayOf(2))) }
        val c = async { other.read(ref) }
        runCurrent()
        assertEquals(listOf("1/1 read", "3/1 read"), sent.map { it.describe() })

        hub.resolve(1, 1, byteArrayOf(9))
        runCurrent()
        assertArrayEquals(byteArrayOf(9), a.await())
        assertEquals("2/1 writeWithoutResponse", sent.last().describe())
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), sent.last().frames.map { it.toList() })

        hub.resolve(2, 1, ByteArray(0))
        hub.resolve(3, 1, byteArrayOf(5))
        b.await()
        assertArrayEquals(byteArrayOf(5), c.await())
    }

    @Test
    fun `an abandoned call keeps the queue until JS settles it`() = runTest {
        val hub = hub()
        val link = hub.open(address, 20)
        val timedOut = withTimeoutOrNull(10_000) { link.read(ref) }
        assertNull(timedOut)

        val next = async { link.disableNotifications(ref) }
        runCurrent()
        assertEquals("the next call waits behind the abandoned one", 1, sent.size)

        hub.resolve(link.id, 1, ByteArray(0))
        runCurrent()
        assertEquals("1/2 disableNotifications", sent.last().describe())
        hub.resolve(link.id, 2, ByteArray(0))
        next.await()
    }

    @Test
    fun `a late or unknown result settles nothing else`() = runTest {
        val hub = hub()
        val link = hub.open(address, 20)
        val read = async { link.read(ref) }
        runCurrent()
        hub.resolve(link.id, 7, byteArrayOf(1))
        hub.resolve(link.id + 1, 1, byteArrayOf(1))
        runCurrent()
        assertTrue(read.isActive)
        hub.resolve(link.id, 1, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), read.await())
    }

    @Test
    fun `a rejection carries the GATT status`() = runTest {
        val hub = hub()
        val link = hub.open(address, 20)
        val read = async { runCatching { link.read(ref) } }
        runCurrent()
        hub.reject(link.id, 1, "Error reading status=5", 5)
        val error = read.await().exceptionOrNull() as BleLinkException
        assertEquals(5, error.gattStatus)
        assertEquals("Error reading status=5", error.message)
    }

    @Test
    fun `a disconnect is signalled, fails pending and queued calls, and frees the queue`() = runTest {
        val hub = hub()
        val link = hub.open(address, 20)
        val events = mutableListOf<String>()
        launch { link.awaitDisconnect(); events += "disconnected" }
        val pending = async { runCatching { link.read(ref) }.also { events += "pending failed" } }
        val queued = async { runCatching { link.read(ref) } }
        runCurrent()

        hub.disconnected(link.id)
        runCurrent()
        assertEquals(listOf("disconnected", "pending failed"), events)
        assertTrue(pending.await().exceptionOrNull() is BleLinkException)
        assertTrue(queued.await().exceptionOrNull() is BleLinkException)
        assertEquals("the queued call never reaches JS", 1, sent.size)
        assertNull(hub.link(link.id))

        val reconnected = hub.open(address, 20)
        val read = async { reconnected.read(ref) }
        runCurrent()
        assertEquals("2/1 read", sent.last().describe())
        hub.resolve(reconnected.id, 1, ByteArray(0))
        read.await()
    }

    @Test
    fun `values reach the listener between enable and disable, and a gap ends the subscription`() = runTest {
        val hub = hub()
        val link = hub.open(address, 20)
        val values = mutableListOf<List<Byte>>()
        val errors = mutableListOf<BleLinkException>()
        val listener = object : NotificationListener {
            override fun onValue(value: ByteArray) {
                values += value.toList()
            }

            override fun onError(error: BleLinkException) {
                errors += error
            }
        }

        val enable = async { link.enableNotifications(ref, listener) }
        runCurrent()
        hub.notification(link.id, 0, byteArrayOf(1)) // before the CCCD write has completed
        hub.resolve(link.id, 1, ByteArray(0))
        enable.await()
        hub.notification(link.id, 1, byteArrayOf(2))
        hub.notification(link.id, 3, byteArrayOf(4))
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), values)
        assertEquals(1, errors.size)

        val disable = async { link.disableNotifications(ref) }
        runCurrent()
        hub.resolve(link.id, 2, ByteArray(0))
        disable.await()
        hub.notification(link.id, 4, byteArrayOf(5))
        assertEquals(2, values.size)
    }

    @Test
    fun `invalidating drops every link`() = runTest {
        val hub = hub()
        val first = hub.open(address, 20)
        val second = hub.open("11:22:33:44:55:66", 20)
        val read = async { runCatching { first.read(ref) } }
        runCurrent()
        hub.invalidate()
        first.awaitDisconnect()
        second.awaitDisconnect()
        assertTrue(read.await().exceptionOrNull() is BleLinkException)
    }

    @Test
    fun `the frame size follows the host's updates`() = runTest {
        val hub = hub()
        val link = hub.open(address, 20)
        hub.setMaxWritePayloadBytes(link.id, 244)
        assertEquals(244, link.maxWritePayloadBytes)
    }
}
