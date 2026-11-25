package io.spotflow.reactnative

import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayConfigTest {
    @Test
    fun `device credentials are the default`() {
        val implicit = JavaOnlyMap().toGatewayConfig()
        assertNull(implicit.ingestKey)
        assertEquals(implicit, JavaOnlyMap.of("ingestKeySource", "device").toGatewayConfig())
    }

    @Test
    fun `a configured key is only accepted with gateway mode`() {
        val key = "example-gateway-key"
        val config = JavaOnlyMap.of("ingestKeySource", "gateway", "ingestKey", key).toGatewayConfig()
        assertEquals(key, config.ingestKey)
        assertFalse(config.toString().contains(key))
        listOf(
            JavaOnlyMap.of("ingestKey", key),
            JavaOnlyMap.of("ingestKeySource", "device", "ingestKey", key),
            JavaOnlyMap.of("ingestKeySource", "gateway"),
            JavaOnlyMap.of("ingestKeySource", "gateway", "ingestKey", " "),
            JavaOnlyMap.of("ingestKeySource", "unknown"),
            JavaOnlyMap.of("ingestKeySource", null),
        ).forEach { map ->
            val error = assertThrows(IllegalArgumentException::class.java) { map.toGatewayConfig() }
            assertFalse(error.message.orEmpty().contains(key))
        }
    }
}
