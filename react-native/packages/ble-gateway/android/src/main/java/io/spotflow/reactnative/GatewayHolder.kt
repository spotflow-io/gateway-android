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
 *
 * Every gateway it creates gets an ID, which JS handles carry: a handle acts on its own gateway only, never
 * on one that replaced it (e.g. configured differently by another React context).
 */
internal object GatewayHolder {
    private var config: GatewayConfig? = null
    private var gateway: SpotflowGateway? = null
    private var id = 0

    /** The gateway with [id], while it is the current one. */
    @Synchronized
    fun current(id: Int): SpotflowGateway? = gateway?.takeIf { id == this.id }

    /**
     * Returns the ID of the gateway for [config], creating it on first use. Another configuration shuts the
     * previous gateway down first; its unsent data is handed over through the flash buffer if enabled.
     */
    @Synchronized
    fun configure(context: Context, config: GatewayConfig): Int {
        if (gateway != null && config == this.config) return id
        gateway?.shutdown()
        gateway = null
        val created = if (config.ingestKey == null) {
            SpotflowGateway.usingDeviceIngestKeys(context.applicationContext, config.options)
        } else {
            SpotflowGateway(context.applicationContext, StaticIngestKey(config.ingestKey), config.options)
        }
        this.config = config
        gateway = created
        return ++id
    }

    /** Shuts the gateway with [id] down, unless another one has replaced it already. */
    @Synchronized
    fun shutdown(id: Int) {
        if (id != this.id) return
        gateway?.shutdown()
        gateway = null
        config = null
    }
}
