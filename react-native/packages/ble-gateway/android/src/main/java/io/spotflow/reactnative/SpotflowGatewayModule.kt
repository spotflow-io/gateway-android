package io.spotflow.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule
import io.spotflow.ble.Attachment
import io.spotflow.ble.AttachmentState
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.protocol.GattProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The JS face of the process's [io.spotflow.ble.SpotflowGateway]. MQTT, buffering and the Spotflow BLE
 * protocol stay in Kotlin; JS performs the GATT operations of each link it registers ([JsLinkHub]).
 *
 * Never logs GATT values: they include OTA commands and artifact requests, which carry download secrets.
 */
@ReactModule(name = SpotflowGatewayModule.NAME)
internal class SpotflowGatewayModule(context: ReactApplicationContext) : NativeSpotflowGatewaySpec(context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            android.util.Log.e(NAME, "unexpected failure in the Spotflow gateway bridge", error)
        },
    )

    private val hub = JsLinkHub(scope, send = { emitOnLinkRequest(it.toMap()) })

    /** By the attachment ID JS chose; removed once the attachment has ended. */
    private val attachments = ConcurrentHashMap<Int, Attachment>()

    override fun getName(): String = NAME

    override fun configure(options: ReadableMap, promise: Promise) {
        try {
            promise.resolve(GatewayHolder.configure(reactApplicationContext, options.toGatewayConfig()).toDouble())
        } catch (e: IllegalArgumentException) {
            promise.reject(E_INVALID_OPTIONS, e.message, e)
        } catch (e: RuntimeException) {
            promise.reject(E_GATEWAY, e.message, e)
        }
    }

    override fun shutdown(gatewayId: Double) {
        GatewayHolder.shutdown(gatewayId.toInt())
    }

    override fun openLink(address: String, maxWritePayloadBytes: Double): Double =
        hub.open(address, maxWritePayloadBytes.toInt()).id.toDouble()

    override fun setMaxWritePayloadBytes(linkId: Double, bytes: Double) {
        hub.setMaxWritePayloadBytes(linkId.toInt(), bytes.toInt())
    }

    override fun linkDisconnected(linkId: Double) {
        hub.disconnected(linkId.toInt())
    }

    override fun resolveRequest(linkId: Double, requestId: Double, value: ReadableArray) {
        hub.resolve(linkId.toInt(), requestId.toInt(), value.toBytes())
    }

    override fun rejectRequest(linkId: Double, requestId: Double, message: String, gattStatus: Double) {
        hub.reject(linkId.toInt(), requestId.toInt(), message, gattStatus.toStatus())
    }

    override fun pushNotification(linkId: Double, seq: Double, value: ReadableArray) {
        hub.notification(linkId.toInt(), seq.toInt(), value.toBytes())
    }

    override fun notificationError(linkId: Double, message: String, gattStatus: Double) {
        hub.notificationError(linkId.toInt(), message, gattStatus.toStatus())
    }

    override fun readDeviceId(linkId: Double, promise: Promise) {
        val link = hub.link(linkId.toInt()) ?: return promise.reject(E_LINK_DOWN, "the link has disconnected")
        scope.launch {
            try {
                promise.resolve(GattProfile.readDeviceId(link))
            } catch (c: CancellationException) {
                promise.reject(E_LINK_DOWN, "the React context was destroyed")
                throw c
            } catch (e: BleLinkException) {
                val info = Arguments.createMap().apply { e.gattStatus?.let { putInt("gattStatus", it) } }
                promise.reject(E_BLE, e.message, e, info)
            }
        }
    }

    override fun attach(gatewayId: Double, linkId: Double, attachmentId: Double, promise: Promise) {
        val gateway = GatewayHolder.current(gatewayId.toInt())
            ?: return promise.reject(E_NOT_CONFIGURED, GATEWAY_GONE)
        val link = hub.link(linkId.toInt()) ?: return promise.reject(E_LINK_DOWN, "the link has disconnected")
        val attachment = try {
            gateway.attach(link)
        } catch (e: IllegalArgumentException) {
            return promise.reject(E_INVALID_ADDRESS, e.message, e)
        } catch (e: IllegalStateException) {
            return promise.reject(E_NOT_CONFIGURED, e.message, e)
        }
        val id = attachmentId.toInt()
        attachments[id] = attachment
        scope.launch {
            attachment.state
                .transformWhile {
                    emit(it)
                    it !is AttachmentState.Ended
                }
                .collect { emitOnAttachmentState(attachmentStateEvent(id, it)) }
            attachments.remove(id)
        }
        promise.resolve(null)
    }

    override fun detach(attachmentId: Double) {
        attachments[attachmentId.toInt()]?.detach()
    }

    override fun diagnosticsSnapshot(gatewayId: Double, promise: Promise) {
        val gateway = GatewayHolder.current(gatewayId.toInt()) ?: return promise.reject(E_NOT_CONFIGURED, GATEWAY_GONE)
        promise.resolve(gateway.diagnosticsSnapshot().toMap())
    }

    /**
     * The React context is going away (e.g. a JS reload): no JS driver is left to serve the links, so every
     * JS-driven attachment ends as disconnected. The gateway itself stays, with its buffers.
     */
    override fun invalidate() {
        hub.invalidate()
        scope.cancel()
        super.invalidate()
    }

    private fun Double.toStatus(): Int? = takeIf { it >= 0 }?.toInt()

    companion object {
        const val NAME = NativeSpotflowGatewaySpec.NAME

        private const val E_INVALID_OPTIONS = "E_INVALID_OPTIONS"
        private const val E_GATEWAY = "E_GATEWAY"
        private const val E_NOT_CONFIGURED = "E_NOT_CONFIGURED"
        private const val E_INVALID_ADDRESS = "E_INVALID_ADDRESS"
        private const val E_LINK_DOWN = "E_LINK_DOWN"
        private const val E_BLE = "E_BLE"

        private const val GATEWAY_GONE = "this gateway has been shut down or replaced: create one with createGateway()"
    }
}
