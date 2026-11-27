package io.spotflow.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import io.spotflow.ble.cloud.CredentialsProvider
import io.spotflow.ble.cloud.DeviceIngestCredential
import io.spotflow.ble.cloud.MqttConfig
import io.spotflow.ble.cloud.MqttUplink
import io.spotflow.ble.cloud.StaticIngestKey
import io.spotflow.ble.cloud.Uplink
import io.spotflow.ble.ota.ArtifactDownloader
import io.spotflow.ble.ota.HttpArtifactDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything [SpotflowGateway] needs from Android and from the network, behind one seam: the Bluetooth
 * adapter, connectivity, and the MQTT client. The gateway's own logic — what is attached, what to
 * report — sits on top of it and is tested against a fake.
 */
internal interface GatewayPlatform {
    /** Whether the phone has a network. Stays `true` if that cannot be observed. */
    val online: StateFlow<Boolean>

    /** Starts watching the Bluetooth adapter and the network. */
    fun start(onBluetoothOff: () -> Unit)

    fun stop()

    fun createUplink(deviceId: String, deviceCredential: DeviceIngestCredential?): Uplink

    /** Downloads OTA artifacts. */
    val artifactDownloader: ArtifactDownloader get() = HttpArtifactDownloader()
}

internal class AndroidGatewayPlatform(
    private val appContext: Context,
    private val credentials: CredentialsProvider?,
    private val mqttConfig: MqttConfig,
) : GatewayPlatform {

    private val network = NetworkMonitor(appContext)

    override val online: StateFlow<Boolean> get() = network.online

    private var bluetoothReceiver: BroadcastReceiver? = null

    /**
     * Watches the Bluetooth adapter itself. Turning Bluetooth off often does not deliver a GATT
     * disconnect callback, which would otherwise leave sessions parked forever; observing the adapter
     * lets the gateway end those sessions.
     */
    @Synchronized
    override fun start(onBluetoothOff: () -> Unit) {
        if (bluetoothReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bluetoothReceiver = receiver
        network.start()
    }

    @Synchronized
    override fun stop() {
        bluetoothReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        bluetoothReceiver = null
        network.stop()
    }

    override fun createUplink(deviceId: String, deviceCredential: DeviceIngestCredential?): Uplink =
        MqttUplink(deviceId, deviceCredential?.let { StaticIngestKey(it.key) } ?: checkNotNull(credentials), mqttConfig)
}

/**
 * Tells whether the phone has a network, so uploads can wait for one instead of retrying into the void,
 * and resume the moment it is back rather than at the end of a backoff.
 */
internal class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _online = MutableStateFlow(true)

    /** Whether there is a default network. `true` if it cannot be observed, so nothing ever waits in vain. */
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
        }

        override fun onLost(network: Network) {
            // Sent for the default network when nothing replaces it (a switch between networks reports
            // the new one as available instead).
            _online.value = false
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered || manager == null) return
        try {
            // The callback only reports changes from here on, and says nothing if there is no network.
            _online.value = manager.activeNetwork != null
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: RuntimeException) {
            // e.g. the host removed ACCESS_NETWORK_STATE from its manifest. Assume a network and let the
            // connection attempts find out.
            Slog.w(TAG, "cannot watch connectivity, assuming the phone is online: ${e.message}")
            _online.value = true
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
        _online.value = true
    }

    private companion object {
        const val TAG = "SpotflowGateway"
    }
}
