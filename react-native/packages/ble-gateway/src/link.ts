/** UUIDs of the Spotflow Observability GATT service, lower case. */
export const GattProfile = {
  SERVICE: '26530001-81e5-4861-82ae-2c92e6887922',
  CAPABILITIES: '26530002-81e5-4861-82ae-2c92e6887922',
  DEVICE_ID: '26530003-81e5-4861-82ae-2c92e6887922',
  SESSION_METADATA: '26530004-81e5-4861-82ae-2c92e6887922',
  TX_STREAM: '26530005-81e5-4861-82ae-2c92e6887922',
  RX_STREAM: '26530006-81e5-4861-82ae-2c92e6887922',
  INGEST_KEY: '26530007-81e5-4861-82ae-2c92e6887922',
} as const;

/** A characteristic, by its service and its own UUID (128-bit, lower case). */
export interface GattRef {
  readonly service: string;
  readonly characteristic: string;
}

export type Bytes = ReadonlyArray<number> | Uint8Array;

/** Receives the notifications of one characteristic; see {@link BleLink.enableNotifications}. */
export interface NotificationListener {
  /** A notified value. Call serially, in arrival order. */
  onValue(value: Bytes): void;
  /** The subscription failed; the attachment ends. */
  onError(error: unknown): void;
}

/**
 * One connected, service-discovered BLE link, served by a driver over the host's BLE library. The
 * gateway relays the Spotflow device behind it; the host keeps permissions, scanning, connecting, MTU
 * negotiation and reconnecting.
 *
 * The gateway issues at most one operation per peripheral at a time and waits for it to settle before
 * issuing the next, also across links of the same peripheral. A driver therefore hands each operation to
 * its library at once, and must settle every operation: when the library drops queued operations on a
 * disconnect, the driver rejects them itself, after signalling the disconnect.
 *
 * Reject with an `Error`; give it a numeric `gattStatus` when the stack reported a GATT status. Never log
 * values: they may carry ingest keys or OTA download secrets.
 */
export interface BleLink {
  /** The peripheral's Bluetooth address, upper case, as Android's `BluetoothDevice.getAddress()`. */
  readonly address: string;

  /** The largest value one write carries: the ATT MTU minus 3, or 20 while it is unknown. */
  readonly maxWritePayloadBytes: number;

  /** Calls `listener` with each new {@link maxWritePayloadBytes}. Returns a function that unsubscribes. */
  onMaxWritePayloadBytesChange?(listener: (bytes: number) => void): () => void;

  /**
   * Calls `listener` once the link has dropped; at once if it already has. Returns a function that
   * unsubscribes.
   */
  onDisconnect(listener: () => void): () => void;

  /** Reads the complete characteristic value, using a GATT long read if needed. */
  read(ref: GattRef): Promise<Bytes>;

  /**
   * Writes each frame as one write without response, in order, without splitting any. Resolves once the
   * last one has been handed to the stack, not merely queued behind the host's operations. A value the
   * stack rejects may be dropped without an error.
   */
  writeWithoutResponse(ref: GattRef, frames: ReadonlyArray<ReadonlyArray<number>>): Promise<void>;

  /**
   * Registers `listener`, then writes the CCCD to enable notifications, and resolves once that write has
   * completed. A failed CCCD write rejects, with its GATT status.
   */
  enableNotifications(ref: GattRef, listener: NotificationListener): Promise<void>;

  /** Writes the CCCD to disable notifications and resolves once that write has completed. */
  disableNotifications(ref: GattRef): Promise<void>;
}

/** A BLE operation failed. `gattStatus` is the GATT status, where the stack reported one. */
export class BleLinkError extends Error {
  readonly gattStatus: number | undefined;

  constructor(message: string, gattStatus?: number) {
    super(message);
    this.name = 'BleLinkError';
    this.gattStatus = gattStatus;
  }
}

export function messageOf(error: unknown): string {
  if (typeof error === 'string') return error;
  if (error instanceof Error) return error.message;
  return String(error);
}

export function gattStatusOf(error: unknown): number | undefined {
  const status = (error as { gattStatus?: unknown } | null | undefined)?.gattStatus;
  return typeof status === 'number' && Number.isInteger(status) && status >= 0 ? status : undefined;
}
