package io.spotflow.gateway.demo

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.spotflow.ble.GatewayOptions
import io.spotflow.ble.cloud.StaticIngestKey
import io.spotflow.ble.managed.DeviceFilter
import io.spotflow.ble.managed.ManagedGateway
import io.spotflow.ble.managed.SpotflowGatewayService

/**
 * Re-installs the gateway service hooks whenever the process starts. If Android kills the process while
 * the gateway runs, the sticky service is recreated in a fresh process — where the hooks set by
 * [MainActivity] are gone — so they must be restored here for relaying to resume.
 */
class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = IngestKeyStore(this)
        if (store.gatewayEnabled) configureGatewayService(store)
    }
}

/**
 * Points [SpotflowGatewayService] at a gateway built from the saved settings, read whenever the service
 * creates one — so a restart picks up settings saved since. Records which settings each gateway was built
 * with, so the UI can tell when saved changes still need a restart. Returns false if no key is saved.
 */
fun configureGatewayService(store: IngestKeyStore): Boolean {
    if (store.ingestKey.isNullOrBlank()) return false
    SpotflowGatewayService.gatewayFactory = { ctx ->
        val key = store.ingestKey.orEmpty()
        val options = GatewayOptions {
            ramBufferBytes = store.bufferRamMb.toLong() * 1024L * 1024L
            flashBufferBytes = store.bufferFlashMb.toLong() * 1024L * 1024L
        }
        store.appliedSettingsVersion = store.settingsVersion
        // A demo relays whatever is in range. An app for real devices passes a filter that accepts only
        // its own (see DeviceFilter): the BLE protocol does not authenticate devices.
        ManagedGateway(ctx, StaticIngestKey(key), DeviceFilter.AcceptAll, options)
    }
    // Permissions revoked since the gateway was started don't make this throw: discovery reports them as
    // ScanState.RETRYING, which the main screen shows.
    SpotflowGatewayService.onReady = { gateway -> gateway.startScanning() }
    return true
}

/** Whether the Bluetooth permissions the gateway needs are currently granted. */
fun hasGatewayPermissions(context: Context): Boolean {
    val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * Starts the gateway service if the user left the gateway enabled but it isn't running (e.g. after an
 * app update or a reboot, which don't restart services). Returns whether the gateway is running (or
 * starting) afterwards. If it can no longer run (key or permissions gone), the enabled flag is cleared
 * so the UI doesn't claim it is running.
 */
fun resumeGatewayIfEnabled(context: Context, store: IngestKeyStore = IngestKeyStore(context)): Boolean {
    if (!store.gatewayEnabled) return false
    if (SpotflowGatewayService.gateway != null) return true
    if (!hasGatewayPermissions(context) || !configureGatewayService(store)) {
        Log.w("GatewayApp", "gateway was enabled but can't resume (missing key or permissions)")
        store.gatewayEnabled = false
        return false
    }
    return runCatching { SpotflowGatewayService.start(context) }
        .onFailure { Log.w("GatewayApp", "cannot start gateway service: ${it.message}") }
        .isSuccess
}

/** Resumes the gateway after the app is updated or the phone reboots, if it was left enabled. */
class GatewayResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> resumeGatewayIfEnabled(context)
        }
    }
}
