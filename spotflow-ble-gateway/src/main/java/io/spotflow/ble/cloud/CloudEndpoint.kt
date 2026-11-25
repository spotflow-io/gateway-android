package io.spotflow.ble.cloud

/**
 * The Spotflow MQTT broker a gateway uploads to: [PRODUCTION] unless told otherwise (see
 * [GatewayOptions.endpoint][io.spotflow.ble.GatewayOptions.endpoint]). The connection is always TLS,
 * validated by the Android system trust store.
 */
public class CloudEndpoint @JvmOverloads constructor(
    public val host: String,
    public val port: Int = DEFAULT_PORT,
) {
    init {
        require(host.isNotBlank()) { "the host is blank" }
        require(port in 1..65535) { "port $port is out of range" }
    }

    override fun equals(other: Any?): Boolean = other is CloudEndpoint && other.host == host && other.port == port

    override fun hashCode(): Int = 31 * host.hashCode() + port

    override fun toString(): String = "$host:$port"

    public companion object {
        public const val DEFAULT_PORT: Int = 8883

        /** Spotflow's production broker. */
        @JvmField
        public val PRODUCTION: CloudEndpoint = CloudEndpoint("mqtt.spotflow.io")
    }
}
