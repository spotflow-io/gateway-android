import { Platform } from 'react-native';
import NativeSpotflowGateway from './NativeSpotflowGateway.ts';
import { Bridge, type SpotflowGateway } from './bridge.ts';
import type { BleLink } from './link.ts';
import type { GatewayOptions } from './types.ts';

export { Attachment, SpotflowGateway } from './bridge.ts';
export { BleLinkError, GattProfile } from './link.ts';
export type { BleLink, Bytes, GattRef, NotificationListener } from './link.ts';
export type {
  AttachmentEnd,
  AttachmentState,
  BufferUsage,
  DeviceError,
  DeviceReport,
  FirmwareTransfer,
  GatewayOptions,
  GatewayReport,
} from './types.ts';

let bridge: Bridge | undefined;

function getBridge(): Bridge {
  if (!NativeSpotflowGateway) {
    throw new Error(
      Platform.OS === 'android'
        ? 'The SpotflowGateway native module is not linked: rebuild the app after installing the package'
        : 'The Spotflow gateway runs on Android only',
    );
  }
  bridge ??= new Bridge(NativeSpotflowGateway);
  return bridge;
}

/** Whether the gateway can run here: on Android, with the native module linked. */
export function isSupported(): boolean {
  return Platform.OS === 'android' && NativeSpotflowGateway != null;
}

/**
 * Returns the process's gateway, creating it on first use. Configuring it again with the same options
 * (e.g. after a JS reload) keeps the running gateway; other options shut it down first and hand its
 * unsent data over through the flash buffer.
 * Uses each device's GATT ingest key by default. To supply a key, set `ingestKeySource: 'gateway'`.
 */
export function createGateway(options: GatewayOptions = {}): Promise<SpotflowGateway> {
  return getBridge().configure(options);
}

/**
 * Reads the self-reported Spotflow device ID over `link`, without starting a relay. Use it to select
 * devices before attaching. The ID is not proof of identity: protect the link with pairing.
 */
export function readDeviceId(link: BleLink): Promise<string> {
  return getBridge().readDeviceId(link);
}
