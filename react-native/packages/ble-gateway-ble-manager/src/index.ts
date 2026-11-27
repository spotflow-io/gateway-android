import BleManager from 'react-native-ble-manager';
import { BleManagerDriver, type BleManagerLink, type BleManagerLinkOptions } from './driver.ts';

export { BleManagerDriver, BleManagerLink, hasSpotflowService } from './driver.ts';
export type { BleManagerApi, BleManagerLinkOptions } from './driver.ts';

let driver: BleManagerDriver | undefined;

/**
 * A Spotflow `BleLink` over the `react-native-ble-manager` connection to `peripheralId`. Call it once the
 * peripheral is connected, its services are retrieved and any MTU exchange has finished:
 *
 * ```ts
 * await BleManager.connect(peripheralId);
 * await BleManager.retrieveServices(peripheralId);
 * const mtu = await BleManager.requestMTU(peripheralId, 247);
 * const attachment = await gateway.attach(bleManagerLink(peripheralId, { mtu }));
 * ```
 *
 * `peripheralId` is the ID `react-native-ble-manager` gives the peripheral (`Peripheral.id`, from a scan,
 * `getConnectedPeripherals()` or `getBondedPeripherals()`): on Android, its Bluetooth address. It becomes
 * the link's `address`, by which the gateway tells peripherals apart.
 *
 * A link serves one connection: after a reconnect, create a new one.
 */
export function bleManagerLink(peripheralId: string, options?: BleManagerLinkOptions): BleManagerLink {
  driver ??= new BleManagerDriver(BleManager);
  return driver.link(peripheralId, options);
}
