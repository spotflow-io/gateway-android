# Changelog

## Unreleased

### Added

- **`GattBridge(delegate)`**: register `bridge.callback` with `connectGatt` and pass the app's own
  `BluetoothGattCallback` as the delegate; it receives every event (connection, service discovery, MTU,
  the results of its own operations) once the bridge has seen it. Before, an app that registered the
  bridge's callback directly never learned when to attach.
- **`DeviceError.gattStatus`** — the Bluetooth stack's status for a failed operation — and
  **`DeviceError.Kind.PAIRING_REQUIRED`** for a device that needs an encrypted link the phone doesn't
  have. Such failures were `BLUETOOTH`, so an app that wanted to ask the user to pair had to parse the
  message. Both reach React Native too.

### Changed

- **The library's manifest declares only `INTERNET` and `ACCESS_NETWORK_STATE`** (and the managed
  service). Apps running a `ManagedGateway` declare the BLE and location permissions, and apps using
  `SpotflowGatewayService` the foreground-service and notification ones, in their own manifest; see
  "Permissions and manifest" in the README. Merged in from the library, they capped the app's location
  permission at API 30 and marked its scans `neverForLocation`, and the React Native bridge had to remove
  them with `tools:node="remove"` — which also strips them from other libraries' manifests, e.g.
  `react-native-ble-manager`'s. The bridge now removes only the gateway's own service.
- **Reported types are plain classes**, not data classes: `GatewayReport`, `DeviceReport`,
  `BufferUsage`, `FirmwareTransfer`, `DeviceError`, `AttachmentEnd`, `AttachmentState.Ready`/`Ended`
  and `ScanStatus` compare by value as before, but can't be destructured (`val (a, b) = report`), so
  fields can be added later without breaking apps. Read the properties by name.
- **`DeviceReport.cloudError` reports `AUTH_REJECTED`** while uploads of a device's backlog are paused
  on a rejected key. It used to stay empty, so a background upload that kept failing was invisible.
- **A diagnostics report keeps at most 64 devices that are no longer attached** (those whose attachments
  ended last), so a host attaching ever new peripherals doesn't grow it without bound. `detach()` drops
  the device's row, as before.
- `GattProfile`'s UUIDs are static fields for Java (`GattProfile.SERVICE`, was `getSERVICE()`).
- `ManagedGateway.startScanning()` is no longer annotated `@RequiresPermission(BLUETOOTH_SCAN,
  BLUETOOTH_CONNECT)`. Those permissions exist only from Android 12, so for an app supporting older
  versions lint demanded a check that always fails there (Android 11 and older need location instead).
  Its documentation lists what each version needs. It never threw for a missing permission: discovery
  reports it as `ScanState.RETRYING` and starts once the permission is granted.
- The library is documented as Kotlin-first: its extension points are suspending functions.
- React Native: **`isSupported()` is removed.** It returned false both off Android and on Android when
  the native module was missing from the build, so a forgotten rebuild silently turned the gateway off.
  Check `Platform.OS === 'android'` (and, in an app that builds with and without the module, its own
  build flag) before using the gateway. `createGateway()` and `readDeviceId()` now reject — never throw —
  with `code` `E_UNSUPPORTED_PLATFORM` off Android, or `E_NOT_LINKED` when the module is missing.
- React Native: a gateway handle acts on the gateway it was created for only. After that one is shut
  down or replaced by `createGateway()` with other options (also from another React context), its
  `attach()` and `diagnosticsSnapshot()` reject and its `shutdown()` does nothing; it used to act on
  whatever gateway was current.
- The `BleLink` contract says what the gateway does: it waits for one call at a time, but may issue the
  next while one it stopped waiting for still runs, so adapters queue calls per connection.

### Fixed

- **Desired configuration could be acknowledged to the broker without reaching the device.** After three
  failed writes — timeouts, or the link dropping mid-write — it was dropped and acknowledged. Failed
  writes are now retried, and after three in a row the configuration waits, unacknowledged, for the
  device's next session.
- Desired configuration and OTA commands the broker resends after an MQTT reconnect are matched to the
  queued messages in order, so each is written once. Before, only a resend of the last queued message
  was recognized: with two queued, both were written twice, the older one last for a moment.
