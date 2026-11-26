# @spotflow/react-native-ble-gateway-ble-manager

A [`react-native-ble-manager`](https://github.com/innoveit/react-native-ble-manager) driver for
[`@spotflow/react-native-ble-gateway`](../ble-gateway): relays Spotflow devices over the connections
the app already holds through `BleManager`, without a second connection and without patching the
library. Android only.

## Install

```sh
pnpm add @spotflow/react-native-ble-gateway @spotflow/react-native-ble-gateway-ble-manager
```

Requires `react-native-ble-manager` 12.6.1 or newer. Earlier versions log every written value at debug
level, and the gateway writes OTA commands that carry download secrets. Do not start `BleManager` with
`logLevel: 'verbose'` (or the deprecated `debug: true`) in production, for the same reason.

Then set up Android as the [gateway package](../ble-gateway/README.md#android-setup) describes.

## Usage

```ts
import BleManager from 'react-native-ble-manager';
import { createGateway, isSupported, readDeviceId } from '@spotflow/react-native-ble-gateway';
import { bleManagerLink, hasSpotflowService } from '@spotflow/react-native-ble-gateway-ble-manager';

const gateway = isSupported() ? await createGateway() : undefined;

await BleManager.connect(id);
const peripheral = await BleManager.retrieveServices(id);
const mtu = await BleManager.requestMTU(id, 247);

if (gateway && hasSpotflowService(peripheral)) {
  const link = bleManagerLink(id, { mtu });
  const attachment = await gateway.attach(link);
  attachment.ended.then((end) => console.log('Spotflow relay ended', end.reason));
}
```

- Create the link once the peripheral is connected, its services are retrieved, and the MTU exchange has
  finished. Report a later exchange with `link.setMtu(mtu)`.
- A link serves one connection. The driver notices the disconnect from `BleManager`'s events and the
  attachment ends `DISCONNECTED`; after reconnecting, create and attach a new link.
- By default, the device must expose its ingest key over GATT. Use encrypted, paired links. To use an
  app-supplied key instead, pass `{ ingestKeySource: 'gateway', ingestKey }` to `createGateway`.
- To select devices by ID, call `readDeviceId(link)` before attaching.
- The app's own operations keep working on the same connection. Keep each one short: the time a gateway
  operation waits behind them counts towards its 10 s budget.

### How it maps to the library

- Reads use `BleManager.read`.
- Each frame is one `writeWithoutResponse` call, with `maxByteSize` set to the frame's length so the
  library never splits it. The library resolves those calls once the writes are queued, so the driver
  follows them with `readRSSI` as a queue fence and reports the write done when the fence completes. The
  RSSI value is not used.
- Notifications use `startNotification` and `stopNotification`, which resolve after the CCCD write, and
  the `onDidUpdateValueForCharacteristic` event, filtered to the link's peripheral and characteristic.
  `startNotificationWithBuffer` is not used: it concatenates values, which breaks framing.
- The library drops queued operations on a disconnect without settling them; the driver fails them
  after reporting the disconnect. It also treats a "not connected" failure as a disconnect.
- GATT statuses are parsed from the library's error messages (`status=5`), so the gateway can tell the
  user to pair a device that requires encryption.

## SimpleLink Connect

To relay devices from SimpleLink Connect (Expo 54, React Native 0.81, new architecture):

1. Install both packages, and update `react-native-ble-manager` to at least 12.6.1. The lockfile pins
   12.4.5, which logs written values.
2. Add to `android/gradle.properties`, each on its own line (the file has no trailing newline):

   ```properties
   android.minSdkVersion=26
   android.packagingOptions.excludes=META-INF/INDEX.LIST,META-INF/DEPENDENCIES
   android.packagingOptions.pickFirsts=META-INF/io.netty.versions.properties,META-INF/native-image/**
   ```

   This raises the app's minimum Android version from 7.0 to 8.0.
3. Create the gateway once, when the app starts, if `isSupported()` (the app also runs on iOS).
4. Attach where the device screen has connected and retrieved services, if `hasSpotflowService()` says
   the device has the Spotflow service. Pass the MTU the app negotiated, and call `link.setMtu()` after
   `handleRequestMTU` exchanges a new one.
5. Detach when the user leaves the device, or keep relaying while it stays connected. Attach a new link
   after every reconnect.
