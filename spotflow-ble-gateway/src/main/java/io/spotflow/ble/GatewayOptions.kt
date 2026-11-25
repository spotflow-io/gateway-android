package io.spotflow.ble

import io.spotflow.ble.cloud.CloudEndpoint

/**
 * How a [SpotflowGateway] runs: where it uploads, how much it may buffer, and what it may do for the
 * devices. Every option has a default that suits production ([DEFAULT]).
 *
 * In Kotlin, build options with `GatewayOptions { … }`; in Java, with [Builder].
 */
public class GatewayOptions internal constructor(builder: Builder) {

    /** The broker the gateway uploads to. Spotflow's production broker by default. */
    public val endpoint: CloudEndpoint = builder.endpoint

    /**
     * Size of the RAM tier of the store-and-forward buffer, shared by all devices. Online, data flows
     * through RAM only and flash is not written; the oldest messages of the device holding the most RAM
     * spill to flash only once RAM fills up (i.e. connectivity has been down long enough to accumulate a
     * backlog). Data still in RAM is lost if the process is killed; 0 writes every message to flash at
     * once. 1 MiB by default.
     */
    public val ramBufferBytes: Long = builder.ramBufferBytes

    /**
     * Size of the flash tier of the buffer, shared by all devices; 0 for a RAM-only buffer that never
     * writes to flash. When the buffer is full, the device holding the most data loses its oldest messages
     * first, so one flooding or long-offline device can't push out the others. 49 MiB by default.
     *
     * Sizes count each message's payload plus a fixed estimate of what holding it costs (object headers
     * in RAM; row and index overhead in flash), so the limits track real use even for tiny messages.
     */
    public val flashBufferBytes: Long = builder.flashBufferBytes

    /**
     * Whether to relay over-the-air updates (Spotflow BLE protocol 1.1) to devices whose Capabilities
     * advertise them: the gateway forwards their OTA commands and results, and downloads their firmware
     * over HTTPS on their behalf. Off, such devices are still relayed for telemetry and configuration. On
     * by default.
     *
     * The gateway is part of the update's trust boundary: it holds the artifacts' download credentials
     * while it serves them, and decides what bytes reach the device. Firmware signatures are still checked
     * on the device (by its bootloader or update handler).
     */
    public val otaEnabled: Boolean = builder.otaEnabled

    /**
     * Largest OTA artifact the gateway downloads; a device asking for a larger one is told it is too
     * large. 32 MiB by default.
     */
    public val otaMaxArtifactBytes: Long = builder.otaMaxArtifactBytes

    /** These options with the changes [block] makes. */
    public fun copy(block: Builder.() -> Unit): GatewayOptions = Builder(this).apply(block).build()

    override fun equals(other: Any?): Boolean = other is GatewayOptions &&
        other.endpoint == endpoint &&
        other.ramBufferBytes == ramBufferBytes &&
        other.flashBufferBytes == flashBufferBytes &&
        other.otaEnabled == otaEnabled &&
        other.otaMaxArtifactBytes == otaMaxArtifactBytes

    override fun hashCode(): Int =
        listOf(endpoint, ramBufferBytes, flashBufferBytes, otaEnabled, otaMaxArtifactBytes).hashCode()

    override fun toString(): String =
        "GatewayOptions(endpoint=$endpoint, ramBufferBytes=$ramBufferBytes, flashBufferBytes=$flashBufferBytes, " +
            "otaEnabled=$otaEnabled, otaMaxArtifactBytes=$otaMaxArtifactBytes)"

    /** Builds [GatewayOptions]; starts from the defaults. See the properties of [GatewayOptions]. */
    public class Builder() {
        public var endpoint: CloudEndpoint = CloudEndpoint.PRODUCTION
        public var ramBufferBytes: Long = 1L * 1024 * 1024
        public var flashBufferBytes: Long = 49L * 1024 * 1024
        public var otaEnabled: Boolean = true
        public var otaMaxArtifactBytes: Long = 32L * 1024 * 1024

        internal constructor(options: GatewayOptions) : this() {
            endpoint = options.endpoint
            ramBufferBytes = options.ramBufferBytes
            flashBufferBytes = options.flashBufferBytes
            otaEnabled = options.otaEnabled
            otaMaxArtifactBytes = options.otaMaxArtifactBytes
        }

        /** @throws IllegalArgumentException if a size is out of range. */
        public fun build(): GatewayOptions {
            require(ramBufferBytes >= 0) { "ramBufferBytes must not be negative" }
            require(flashBufferBytes >= 0) { "flashBufferBytes must not be negative" }
            require(otaMaxArtifactBytes > 0) { "otaMaxArtifactBytes must be positive" }
            return GatewayOptions(this)
        }
    }

    public companion object {
        /** The defaults: Spotflow's production broker, a 1 MiB RAM and 49 MiB flash buffer, OTA on. */
        @JvmField
        public val DEFAULT: GatewayOptions = Builder().build()
    }
}

/**
 * Builds [GatewayOptions], starting from the defaults:
 * ```
 * GatewayOptions {
 *     flashBufferBytes = 0   // RAM only
 *     otaEnabled = false
 * }
 * ```
 */
public fun GatewayOptions(block: GatewayOptions.Builder.() -> Unit): GatewayOptions =
    GatewayOptions.Builder().apply(block).build()
