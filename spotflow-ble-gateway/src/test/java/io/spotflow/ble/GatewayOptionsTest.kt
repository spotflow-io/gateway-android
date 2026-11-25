package io.spotflow.ble

import io.spotflow.ble.cloud.CloudEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayOptionsTest {

    @Test
    fun `the defaults target production with a 50 MiB buffer and OTA on`() {
        val options = GatewayOptions.DEFAULT
        assertEquals(CloudEndpoint("mqtt.spotflow.io", 8883), options.endpoint)
        assertEquals(1L shl 20, options.ramBufferBytes)
        assertEquals(49L shl 20, options.flashBufferBytes)
        assertEquals(true, options.otaEnabled)
        assertEquals(32L shl 20, options.otaMaxArtifactBytes)
        assertEquals(options, GatewayOptions { })
    }

    @Test
    fun `copy changes only what it sets`() {
        val staging = GatewayOptions { endpoint = CloudEndpoint("mqtt.staging.example") }
        val copy = staging.copy { otaEnabled = false }

        assertFalse(copy.otaEnabled)
        assertEquals(CloudEndpoint("mqtt.staging.example", CloudEndpoint.DEFAULT_PORT), copy.endpoint)
        assertEquals(staging.flashBufferBytes, copy.flashBufferBytes)
        assertNotEquals(staging, copy)
    }

    @Test
    fun `either buffer tier may be 0, but no size may be negative`() {
        GatewayOptions { ramBufferBytes = 0; flashBufferBytes = 0 }
        assertThrows(IllegalArgumentException::class.java) { GatewayOptions { ramBufferBytes = -1 } }
        assertThrows(IllegalArgumentException::class.java) { GatewayOptions { flashBufferBytes = -1 } }
        assertThrows(IllegalArgumentException::class.java) { GatewayOptions { otaMaxArtifactBytes = 0 } }
    }

    @Test
    fun `an endpoint needs a host and a valid port`() {
        assertThrows(IllegalArgumentException::class.java) { CloudEndpoint(" ") }
        assertThrows(IllegalArgumentException::class.java) { CloudEndpoint("mqtt.example", 0) }
        assertThrows(IllegalArgumentException::class.java) { CloudEndpoint("mqtt.example", 65536) }
    }
}
