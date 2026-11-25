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
 * await BleManager.connect(id);
 * await BleManager.retrieveServices(id);
 * const mtu = await BleManager.requestMTU(id, 247);
 * const attachment = await gateway.attach(bleManagerLink(id, { mtu }));
 * ```
 *
 * A link serves one connection: after a reconnect, create a new one.
 */
export function bleManagerLink(peripheralId: string, options?: BleManagerLinkOptions): BleManagerLink {
  driver ??= new BleManagerDriver(BleManager);
  return driver.link(peripheralId, options);
}