- A gateway restarted in place (`SpotflowGatewayService.restart`, React Native reconfiguring) could lose
  its OTA artifact files to the gateway it replaced, which deletes its own while shutting down; each
  gateway now keeps them in a directory of its own.
- `GattBridge` reports an operation Android refuses to issue (or a missing characteristic) as a
  `BleLinkException`, as `BleLink` requires, rather than an `IllegalStateException`.

## 0.4.0

The library is split in two layers. `SpotflowGateway` is now the core: it relays devices over connections
the app attaches, and does not scan. Scanning, connecting and reconnecting moved to `ManagedGateway` in
`io.spotflow.ble.managed`, a layer built on the core's public API. See [Migrating from
0.3.x](#migrating-from-03x).

### Added

- **`GatewayOptions`**, the one place for a gateway's settings: the broker (`endpoint`, a
  `CloudEndpoint`, for a non-production broker), the RAM and flash buffer sizes, and OTA. Both gateways
  take it as their last argument; settings added later become options, not new arguments. Build it with
  `GatewayOptions { … }` in Kotlin or `GatewayOptions.Builder` in Java.

- **`SpotflowGateway.attach(link: BleLink)`** relays a device over a BLE connection the app owns through
  any BLE library, not only a `BluetoothGatt` (see "Attaching a link" in the README). The app implements
  `BleLink` (reads, writes without response, notifications, the disconnect); the gateway keeps the
  protocol and the cloud side. `Attachment.awaitEnd()` returns the terminal `AttachmentEnd`.
- **`GattProfile.advertisesService(scanResult)`** recognizes the Spotflow UUID in a scan result, and
  **`GattProfile.readDeviceId(link)`** reads the reported ID before the app decides whether to attach.
- **`ManagedGateway`**: the standalone gateway — discovery, the `DeviceFilter`, reconnecting with backoff
  — as a layer on top of the core relay. It filters on the ID before starting a relay and uses that
  same ID to relay. `ManagedGateway` exposes status and `attach(link)` directly, without exposing its
  core gateway. Host-owned links are selected by the app.
- **`GattBridge`** adapts a host-owned, ready `BluetoothGatt` to a `BleLink`. Both native and custom
  links go through `SpotflowGateway.attach`.
- **React Native packages** in `react-native/`: `@spotflow/react-native-ble-gateway` runs the gateway as
  a TurboModule (Android, React Native 0.81+) whose links a JS driver serves, and
  `@spotflow/react-native-ble-gateway-ble-manager` is the driver for `react-native-ble-manager` 12.6.1+,
  for apps such as SimpleLink Connect. The host app needs `minSdk` 26 and packaging excludes for Netty's
  `META-INF` entries; see the package READMEs.
- **Device-provisioned ingest keys.** The React Native gateway reads each key from the device's Ingest
  Key characteristic by default. To supply a key, use `createGateway({ ingestKeySource: 'gateway', ingestKey })`.
  Kotlin hosts can opt in with `SpotflowGateway.usingDeviceIngestKeys(context)`. Device keys aren't stored;
  after process restart, flash backlog waits for an attachment with matching credentials.

### Changed

- The host or managed layer connects, discovers services and negotiates the MTU before attaching.
  `Attachment.state` reports `Starting`, `Ready(deviceId)`, or `Ended(result)`, independently of the
  physical BLE and MQTT connections. `awaitEnd()` returns the same terminal result.
- Both gateways expose `diagnosticsSnapshot()` instead of dashboard flows. The report includes each
  tracked device's attachment state, cloud state, counters, OTA progress, and shared buffer usage.
  Snapshots are built on demand without I/O; the demo reads them while visible.
- `Attachment.detach()` is the only public way to stop a host attachment. It returns immediately;
  use `awaitEnd()` for the outcome after best-effort TX stream cleanup (up to 2 s). The gateway does
  not disconnect the host's link. `shutdown()` requests shutdown in the background and rejects new
  attachments.
- Background uploads retry rejected credentials automatically with a capped backoff, even if the device
  does not return. Managed `retry()` wakes paused relay attempts without clearing exclusions made with `ignoreDevice(address)`.
  Use `allowDevice(address)` to explicitly remove an exclusion.
- A gateway operation that times out ends the session (the managed gateway reconnects with backoff)
  instead of letting the session carry on. The GATT queue stays held for an operation nobody waits for
  any more until its callback arrives or the link drops, so the next operation is not rejected as busy.
