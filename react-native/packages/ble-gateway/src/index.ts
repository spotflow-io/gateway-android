import { Platform } from 'react-native';
import NativeSpotflowGateway from './NativeSpotflowGateway.ts';
import { Bridge, requireNative, type SpotflowGateway } from './bridge.ts';
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
  bridge ??= new Bridge(requireNative(Platform.OS, NativeSpotflowGateway));
  return bridge;
}

/**
 * Returns the process's gateway, creating it on first use. Calling it again with the same options (e.g.
 * after a JS reload) returns the running gateway; other options shut it down first and hand its unsent
 * data over through the flash buffer.
 * Uses each device's GATT ingest key by default. To supply a key, set `ingestKeySource: 'gateway'`.
 *
 * The gateway runs on Android only: check `Platform.OS === 'android'` before calling. Rejects with an
 * error whose `code` is `E_UNSUPPORTED_PLATFORM` elsewhere, and `E_NOT_LINKED` on Android if the native
 * module is not in the build.
 */
export async function createGateway(options: GatewayOptions = {}): Promise<SpotflowGateway> {
  return getBridge().configure(options);
}

/**
 * Reads the self-reported Spotflow device ID over `link`, without starting a relay. Use it to select
 * devices before attaching. The ID is not proof of identity: protect the link with pairing. Rejects like
 * {@link createGateway} where the gateway can't run.
 */
export async function readDeviceId(link: BleLink): Promise<string> {
  return getBridge().readDeviceId(link);
}
