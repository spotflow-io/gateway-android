package io.spotflow.ble.cloud

/**
 * MQTT topic names on the Spotflow broker. Telemetry, reported configuration and OTA results are published
 * to fixed topics; desired configuration and OTA commands arrive on a per-device topic *below* the one
 * subscribed to (`config-cbor-c2d/<workspace>/<device>`), so those are matched by prefix.
 */
internal object SpotflowTopics {
    const val INGEST = "ingest-cbor"
    const val REPORTED_CONFIGURATION = "config-cbor-d2c"
    const val DESIRED_CONFIGURATION = "config-cbor-c2d"

    /** OTA commands for the device (subscribed to only for devices that take OTA updates). */
    const val OTA_COMMANDS = "ota-cbor-c2d"

    /** The device's OTA update results. */
    const val OTA_RESULTS = "ota-cbor-d2c"
}

/**
 * How [MqttUplink] reaches the broker. Messages are published with QoS 1: a message stays buffered until
 * the broker acknowledges it, and may be delivered twice.
 */
internal data class MqttConfig(
    val host: String = CloudEndpoint.PRODUCTION.host,
    val port: Int = CloudEndpoint.PRODUCTION.port,
    /** Off only to test against a local broker: a [CloudEndpoint] is always TLS. */
    val useTls: Boolean = true,
    /**
     * MQTT keep-alive. Every device has its own connection, each pinging at this interval while idle, so
     * a short one costs battery on mobile data. A dead link is noticed sooner than this whenever there is
     * data to send (publishes time out), so the default favours battery.
     */
    val keepAliveSeconds: Int = 60,
) {
    constructor(endpoint: CloudEndpoint) : this(endpoint.host, endpoint.port)
}