- A message's write is timed as a whole, from when it is issued: 10 s plus 100 ms per frame (it used to
  be 10 s per frame, counted once the frame held the GATT queue).
- An attached session that fails — e.g. on a rejected key or a duplicate device ID — ends; the app
  decides whether to attach the device again. The managed gateway retries as before.
- The types the gateway reports — `GatewayReport`, `DeviceReport`, `BufferUsage`, `DeviceError`,
  `FirmwareTransfer`, `AttachmentEnd`, `ScanStatus` — cannot be created or `copy`d by the app.

### Removed

- `MqttConfig`, `SpotflowTopics`, `PublishQos` and `OtaConfig`. The broker connection is always TLS with
  QoS 1 and a 60 s keep-alive, to the platform's topics. The buffer and OTA settings moved to
  `GatewayOptions`.
- `requestedMtu` of `ManagedGateway`: it always asks for the largest MTU (517).
- Gateway-wide `status`: apps can observe Android connectivity directly. Check `diagnosticsSnapshot().bufferUsage.flashAvailable`
  for persistent buffer availability. The internal connectivity monitor still pauses and resumes uploads.
- RSSI polling and reporting: host apps can read signal strength through their own BLE stack. Collecting
  diagnostics no longer schedules GATT calls.

### Fixed

- Frames are at most 512 bytes, the protocol's limit on a GATT value; at an MTU of 517 they could be 514.

### Migrating from 0.3.x

- **Managed mode** is `ManagedGateway`, with the arguments `SpotflowGateway` took:

  ```kotlin
  // before
  val gateway = SpotflowGateway(context, StaticIngestKey(key), myFilter, mqttConfig)
  gateway.startScanning()
  gateway.devices
  // after
  val managed = ManagedGateway(context, StaticIngestKey(key), myFilter, options)   // see Settings below
  managed.startScanning()
  managed.diagnosticsSnapshot()     // also scanStatus, attach()
  ```

  `startScanning`, `stopScanning`, `shutdown` and `isBluetoothEnabled` are on `ManagedGateway`.
  Use `ignoreDevice(address)` / `allowDevice(address)` for exclusions and `retry()` to wake paused
  attempts. Scan state moved from `GatewayStatus.scan` / `scanError` to
  `ManagedGateway.scanStatus` (`ScanStatus.state` / `error`).
- **Attach-only apps** create the core with `SpotflowGateway(context, credentials, options)` and can use
  `GattProfile.readDeviceId(link)` to select devices before attaching. Replace `newAttachment()` with `GattBridge()`, wire
  `bridge.callback`, then connect, discover services and finish any MTU exchange yourself before calling
  `gateway.attach(bridge.link(gatt))`. Use `bridge.runExclusive` for shared GATT access and the returned
  `Attachment` for `state`, `detach()` and `awaitEnd()`. In diagnostic reports the old `ble`, `received`
  and `diskBytes` fields are now `attachmentState`, `cloudMessagesReceived` and `flashBytes`. Read `activeFirmwareTransfer`
  instead of `otaTransfer`; its `lastReadEndOffsetBytes` estimates progress, not confirmed delivery.
- **Moved to `io.spotflow.ble.managed`:** `DeviceFilter`, `ScanState`, `SpotflowGatewayService` (was
  `io.spotflow.ble.service`). The service hosts a `ManagedGateway`: its `gatewayFactory` returns one, and
  `gateway` / `gatewayFlow` hold one.
- **Settings** move to `GatewayOptions`:

  | Before | After |
  |---|---|
  | `MqttConfig.ramBufferMaxBytes` | `ramBufferBytes` |
  | `MqttConfig.bufferMaxBytes` (RAM + flash) | `flashBufferBytes` (flash only: subtract the RAM size) |
  | `MqttConfig.host`, `port` | `endpoint = CloudEndpoint(host, port)` |
  | `OtaConfig.enabled` | `otaEnabled` |
  | `OtaConfig.maxArtifactBytes` | `otaMaxArtifactBytes` |

  `useTls`, `keepAliveSeconds`, `qos` and `topics` have no replacement: the connection always uses
  their defaults.
