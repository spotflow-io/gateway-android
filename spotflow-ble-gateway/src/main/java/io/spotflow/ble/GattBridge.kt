package io.spotflow.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.transport.GattClient

/**
 * A [BleLink] for a `BluetoothGatt` the app holds itself — for apps that already have a connection to the
 * device and don't want a second one. One bridge serves one `BluetoothGatt`:
 *
 * ```
 * val bridge = GattBridge(myCallback)   // myCallback: the app's own BluetoothGattCallback
 * val gatt = device.connectGatt(context, false, bridge.callback, BluetoothDevice.TRANSPORT_LE)
 * // in myCallback, once connected, with services discovered and the MTU negotiated:
 * val attachment = gateway.attach(bridge.link(gatt))
 * ```
 *
 * 1. **Wire [callback]** into the GATT's callbacks from the start. Either register it with `connectGatt`
 *    and pass the app's own callback as the bridge's `delegate`, which then receives every event, or
 *    register the app's callback and forward connection state changes, MTU changes, characteristic
 *    reads/writes/notifications, and descriptor writes to [callback]. Forward both the API 33+ and older
 *    overloads of `onCharacteristicRead` and `onCharacteristicChanged`.
 * 2. **Attach [link]** once the link is ready, as for any [BleLink]: connected, services discovered and
 *    any MTU exchange finished. After a reconnect (with `BluetoothGatt.connect()`, so the GATT and the bridge stay the
 *    same), attach a fresh [link].
 *
 * While attached, run the app's own GATT operations inside [runExclusive]: Android allows only one
 * outstanding GATT operation at a time, and this is what keeps the app's and the gateway's apart. Time a
 * gateway operation spends waiting for a [runExclusive] block counts towards its 10 s budget, so keep
 * blocks short.
 *
 * @param delegate the app's own callback, when [callback] is the one registered with the GATT: it gets
 *   every event once the bridge has seen it, as Android would have delivered it — the gateway's too
 *   (notifications and operation results on the Spotflow service's characteristics), which it can ignore.
 */
public class GattBridge @JvmOverloads constructor(delegate: BluetoothGattCallback? = null) {

    private val client = GattClient(delegate)

    /** Receives the GATT's events. Valid for the life of the bridge, across reconnects. */
    public val callback: BluetoothGattCallback get() = client.callback

    /**
     * A link over [gatt] for as long as its connection is up now; attach it with [SpotflowGateway.attach].
     *
     * @param mtu the connection's ATT MTU, if the bridge may have missed its exchange (its [callback] was
     *   wired in after the MTU was negotiated). Otherwise it uses the MTU it saw negotiated, or the
     *   minimum of 23.
     * @throws IllegalArgumentException if the bridge already serves another `BluetoothGatt`, or [mtu] is
     *   below 23.
     */
    @JvmOverloads
    public fun link(gatt: BluetoothGatt, mtu: Int? = null): BleLink {
        require(mtu == null || mtu >= FrameCodec.MIN_MTU) { "an ATT MTU is at least ${FrameCodec.MIN_MTU}" }
        client.bind(gatt)
        mtu?.let { client.mtu = it }
        return client.link()
    }

    /**
     * Runs [block] while holding the GATT operation queue, so the app's own operations (issued on the
     * GATT inside [block], awaiting their own callbacks) never overlap with the gateway's. It is the
     * host's responsibility not to exit [block], even on cancellation, while its GATT operation is still
     * outstanding. Keep blocks short: the wait counts towards the gateway operation's time budget.
     *
     * @throws IllegalStateException before the first [link].
     */
    public suspend fun <T> runExclusive(block: suspend (BluetoothGatt) -> T): T {
        val gatt = checkNotNull(client.gatt) { "link() has not been called" }
        return client.exclusive { block(gatt) }
    }
}
