# @spotflow/react-native-ble-gateway

Relays Spotflow BLE devices to the Spotflow cloud from a React Native app that already owns the BLE
connections, such as SimpleLink Connect. Android only.

The Spotflow protocol, MQTT, store-and-forward buffering and OTA run in the native Kotlin gateway
(`io.spotflow:spotflow-ble-gateway`, bundled). The app keeps scanning, connecting, pairing, MTU
negotiation and reconnecting; a small JS driver performs the gateway's GATT operations through the app's
BLE library. For `react-native-ble-manager`, use
[`@spotflow/react-native-ble-gateway-ble-manager`](../ble-gateway-ble-manager).

## Requirements

- React Native 0.81 or newer, with the new architecture.
- Android `minSdk` 26 or higher.
- The gateway's MQTT client brings Netty jars with colliding `META-INF` entries, which the app must
  exclude when packaging (see below).

## Android setup

In an Expo or React Native app whose `android/app/build.gradle` reads packaging options from
`gradle.properties` (the Expo template does), add to `android/gradle.properties`:

```properties
android.minSdkVersion=26
android.packagingOptions.excludes=META-INF/INDEX.LIST,META-INF/DEPENDENCIES
android.packagingOptions.pickFirsts=META-INF/io.netty.versions.properties,META-INF/native-image/**
```

Otherwise, set `minSdkVersion = 26` in `android/build.gradle` and add to `android/app/build.gradle`:

```groovy
android {
    packagingOptions {
        resources {
            excludes += ["META-INF/INDEX.LIST", "META-INF/DEPENDENCIES"]
            pickFirsts += ["META-INF/io.netty.versions.properties", "META-INF/native-image/**"]
        }
    }
}
```

Apps generated with `expo prebuild` set the same values with `expo-build-properties`
(`android.minSdkVersion`, `android.packagingOptions`). The module adds its bundled Maven repository to
the build itself, and needs no permissions beyond the app's BLE permissions and `INTERNET`.

## Usage

```ts
import { createGateway, readDeviceId } from '@spotflow/react-native-ble-gateway';
import { bleManagerLink } from '@spotflow/react-native-ble-gateway-ble-manager';

const gateway = await createGateway(); // read each device's ingest key over GATT

// Once the app has connected, retrieved services and negotiated the MTU:
const link = bleManagerLink(peripheralId, { mtu });
if (allowedDeviceIds.has(await readDeviceId(link))) {
  const attachment = await gateway.attach(link);
  attachment.onStateChange((state) => console.log('relay', state.type));
  attachment.ended.then((end) => console.log('relay ended', end.reason, end.error?.message));
}
```

- `attach()` resolves when the relay has started, not when it is ready. `attachment.state` goes from
  `starting` to `ready` (the gateway can receive and buffer the device's messages, even while MQTT is
  disconnected) to `ended`.
- An attachment ends `DISCONNECTED` when the link drops. After the app reconnects, attach a **new** link.
  Buffered data keeps uploading in the meantime.
- `attachment.detach()` stops relaying and turns the device's notifications off; the BLE link stays up.
- Attaching the same peripheral again replaces its attachment (`REPLACED`).
- `gateway.diagnosticsSnapshot()` reports each device's relay and cloud state, message counters, OTA
  progress, and the buffer usage. It reads memory only, so polling it about once a second is fine.
- The reported device ID is not proof of identity; protect the link with pairing.

### Ingest keys

`createGateway()` (or `{ ingestKeySource: 'device' }`) reads the device's optional Ingest Key
characteristic once per attachment, before starting its stream. If the key is missing, unreadable or
invalid, the attachment ends with `DEVICE_INGEST_KEY`. Pair with trusted devices and keep BLE encryption
enabled. The key is not returned to the app or stored on disk, but its raw GATT bytes pass through the
JS driver, so don't log or record driver traffic.

For devices that don't provide a key, explicitly configure one for the gateway:

```ts
const gateway = await createGateway({ ingestKeySource: 'gateway', ingestKey });
```

In this mode the gateway never reads the key characteristic. `createGateway({ ingestKey })` is rejected;
add `ingestKeySource: 'gateway'` when migrating. Neither mode falls back to the other.

### Lifecycle

- There is one gateway per process. `createGateway()` with the same options returns the running gateway,
  for example after a JS reload. With different options it shuts the old gateway down first, keeping its
   unsent data in the flash buffer if enabled. The new gateway can only upload data from a compatible queue.
- When the React context is destroyed (a JS reload, for example), every attachment ends `DISCONNECTED`:
  no JS driver is left to serve the links. The app attaches again once it has reconnected.
- `gateway.shutdown()` ends all attachments (`DETACHED`) and stops the gateway.
- The package does not relay with the screen off or after the process dies. In device-key mode, data in
  flash waits for a device to reattach with the same ID, key and broker endpoint. A rotated key or switch
  to gateway-key mode does not upload that backlog under different credentials.

## Writing a driver

A driver implements `BleLink` (see `src/link.ts`) over the app's BLE library:

- `read`, `writeWithoutResponse`, `enableNotifications` and `disableNotifications` on characteristics
  given by `GattRef` (128-bit, lower-case UUIDs). `read` must return the complete value, including for a
  key longer than one ATT read response.
- The gateway issues one operation per peripheral at a time and waits for it to settle, also when it has
  stopped waiting for it after a timeout. Hand each operation to the library at once.
- `writeWithoutResponse` sends each frame as one write, in order, without splitting it, and resolves
  only once the last frame has reached the stack.
- `enableNotifications` registers the listener before writing the CCCD, and resolves once the CCCD write
  has completed. Deliver values in arrival order, without drops.
- Every operation settles. When the link drops, call the `onDisconnect` listeners first, then reject
  whatever the library will not settle itself.
- Reject with an `Error`; set a numeric `gattStatus` when the stack reported one (for example, 5 when the
  device requires pairing).
- Never log values: they can contain ingest keys or OTA download secrets.