- **Reported types** can't be created any more: e.g. use nullable `ScanStatus?` until the first value arrives.

## 0.3.1

### Changed

- **Apps on Kotlin 2.1 or newer can use the library.** It was compiled with the latest language version,
  so its metadata (2.3) could be read only by Kotlin 2.2 or newer, and apps on an older Kotlin failed to
  compile ("Module was compiled with an incompatible version of Kotlin"). It is now built with language
  and API version 2.1, and its POM asks for `kotlin-stdlib` 2.1.21 rather than 2.3.21. Kotlin 2.1 is
  the minimum because kotlinx-coroutines 1.11 is built with Kotlin 2.2.

## 0.3.0

Over-the-air firmware updates through the gateway (Spotflow BLE protocol 1.1).

### Added

- **OTA updates** for devices whose Capabilities advertise them, on by default. The gateway subscribes to
  the device's OTA commands (`ota-cbor-c2d`) after its session metadata, and again for every BLE session
  and MQTT connection so the cloud resends an unfinished update; writes each command as `OTA_C2D` with
  `NEEDS_ACK`, retransmitting on `NACK` or silence, and acknowledges it to the broker only once the device
  has; and publishes the device's results (`OTA_D2C`) to `ota-cbor-d2c`.
- **Artifact transfer** — the gateway answers the device's `ARTIFACT_REQUEST` (open, read, close): it
  downloads the firmware over HTTPS with the `OtaSecret` from the request, once per artifact and shared
  by all devices, into the app's cache directory, and serves the device's reads from it. Downloads
  outlive the session that started them, resume after an interruption, and never log URLs or secrets.
- `OtaConfig` (the new last parameter of `SpotflowGateway`): turn OTA off, or change the largest artifact
  the gateway downloads (32 MiB by default).
- `GatewayDeviceState.otaEnabled` and `otaTransfer` (an `OtaTransfer`: artifact size, bytes delivered);
  the demo app shows them.
- `SpotflowTopics.otaCommands` and `otaResults`.

### Changed

- A device that advertises OTA now gets updates through the gateway; until now its OTA messages were
  dropped. Pass `OtaConfig(enabled = false)` to keep the old behaviour.
- The gateway can use up to four artifacts' worth of the app's cache directory (`spotflow-ota/`) while
  devices update; it is emptied on `shutdown()`.
- Source compatible; the `SpotflowGateway`, `GatewayDeviceState` and `SpotflowTopics` constructors gained
  parameters, so code built against 0.2.x has to be recompiled.

## 0.2.1

Support for version 1.1 of the Spotflow BLE protocol, which comes with the current Spotflow cloud and
the Device SDK's BLE OTA support. The MQTT side (broker, credentials, topics) is unchanged. OTA updates
through the gateway are not relayed yet.

### Added

- **Protocol 1.1** — the Capabilities characteristic is read as major and minor version plus the feature
  byte (bit 0: OTA supported); 1.0 devices, which expose only the version, keep working. A device built
  with OTA is relayed for telemetry and configuration, and the log says that its OTA is not relayed.
- The new message types (`OTA_D2C`, `OTA_C2D`, `ARTIFACT_REQUEST`, `ARTIFACT_RESPONSE`) and the
  `ACK`/`NACK` control frames are recognized and dropped on arrival, instead of being reassembled (taking
  up to 64 KiB each and competing with telemetry for the in-flight slots) and dropped at the end.

### Fixed

- **Session metadata** is published again after an MQTT reconnect, ahead of everything else: the cloud
  applies it per connection, so telemetry sent after a reconnect lost its context (build ID, run ID).
