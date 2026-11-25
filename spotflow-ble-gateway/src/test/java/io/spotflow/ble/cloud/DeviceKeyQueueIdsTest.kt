package io.spotflow.ble.cloud

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceKeyQueueIdsTest {
    @Test
    fun `device queue identity binds endpoint device key and installation`() {
        val endpoint = CloudEndpoint("mqtt.example.com")
        fun ids(target: CloudEndpoint = endpoint, installation: Byte = 1) =
            DeviceKeyQueueIds(target) { SecretKeySpec(ByteArray(32) { installation }, "HmacSHA256") }
        val key = "example-key-not-for-storage"
        val original = ids().queueId("device", key)

        assertEquals(original, ids().queueId("device", key))
        assertFalse(original.contains(key))
        assertEquals(null, MessageStore.suppliedKeyDeviceId(original))
        val alternatives = listOf(
            ids(CloudEndpoint("mqtt.example.net")).queueId("device", key),
            ids(CloudEndpoint(endpoint.host, 443)).queueId("device", key),
            ids().queueId("other-device", key),
            ids().queueId("device", "rotated-key"),
            ids(installation = 2).queueId("device", key),
        )
        assertEquals(6, (alternatives + original).toSet().size)
    }
}
