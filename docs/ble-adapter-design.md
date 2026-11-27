# BLE transport adapters for the Spotflow gateway

**Status:** Kotlin transport adapters are implemented. The React Native bridge and the `react-native-ble-manager`
driver are implemented in [`react-native/`](../react-native/README.md); the `react-native-ble-plx` driver is
proposed. The [README](../README.md#quick-start) and [API baseline](../spotflow-ble-gateway/api/spotflow-ble-gateway.api)
describe the Kotlin API.

## Motivation

Companion apps such as SimpleLink Connect already own a BLE connection and use it for their own features. The `GattBridge` adapter requires access to Android's `BluetoothGatt`, callbacks and operation queue. SimpleLink's `react-native-ble-manager` keeps that machinery private. Patching it would add maintenance work; opening a second connection would not reuse the host's link. Rewriting the gateway in TypeScript would duplicate framing, MQTT/TLS, store-and-forward buffering, configuration sync, and OTA handling.

## Proposed design

`SpotflowGateway.attach(link: BleLink)` is the entry point in the Kotlin AAR. The gateway core keeps the Spotflow BLE protocol (framing, reassembly, ACK/NACK, OTA and artifact transfer) and the cloud logic. The adapter exposes raw GATT operations and notifications. The host owns permissions, discovery, connection, MTU negotiation, reconnection, and the single GATT operation queue. `ManagedGateway` handles those tasks when there is no host connection.

```kotlin
public interface BleLink {
    public val address: String
    public val maxWritePayloadBytes: Int

    public suspend fun awaitDisconnect()
    public suspend fun read(ref: GattRef): ByteArray
    public suspend fun writeWithoutResponse(ref: GattRef, frames: List<ByteArray>)
    public suspend fun enableNotifications(ref: GattRef, listener: NotificationListener)
    public suspend fun disableNotifications(ref: GattRef)
}

public interface NotificationListener {
    public fun onValue(value: ByteArray)
    public fun onError(error: BleLinkException)
}

public data class GattRef(val service: UUID, val characteristic: UUID)

public open class BleLinkException(
    message: String,
    public val gattStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
```

One `BleLink` represents one connected, service-discovered link.

### Identity

`address` is the peripheral's Bluetooth address, as `BluetoothDevice.getAddress()` reports it. Both `react-native-ble-manager` and `react-native-ble-plx` use this as the peripheral ID on Android. It is not the attachment ID or the Spotflow device ID. `Attachment.address`, `DeviceReport.address` and the managed `DeviceFilter` all see the same address. `attach()` rejects invalid Bluetooth addresses. Attaching an address that managed mode is relaying replaces the managed session.

### Call order

The core waits for at most one `BleLink` call per link at a time, not counting `awaitDisconnect()`. It does not wait for every call to settle: a call it abandoned on a timeout, or whose session was cancelled, may still be running when it issues the next one — every session ends with a `disableNotifications` issued that way. Calls for one connection also come from several links: a replacement attachment's, and the host's own `GattProfile.readDeviceId(link)`. The host's own operations may run between the gateway's calls.

Adapters run calls in the order the core issues them, also across `BleLink` instances for the same connection, and every call settles: it returns or throws, even after the core has stopped waiting for it. A call the core abandoned on a timeout still runs before the next one; the adapter does not skip, reorder or overlap it, and drops its late result. A library with a per-peripheral operation queue provides this order. The native adapter provides it by keeping its queue held until the abandoned operation's callback arrives or the link drops.

### Reads and writes

`writeWithoutResponse` receives all frames of one message, in order. The adapter sends each value as one write, without splitting it, and returns once the last value has been handed to the stack:

- The native adapter waits for `onCharacteristicWrite` after each value.
- An adapter over a library whose write call returns once the write is queued also queues a fence: an operation behind the values whose completion shows that the values have been sent. It returns when the fence settles.

The core's timeouts, including the 5 s wait for an OTA ACK, therefore start when the frames are on their way, not while they wait behind the host's operations. It also bounds how much gateway traffic sits in the host's queue.

Returning means the stack accepted the write, not that the device received it. Per the protocol, RX Stream delivery is best effort: an adapter may drop a value the stack rejects without reporting an error, and does not retry it. `OTA_C2D` reliability comes from `NEEDS_ACK`, which the core handles.

### Frame size

`maxWritePayloadBytes` is the frame size: the ATT MTU minus 3. Use 20 if the MTU is unknown. The core clamps it to `20..512`: no ATT MTU is below 23, and 512 is the protocol's GATT value limit. The core reads it before framing each message, so an MTU exchange after attach takes effect.

The host should negotiate a larger MTU before attaching. One artifact READ response carries up to 4096 bytes, which is about 240 frames at 20 bytes and 18 at an MTU of 247.

### Notifications

`enableNotifications` writes the TX Stream CCCD and returns only once the write has completed, meaning the device has notifications enabled. A failed CCCD write fails the call, with its GATT status. From the call on, the listener receives values serially, in arrival order, without drops. `onValue` must not block; the core buffers without bound, as today. `onError` after enabling ends the attachment.

`disableNotifications` writes the CCCD disable value and returns once the write has completed. The protocol requires the device to stop sending TX Stream notifications from then on. Neither call touches the host's subscriptions on other characteristics.

The core reads Capabilities and Device ID, claims the device's cloud link, then reads and queues Session Metadata before enabling TX Stream notifications. Managed discovery first reads the device ID and applies `DeviceFilter`; it passes the accepted ID to the core without rereading it. Host-owned callers can use `GattProfile.readDeviceId(link)` before attaching; the core reads the ID again on a public `attach(link)`.

The core sends OTA commands only after `enableNotifications` has returned, because their ACKs travel on the TX Stream.

The order assumes the TX Stream CCCD is off when a session starts. It may not be: the bridge reuses links after a JS reload (see [React Native bridge](#react-native-bridge)), and a bonded device whose stack persists CCCDs, as Zephyr does with `CONFIG_BT_SETTINGS`, restores the value when the link is encrypted again. Validation checks the bonded case. If the device streams before the core enables notifications, the core disables TX Stream notifications at the start of every session, before step 1, which also covers the reload case.

### Timeouts, errors and disconnects

The core times out every call it makes and ends the attachment on a timeout. A read or a notification change gets 10 s. A write gets 10 s plus 100 ms per frame: a 4096-byte artifact READ response at 20-byte frames is about 240 frames, which take about 12 s at one frame per 50 ms connection interval, and gets 34 s.

A call's time starts when the core issues it, so it includes time spent behind the host's operations: a library queue does not tell when the gateway's operation starts. Hosts keep their own operations short; one that holds the queue for most of a call's budget can end the attachment instead of only delaying it.

Cancelling a call cannot undo a GATT operation that is already issued; the adapter still runs it in order (see [Call order](#call-order)). Adapters report failures as `BleLinkException`, with the GATT status when the library reports one. That keeps today's diagnostics: for example, an insufficient-encryption status tells the user to pair the device.

When the link drops, `awaitDisconnect()` returns, the core stops waiting for its pending call, and the attachment ends. The adapter still settles every pending call. Where the library drops queued operations on disconnect without settling them, as `react-native-ble-manager` does, the adapter fails them itself. The host attaches a fresh link on reconnect, and the core repeats capability discovery and session setup, as the protocol requires. The device's cloud link keeps uploading what is buffered.

### Attachments

```kotlin
public fun attach(link: BleLink): Attachment

public class Attachment {
    public val address: String
    public val state: StateFlow<AttachmentState>
    public fun detach()
    public suspend fun awaitEnd(): AttachmentEnd
}

public data class AttachmentEnd internal constructor(val reason: Reason, val error: DeviceError? = null) {
    public enum class Reason { DETACHED, REPLACED, DISCONNECTED, FAILED }
}
```

`attach()` returns at once. Observe `state` for `Starting`, `Ready(deviceId)`, and `Ended(result)`.
`Ready` doesn't imply MQTT connectivity. For a dashboard, read `diagnosticsSnapshot()` on demand.

- **Ending.** Every attachment ends exactly once, and `awaitEnd()` says why:
  - `DETACHED`: the host called `detach()`.
  - `REPLACED`: the same address was attached again.
  - `DISCONNECTED`: `awaitDisconnect()` returned.
  - `FAILED`: a call failed or timed out, or the device or cloud refused the session. `error` holds the `DeviceError`: its `kind` (e.g. `PAIRING_REQUIRED` for a device that needs an encrypted link) and the GATT status, where there was one.

  Except after `DISCONNECTED`, the host's link is still up. The host decides whether to attach it again.
- **Attaching the same address again** replaces the previous session. The new session starts after the previous one has attempted its TX Stream disable and released the device's cloud link. The adapter serializes operations so a late disable cannot switch off the new session's notifications.
- **A different address reporting a device ID that is already relayed** ends `FAILED` with `DeviceError.Kind.DUPLICATE_DEVICE`. It is not retried by the core; the host decides.
- **`detach()`** ends only this attachment, not a newer one for the same address. It returns immediately. `awaitEnd()` waits for best-effort notification cleanup (up to 2 s). The gateway leaves the host's link up. Buffered data is still uploaded.

### Reference adapters

**Native Kotlin**

- `GattBridge` provides a `BleLink` over an Android `BluetoothGatt`. The host runs service discovery and MTU negotiation before attaching. Either it registers the bridge's callback with `connectGatt` and passes its own as the bridge's delegate, which then gets every event, or it forwards its callbacks to the bridge.
- The bridge owns the GATT queue across sessions on that GATT; host operations go through `runExclusive`. An abandoned operation holds the queue until its callback arrives or the link drops. Time spent behind the host's operations counts toward the gateway's operation budget.
- Native hosts with a GATT queue of their own implement `BleLink` directly.

**`react-native-ble-manager`**

- **Writes:** call `writeWithoutResponse(id, service, characteristic, frame, frame.length, 0)` once per frame, in order. Setting `maxByteSize` to the frame length prevents rechunking.
  - Each promise resolves once the write is queued, before it is sent.
  - The library's per-peripheral queue sends each write after earlier operations and waits for `onCharacteristicWrite` before the next one.
  - The library runs each call as a command that queues the actual write at the back of its queue. Issuing all frames at once keeps their order, but an operation issued before those commands have run could overtake the writes. The bridge therefore issues the next operation only once the previous one has settled.
  - After the last frame's promise has resolved, the adapter calls `readRSSI(id)` solely as a queue fence and ignores the signal-strength result. The queue runs it only after the last frame's `onCharacteristicWrite`. This is not gateway RSSI polling.
  - A write the stack rejects is dropped without an error, which is best effort as the protocol allows.
- **Notifications:**
  - Register an `onDidUpdateValueForCharacteristic` listener, filtered by peripheral, service and characteristic, before calling `startNotification()`. The `startNotification()` promise resolves after the CCCD write.
  - Do not use `startNotificationWithBuffer()`: it concatenates values, which breaks framing.
  - `stopNotification()` disables notifications and also resolves after the CCCD write.
  - The library reports a failed CCCD write's GATT status only in the error message (`status=5`); the adapter parses it into `gattStatus`.
  - Values arrive as number arrays.
- **Disconnects:** on disconnect the library clears its queue without settling the promises of queued operations. The adapter fails every pending call when the disconnect event arrives. The library fails the operation in flight with "Device disconnected" before it emits the event, so the adapter treats that failure, and "not connected" failures, as the disconnect and signals it first.
- **Frame size:** Android has no MTU getter, so the host passes the value `requestMTU()` resolved with; the adapter subtracts 3.
- **Logging:** before 12.6.1, the library logs every written value in hex at debug level, whatever its options, including OTA commands; the driver requires 12.6.1 or newer. From 12.6.1 it does so only at the verbose log level (`start({ logLevel: 'verbose' })`, or the deprecated `debug: true`). The library has no log-level getter, so the driver's documentation requires hosts not to use the verbose level in production.
- No dependency patch.

**`react-native-ble-plx`**

- **Reads and writes:** use the host's connected `Device` and convert Base64 at the boundary. `writeCharacteristicWithoutResponseForService` resolves after `onCharacteristicWrite`, so no fence is needed.
- **Enabling notifications:**
  - `monitorCharacteristicForService()` gives no completion signal; its CCCD write follows the setup.
  - The library refuses CCCD writes through `writeDescriptorForService`, so the adapter cannot write the CCCD itself.
  - Instead, the adapter confirms enabling by re-reading the TX Stream CCCD with `readDescriptorForService` until it reads `01 00`.
  - Meanwhile it listens for the monitor's error callback. A failed CCCD write ends the subscription with an error, and the adapter fails the call with it and its GATT status rather than polling until the timeout.
- **Disabling notifications:** `remove()` on the subscription writes the disable value without a completion signal. The adapter confirms it the same way, until the CCCD reads `00 00`.
- **Frame size:** `device.mtu` is a snapshot taken at connect. Use the latest `requestMTUForDevice()` result instead.
- **Logging:** at `LogLevel.Verbose`, the library logs every notification and written value in hex, including artifact OPEN requests. The driver reads `manager.logLevel()` at attach and refuses to attach at that level, unless the host opts in for development with `allowVerboseLibraryLogging`.
- No dependency patch.

### React Native bridge

Both React Native drivers share a thin Android bridge module (a TurboModule) that implements `BleLink` by calling into the registered JS driver. MQTT, buffering and framing stay in Kotlin.

- **One gateway per process.** The module reuses the native gateway when initialized again with the same options, for example after a JS reload or from Headless JS while the UI runs. With different options, it shuts the old gateway down first; its buffered data is handed over through the flash tier.
- **Call order.** The bridge queues calls per Bluetooth address, across all links and attachments of that peripheral, and sends the next one to JS only once JS has settled the previous one or the link has dropped. A call the core abandoned on a timeout stays at the head of the queue until then. The driver hands each call to the library at once.
- **Late results.** Each JS link is registered once and serves all its attachments. Requests carry the link ID and a request ID, and state events an attachment ID, so a late result cannot complete another request or affect a replacement session.
- **One crossing per message.** A `writeWithoutResponse` call crosses into JS once with all frames of the message.
- **Ordering.** The driver numbers notifications per attachment, and Kotlin ends the attachment on a gap or reordering. Reordered fragments of equal length would otherwise reassemble into a corrupt payload of the right size. The numbering exists only on the bridge; BLE frames are unchanged.
- **Bridge invalidation.** When the React context is destroyed, for example by a JS reload, every JS-driven attachment ends as if disconnected.
  - The core cannot reach JS to disable notifications at that point, so the device may keep notifying into the void.
  - `react-native-ble-manager` disconnects every peripheral when its module is invalidated, so no link survives a reload; the host reconnects and attaches new links, and the core enables notifications again. A `react-native-ble-plx` driver that reuses a link after a reload disables TX Stream notifications before attaching it again.
- **Manifest.** The gateway AAR declares only `INTERNET`, `ACCESS_NETWORK_STATE` and the service of managed mode; hosts of a managed gateway declare the BLE, location and foreground-service permissions themselves. The bridge removes just the service, by its name. It removes no permission: a `tools:node="remove"` cannot be limited to one library, so it would also strip what other libraries declare — `react-native-ble-manager` declares `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and location.
- **Discovery.** Export the Spotflow service UUID from `GattProfile.SERVICE` for the host's existing scanner, and bridge `GattProfile.readDeviceId(link)` for selection before attaching. Do not add another scanner.
  - BLE PLX connections from before a reload are invisible to a new `BleManager`. Hosts call `destroy()` before reloading where they control it.
- **No logging of values.** TX Stream values include artifact OPEN requests, and RX Stream values include OTA commands; both carry download secrets and credential-bearing URLs. The driver never logs values, and keeps the libraries from logging them as each adapter describes.

## Lifecycle and validation

SimpleLink initially follows its existing JS lifecycle. Android screen-off relay is opt-in: the host can run the same driver and reconnect logic in Headless JS under a `connectedDevice` foreground service. Since Android 12, apps generally cannot start a foreground service from the background, so the host starts it while the app is visible or relies on a CompanionDeviceManager association. Native hosts can use `SpotflowGatewayService` or their own service. Neither adapter resumes relaying after process death or reboot. In device-key mode, data spilled to flash is only eligible for upload after a matching reattachment (see below).

Before freezing the API, test all three adapters on hardware:

- Relay while the host reads, writes, and subscribes on the same link.
- Send a multi-frame message with frames larger than 20 bytes, and one at 20-byte frames; both arrive intact.
- Relay a large core dump at 20-byte frames, which exercises notification ordering through the bridge.
- Send the first OTA command to a quiet device; it is acknowledged without retransmission, which confirms that notification enabling is confirmed and that writes return only once sent.
- Measure artifact-transfer throughput at 20-byte frames and at a negotiated MTU, at the slowest connection interval the host uses. No READ response comes close to its timeout.
- Keep the host's queue busy with its own operations for several seconds during an artifact transfer. The transfer resumes and the attachment survives.
- Disconnect mid-operation, with gateway calls queued in the library, and reattach. The attachment ends `DISCONNECTED` and no call hangs.
- Reattach the same address while the previous session's TX Stream disable is still queued behind the host's operations. The new session's notifications stay on.
- Detach. TX Stream notifications are off on the device, and the host's link and its subscriptions on other characteristics are intact.
- Reconnect a bonded device and check whether it sends TX Stream notifications before the core enables them. This decides whether every session starts by disabling them (see [Notifications](#notifications)).
- Run two attachments at once.
- Reload JS mid-session (React Native).
- Attach a device that requires encryption but is not bonded. The read fails with an error that tells the user to pair.
- In device-key mode, verify an encrypted Ingest Key read longer than a single ATT response at the default MTU.
- End an attachment in each way `AttachmentEnd.Reason` lists. `awaitEnd()` reports the reason.

Validate screen-off operation separately.

## Gateway creation and device selection

Kotlin callers use `SpotflowGateway.usingDeviceIngestKeys(context, options)` or construct `SpotflowGateway(context, credentials, options)` with `StaticIngestKey(ingestKey)` or a `CredentialsProvider`. `GatewayOptions` sets the broker endpoint, the RAM and flash limits, and OTA enablement and artifact size. Create one gateway per process. In JS:

```ts
// Only where Platform.OS === 'android'; createGateway() rejects elsewhere (E_UNSUPPORTED_PLATFORM),
// and with E_NOT_LINKED if the native module is not in the build.
const gateway = await createGateway(); // or { ingestKeySource: 'gateway', ingestKey }
const link = bleManagerLink(peripheralId, { mtu }); // peripheralId: the library's ID, on Android the Bluetooth address
if (allowedDeviceIds.has(await readDeviceId(link))) {
  const attachment = await gateway.attach(link);
  attachment.ended.then((end) => console.log(`relaying stopped: ${end.reason}`));
  // Later, request detach without disconnecting the BLE link:
  attachment.detach();
}
```

The bridge uses the Android application context and only wraps `ingestKey` in `StaticIngestKey` when the app explicitly sets `ingestKeySource: 'gateway'`. A JS gateway handle carries the ID of the native gateway it was created for: once that gateway is shut down or replaced (also from another React context), the handle's calls reject and its `shutdown()` does nothing. Awaiting `attach` waits for the bridge call, not BLE or MQTT readiness. `detach()` requests the Kotlin `detach()` and returns; `attachment.state` follows the Kotlin `Attachment.state`, and `ended` resolves with its final result.

The host owns device selection and calls `attach(link)` for each connection it wants to relay, including after reconnect. The reported device ID is not proof of identity; hosts should protect the link with encryption and restrict pairing. Managed discovery is a separate entry point that requires a `DeviceFilter`.

Device-key mode reads characteristic `26530007-81e5-4861-82ae-2c92e6887922` once per attachment, before enabling TX notifications. If no valid key is available, it ends with `DEVICE_INGEST_KEY` rather than falling back. The MQTT uplink holds the key in memory while it can upload, including after BLE disconnect. Device-key queues share the flash budget but are separate from the supplied-key queues: they are addressed by an Android Keystore-backed HMAC of endpoint, device ID, and key. After a restart, the same tuple must be supplied by a new attachment. Rotation parks the old backlog instead of uploading it to a potentially different workspace. Raw key bytes transit any JS driver; drivers must not log them.