- A message whose fragments disagree on `NEEDS_ACK` is dropped as malformed, as protocol 1.1 requires.
- A device that requires an encrypted link (the Device SDK's default when built with OTA) fails with an
  error saying it must be paired, not with a bare GATT status.

## 0.2.0

A hardening release. The library's public API changed in a few places; see
[Migrating from 0.1.x](#migrating-from-01x).

### Fixed

- **MQTT** — a connect that is cancelled no longer leaves a connected client behind that never
  subscribed (desired configuration then never arrived). A connection lost while messages are in flight
  is noticed at once instead of after a 20 s timeout, and the client no longer re-sends them behind the
  upload loop's back. A connection lost before the subscription is confirmed fails the connect instead of
  hanging for about an hour.
- **Discovery** — a scan that fails is retried with backoff (it used to stay dead until Bluetooth was
  toggled), and a running scan is restarted before Android throttles it.
- **Storage** — the buffer's database is opened in the background, not on the thread that creates the
  gateway. If it can't be opened the gateway relays with the RAM tier only instead of crashing.
- **Restart** — data the previous gateway writes to flash after the new one has started is picked up.
  Restarting with the flash tier switched off no longer wipes the backlog.
- **Sessions** — a BLE session lets go of its cloud link before the link is handed on, and two sessions
  can no longer both claim one device ID.
- **Attach mode** — callbacks only complete the operation they answer; detaching turns the device's
  stream off; a link that drops while the stream is being started is not reported as ready.
- **Poison messages** — a connection lost to the network, or a broker over its quota, never counts
  against a message; with several messages in flight, a bad one no longer takes its neighbours with it.
- **ProGuard / R8** — the library's consumer rules now let a shrunk build through (it failed on missing
  SLF4J classes).

### Added

- `SpotflowGateway.status`: whether discovery is running (and why not), connectivity, storage.
- `DeviceError`: a typed error per device (`AUTH_REJECTED`, `CLOUD_UNREACHABLE`, `BLUETOOTH`,
  `DUPLICATE_DEVICE`).
- `SpotflowGateway.retryStopped()`: retries devices stopped by a rejected key or the device filter,
  devices that were detached, and stored data whose upload gave up.
- `SpotflowLog`: route, filter or silence the library's logging.
- `SpotflowGatewayService.gatewayFlow`: follow the hosted gateway across restarts.
- Releases attach a Maven repository zip, so the library's dependencies resolve.

### Changed

- Up to 16 messages are uploaded at once; flash is read and cleared in batches.
- Uploads wait for a network instead of retrying without one.
- The scan runs low-power after its first two minutes; signal strength is read only while `devices` is
  being collected; the MQTT keep-alive default is 60 s (was 30 s).
- A rejected ingest key is retried after a pause that grows from 1 to 15 minutes (it used to stop for
  good); a device the filter rejects is asked about again after 10 minutes.
- `detach(address)` keeps a managed device out until `retryStopped()` (the scan used to reconnect it).
- Buffer sizes include an estimate of the per-message overhead.
- The library no longer declares Bluetooth LE as a *required* feature.
- Licensed under the Business Source License 1.1, like the Spotflow Device SDK.
- Built with AGP 8.13, Kotlin 2.3 and Gradle 8.14; the MQTT client is HiveMQ 1.4.2.

### Migrating from 0.1.x

- **Constructor** — `deviceFilter` is now required and comes third:

  ```kotlin
  // before
  SpotflowGateway(context, StaticIngestKey(key), mqttConfig)
  // after
  SpotflowGateway(context, StaticIngestKey(key), myFilter, mqttConfig)   // DeviceFilter.AcceptAll to relay everything
  ```

- **Attach mode** — `attach(gatt)` is replaced by a handle you wire up before starting:

  ```kotlin
  // before
  val connection = gateway.attach(gatt)            // then forward callbacks to connection.gattCallback
  // after
  val attachment = gateway.newAttachment()
  // pass attachment.gattCallback to connectGatt, or forward every callback to it, then:
  attachment.start(gatt)
  ```

  `connection.runExclusive { }` becomes `attachment.runExclusive { }`; `close()` becomes `detach()`.

- **Errors** — `GatewayDeviceState.error` is a `DeviceError?` (was `String?`): use `error.kind` to decide
  and `error.message` to display.
- **MQTT configuration** — `MqttConfig.qos` is the library's own `PublishQos` (was HiveMQ's `MqttQos`).
- **Internal types** — `MqttUplink`, `Uplink`, `SpotflowScanner`, `ManagedBleConnection`,
  `AttachedBleConnection`, `BleConnection`, `FrameCodec`, `Message` and `MessageType` are no longer
  public. `GattProfile` (the UUIDs) still is.
- **Dependencies** — if you take the bare `.aar`, see "Adding the library" in the README for what to add;
  the MQTT client is now `com.hivemq:hivemq-mqtt-client:1.4.2`.
- **Manifest** — if your app needs Bluetooth LE, declare the feature as required yourself.
