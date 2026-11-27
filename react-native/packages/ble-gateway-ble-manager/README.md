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

The gateway runs on Android only, so the app checks the platform itself before using it:

```ts
import { Platform } from 'react-native';
import BleManager from 'react-native-ble-manager';
import { createGateway } from '@spotflow/react-native-ble-gateway';
import { bleManagerLink, hasSpotflowService } from '@spotflow/react-native-ble-gateway-ble-manager';

const spotflow = Platform.OS === 'android';

// When the app starts: creating the gateway early lets it upload data buffered by an earlier run.
if (spotflow) createGateway().catch((error) => console.error('Spotflow gateway unavailable', error));

// Where the app connects to a peripheral it found (see "Peripheral IDs" below):
async function connect(peripheralId: string) {
  await BleManager.connect(peripheralId);
  const peripheral = await BleManager.retrieveServices(peripheralId);
  const mtu = await BleManager.requestMTU(peripheralId, 247);

  if (spotflow && hasSpotflowService(peripheral)) {
    const gateway = await createGateway(); // same options: the gateway created at start
    const link = bleManagerLink(peripheralId, { mtu });
    const attachment = await gateway.attach(link);
    attachment.ended.then((end) => console.log('Spotflow relay ended', end.reason, end.error?.kind));
  }
}
```

- `createGateway()` returns the running gateway whenever it is called with the same options, so the app
  can call it where it needs the gateway rather than pass one around. On Android it rejects with
  `E_NOT_LINKED` if the native module is not in the build: rebuild the app after installing the package.
  An app that builds for Android both with and without the module (as SimpleLink Connect does) adds its
  own build flag to `spotflow`.
- Create the link once the peripheral is connected, its services are retrieved, and the MTU exchange has
  finished. Report a later exchange with `link.setMtu(mtu)`.
- A link serves one connection. The driver notices the disconnect from `BleManager`'s events and the
  attachment ends `DISCONNECTED`; after reconnecting, create and attach a new link.
- By default, the device must expose its ingest key over GATT. Use encrypted, paired links. To use an
  app-supplied key instead, pass `{ ingestKeySource: 'gateway', ingestKey }` to `createGateway`.
- To select devices by ID, call `readDeviceId(link)` before attaching.
- The app's own operations keep working on the same connection. Keep each one short: the time a gateway
  operation waits behind them counts towards its 10 s budget.

### Peripheral IDs

`peripheralId` is the ID `react-native-ble-manager` gives a peripheral, the same one the app passes to
`BleManager.connect`. The app gets it wherever it finds the peripheral: from `Peripheral.id` in a scan
(`onDiscoverPeripheral`, `getDiscoveredPeripherals()`), or from `getConnectedPeripherals()` or
`getBondedPeripherals()`:

```ts
import { GattProfile } from '@spotflow/react-native-ble-gateway';

const discovery = BleManager.onDiscoverPeripheral((peripheral) => addToDeviceList(peripheral.id, peripheral.name));
await BleManager.scan({ serviceUUIDs: [GattProfile.SERVICE], seconds: 5 });
// later: discovery.remove();
```

- On Android the ID is the peripheral's Bluetooth address, e.g. `C4:BE:84:12:34:56`. The link reports
  it, upper-cased, as its `address`, and the gateway uses that to tell peripherals apart: attaching the
  same address again replaces the earlier attachment, and diagnostics list devices by it. Pass the ID as
  the library gave it: the gateway rejects a link whose address is not a Bluetooth address.
- It is not the Spotflow device ID, which the device reports over GATT: read that with
  `readDeviceId(link)` to choose which devices to relay, before attaching.
- Filtering the scan by `GattProfile.SERVICE` finds only peripherals that advertise the Spotflow
  service. One that doesn't may still have it, so an app that scans more broadly checks
  `hasSpotflowService()` after `retrieveServices` instead.

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

1. Install both packages, with `react-native-ble-manager` 12.6.1 or newer (earlier versions log written
   values).
2. Add to `android/gradle.properties`, each on its own line (the file has no trailing newline):

   ```properties
   android.minSdkVersion=26
   android.packagingOptions.excludes=META-INF/INDEX.LIST,META-INF/DEPENDENCIES
   android.packagingOptions.pickFirsts=META-INF/io.netty.versions.properties,META-INF/native-image/**
   ```

   This raises the app's minimum Android version from 7.0 to 8.0.
3. Create the gateway when the app starts, in builds that include it. The app also runs on iOS, and
   builds for Android without the native module unless `EXPO_PUBLIC_SIMPLELINK_SPOTFLOW=1` (its
   `react-native.config.js` excludes the package from autolinking otherwise). Gradle and the JS bundle
   read the same variable — Expo inlines `EXPO_PUBLIC_` variables into the bundle — so the check is:

   ```ts
   const spotflow = Platform.OS === 'android' && process.env.EXPO_PUBLIC_SIMPLELINK_SPOTFLOW === '1';
   ```

   A build where the two ever disagree fails with `E_NOT_LINKED` instead of hiding the feature.
4. Attach where the device screen has connected and retrieved services, if `hasSpotflowService()` says
   the device has the Spotflow service. Pass the MTU the app negotiated, and call `link.setMtu()` after
   `handleRequestMTU` exchanges a new one.
5. Detach when the user leaves the device, or keep relaying while it stays connected. Attach a new link
   after every reconnect.
