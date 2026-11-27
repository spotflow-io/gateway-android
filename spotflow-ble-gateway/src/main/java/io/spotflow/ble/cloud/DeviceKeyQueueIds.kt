package io.spotflow.ble.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Resolves the flash partition without storing the device's credential (or an unkeyed hash of it). */
internal class DeviceKeyQueueIds(
    private val endpoint: CloudEndpoint,
    private val localKey: () -> SecretKey = ::loadOrCreateLocalKey,
) {
    fun queueId(deviceId: String, ingestKey: String): String {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).apply { init(localKey()) }
        for (part in listOf(endpoint.host, endpoint.port.toString(), deviceId, ingestKey)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            try {
                mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                mac.update(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return MessageStore.deviceKeyQueue(mac.doFinal().joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "io.spotflow.ble.device-key-queue.v1"

        @Synchronized
        fun loadOrCreateLocalKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).setKeySize(256).build())
            }.generateKey()
        }
    }
}

/** In-memory credential for one device attachment/uplink, never included in diagnostics. */
internal class DeviceIngestCredential(val key: String, val queueId: String)
