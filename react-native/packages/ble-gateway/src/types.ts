interface GatewaySettings {
  /** The MQTT broker. Spotflow's production broker by default. */
  endpoint?: { host: string; port?: number };
  /** RAM tier of the store-and-forward buffer, shared by all devices. 1 MiB by default. */
  ramBufferBytes?: number;
  /** Flash tier of the buffer; 0 for RAM only. 49 MiB by default. */
  flashBufferBytes?: number;
  /** Whether to relay OTA updates to devices that support them. On by default. */
  otaEnabled?: boolean;
  /** Largest OTA artifact the gateway downloads. 32 MiB by default. */
  otaMaxArtifactBytes?: number;
}

export type GatewayOptions = GatewaySettings & (
  | { ingestKeySource?: 'device'; ingestKey?: never }
  | { ingestKeySource: 'gateway'; ingestKey: string }
);

/**
 * The `code` of an error the gateway's functions reject with:
 *
 * - `E_UNSUPPORTED_PLATFORM`: `createGateway()` or `readDeviceId()` off Android.
 * - `E_NOT_LINKED`: the same, on Android without the native module in the build.
 * - `E_INVALID_OPTIONS`: `createGateway()` with an option missing or out of range.
 * - `E_GATEWAY`: `createGateway()` could not start the gateway.
 * - `E_NOT_CONFIGURED`: `attach()` or `diagnosticsSnapshot()` on a handle whose gateway has been shut down
 *   or replaced.
 * - `E_INVALID_ADDRESS`: `attach()` with a link whose `address` is not a Bluetooth address.
 * - `E_LINK_DOWN`: `attach()` or `readDeviceId()` with a link that has disconnected.
 * - `E_BLE`: `readDeviceId()` failed on the link; the error is a `BleLinkError`, with `gattStatus` where
 *   the stack reported one.
 */
export type GatewayErrorCode =
  | 'E_UNSUPPORTED_PLATFORM'
  | 'E_NOT_LINKED'
  | 'E_INVALID_OPTIONS'
  | 'E_GATEWAY'
  | 'E_NOT_CONFIGURED'
  | 'E_INVALID_ADDRESS'
  | 'E_LINK_DOWN'
  | 'E_BLE';

/**
 * Why a device is not (fully) relayed: how an attachment failed, or what keeps its cloud connection from
 * working. `CLOUD_UNREACHABLE` only appears as a `cloudError`; `PAIRING_REQUIRED` means the device needs an
 * encrypted link: pair (bond) the phone with it, then attach again.
 */
export interface DeviceError {
  kind:
    | 'AUTH_REJECTED'
    | 'CLOUD_UNREACHABLE'
    | 'BLUETOOTH'
    | 'PAIRING_REQUIRED'
    | 'DUPLICATE_DEVICE'
    | 'DEVICE_INGEST_KEY';
  /** For logs and display; not meant to be parsed. */
  message: string;
  /** The GATT status the Bluetooth stack reported for the failed operation, if any. */
  gattStatus: number | null;
}

/** How an attachment ended. Except after `DISCONNECTED`, the host's link is still up. */
export interface AttachmentEnd {
  /**
   * - `DETACHED`: the host detached it or shut the gateway down.
   * - `REPLACED`: the same peripheral was attached again.
   * - `DISCONNECTED`: the link dropped, or the React context was destroyed (e.g. a JS reload).
   * - `FAILED`: an operation failed or timed out, or the device or cloud refused the session; see `error`.
   */
  reason: 'DETACHED' | 'REPLACED' | 'DISCONNECTED' | 'FAILED';
  error?: DeviceError;
}

/**
 * Lifecycle of one attachment. `ready` means the gateway can receive and buffer the device's messages;
 * it does not imply an MQTT connection.
 */
export type AttachmentState =
  | { type: 'starting' }
  | { type: 'ready'; deviceId: string }
  | { type: 'ended'; result: AttachmentEnd };

export interface FirmwareTransfer {
  /** The artifact's size, or null while it is being prepared. */
  sizeBytes: number | null;
  /** End of the latest block prepared for a read. Not a delivery acknowledgement. */
  lastReadEndOffsetBytes: number;
}

/** Diagnostic data for one peripheral. MQTT may stay connected after the attachment ends. */
export interface DeviceReport {
  address: string;
  deviceId: string | null;
  /** State of the latest attachment for this address. */
  attachmentState: AttachmentState;
  cloudConnected: boolean;
  forwarded: number;
  cloudMessagesReceived: number;
  pendingMessages: number;
  ramBytes: number;
  flashBytes: number;
  /** Why the cloud connection is not working (`CLOUD_UNREACHABLE` or `AUTH_REJECTED`); retried. */
  cloudError: DeviceError | null;
  otaEnabled: boolean;
  activeFirmwareTransfer: FirmwareTransfer | null;
}

export interface BufferUsage {
  ramBytes: number;
  ramMaxBytes: number;
  flashBytes: number;
  flashMaxBytes: number;
  /** False if the persistent buffer could not be opened. */
  flashAvailable: boolean;
}

/** A point-in-time diagnostic view, built from memory without BLE, network or disk I/O. */
export interface GatewayReport {
  devices: DeviceReport[];
  bufferUsage: BufferUsage;
}
