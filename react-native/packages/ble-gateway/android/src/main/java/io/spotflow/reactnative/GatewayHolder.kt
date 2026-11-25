package io.spotflow.reactnative

import android.content.Context
import io.spotflow.ble.GatewayOptions
import io.spotflow.ble.SpotflowGateway
import io.spotflow.ble.cloud.StaticIngestKey

/** What JS configured the gateway with. Equal configurations share one gateway. */
internal data class GatewayConfig(val ingestKey: String?, val options: GatewayOptions) {
    override fun toString(): String = "GatewayConfig(ingestKeySource=${if (ingestKey == null) "device" else "gateway"}, options=$options)"
}

/**
 * The one gateway of the process. It outlives React contexts, so a JS reload (or Headless JS next to the
 * UI) configures the gateway it already has and keeps uploading what is buffered.
 */
internal object GatewayHolder {
    private var config: GatewayConfig? = null
    private var gateway: SpotflowGateway? = null

    val current: SpotflowGateway? @Synchronized get() = gateway

    /**
     * Returns the gateway for [config], creating it on first use. Another configuration shuts the previous
     * gateway down first; its unsent data is handed over through the flash buffer if enabled.
     */
    @Synchronized
    fun configure(context: Context, config: GatewayConfig): SpotflowGateway {
        gateway?.let { if (config == this.config) return it }
        gateway?.shutdown()
        gateway = null
        val created = if (config.ingestKey == null) {
            SpotflowGateway.usingDeviceIngestKeys(context.applicationContext, config.options)
        } else {
            SpotflowGateway(context.applicationContext, StaticIngestKey(config.ingestKey), config.options)
        }
        this.config = config
        gateway = created
        return created
    }

    @Synchronized
    fun shutdown() {
        gateway?.shutdown()
        gateway = null
        config = null
    }
}
