import type {
  NativeAttachmentState,
  NativeLinkRequest,
  Spec,
} from './NativeSpotflowGateway.ts';
import {
  BleLinkError,
  GattProfile,
  gattStatusOf,
  messageOf,
  type BleLink,
  type Bytes,
  type GattRef,
  type NotificationListener,
} from './link.ts';
import type {
  AttachmentEnd,
  AttachmentState,
  DeviceError,
  GatewayOptions,
  GatewayReport,
} from './types.ts';

/** The parts of the TurboModule the bridge uses; tests substitute a fake. */
export type NativeGateway = Omit<Spec, keyof import('react-native').TurboModule>;

/**
 * The native module, or a coded error saying why there is none: `E_UNSUPPORTED_PLATFORM` off Android,
 * `E_NOT_LINKED` on Android when the module is not in the build (installed without rebuilding the app, or
 * excluded from autolinking). The app decides beforehand whether to use the gateway; this only makes a
 * mismatch loud.
 */
export function requireNative(os: string, native: NativeGateway | null | undefined): NativeGateway {
  if (os !== 'android') {
    throw Object.assign(new Error('The Spotflow gateway runs on Android only'), { code: 'E_UNSUPPORTED_PLATFORM' });
  }
  if (native == null) {
    throw Object.assign(
      new Error('The SpotflowGateway native module is not in this build: rebuild the app after installing the package'),
      { code: 'E_NOT_LINKED' },
    );
  }
  return native;
}

/** A device relayed over a link; returned by {@link SpotflowGateway.attach}. */
export class Attachment {
  /** The peripheral's Bluetooth address. */
  readonly address: string;

  /** Resolves with how the attachment ended, after best-effort cleanup (at most about 2 s). */
  readonly ended: Promise<AttachmentEnd>;

  private current: AttachmentState = { type: 'starting' };
  private readonly listeners = new Set<(state: AttachmentState) => void>();
  private readonly requestDetach: () => void;
  private resolveEnded!: (end: AttachmentEnd) => void;

  /** @internal */
  constructor(address: string, requestDetach: () => void) {
    this.address = address;
    this.requestDetach = requestDetach;
    this.ended = new Promise((resolve) => {
      this.resolveEnded = resolve;
    });
  }

  /** The latest known state of this attachment. */
  get state(): AttachmentState {
    return this.current;
  }

  /** Calls `listener` with each later state. Returns a function that unsubscribes. */
  onStateChange(listener: (state: AttachmentState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Stops relaying this attachment, not a newer one for the same peripheral, and turns the device's
   * notifications off if it can. Returns at once; {@link ended} says when it is done. The BLE link stays
   * up, and buffered data is still uploaded.
   */
  detach(): void {
    if (this.current.type !== 'ended') this.requestDetach();
  }

  /** @internal */
  update(state: AttachmentState): void {
    if (this.current.type === 'ended') return;
    this.current = state;
    this.listeners.forEach((listener) => listener(state));
    if (state.type === 'ended') {
      this.listeners.clear();
      this.resolveEnded(state.result);
    }
  }
}

/**
 * The process's gateway. Create it with `createGateway()`.
 *
 * A handle stands for the gateway it was created for: once that gateway has been shut down, or replaced
 * by `createGateway()` with other options (also from another React context), the handle's calls reject
 * and its `shutdown()` does nothing. Calling `createGateway()` again returns a handle to the current one.
 */
export class SpotflowGateway {
  private readonly bridge: Bridge;
  private readonly id: number;

  /** @internal */
  constructor(bridge: Bridge, id: number) {
    this.bridge = bridge;
    this.id = id;
  }

  /**
   * Relays the device behind `link`. Resolves once the relay has started in the background, not when it
   * is ready: watch {@link Attachment.state}. Attaching a peripheral that is already relayed replaces
   * that attachment.
   *
   * Rejects if the link has disconnected, its address is invalid, or this gateway was shut down or
   * replaced.
   */
  attach(link: BleLink): Promise<Attachment> {
    return this.bridge.attach(this.id, link);
  }

  /**
   * Current diagnostics: per-device relay and cloud state, counters, and buffer usage. Rejects if this
   * gateway was shut down or replaced.
   */
  async diagnosticsSnapshot(): Promise<GatewayReport> {
    let report: GatewayReport;
    try {
      report = (await this.bridge.native.diagnosticsSnapshot(this.id)) as GatewayReport;
    } catch (error) {
      throw asError(error);
    }
    report.devices.forEach((device) => {
      device.attachmentState = toState(device.attachmentState as NativeStateShape);
    });
    return report;
  }

  /**
   * Shuts this gateway down: attachments end as `DETACHED`, and unsent data is kept in the flash buffer
   * for the next gateway. Returns at once. Does nothing if the gateway was shut down or replaced already.
   */
  shutdown(): void {
    this.bridge.native.shutdown(this.id);
  }
}

/** A link the native module knows, by its ID. It lives until the link disconnects. */
class Registration {
  readonly linkId: number;
  readonly link: BleLink;
  private readonly native: NativeGateway;
  private seq = 0;
  down = false;

  /** One listener for every attachment of the link; the module numbers values per link. */
  readonly listener: NotificationListener;

  constructor(linkId: number, link: BleLink, native: NativeGateway) {
    this.linkId = linkId;
    this.link = link;
    this.native = native;
    this.listener = {
      onValue: (value) => {
        if (!this.down) native.pushNotification(linkId, this.seq++, toArray(value));
      },
      onError: (error) => {
        if (!this.down) native.notificationError(linkId, messageOf(error), gattStatusOf(error) ?? -1);
      },
    };
  }

  serve(request: NativeLinkRequest): void {
    const ref: GattRef = { service: request.service, characteristic: request.characteristic };
    let operation: Promise<Bytes | void>;
    try {
      operation = this.start(request.op, ref, request.frames);
    } catch (error) {
      operation = Promise.reject(error);
    }
    operation.then(
      (value) => this.native.resolveRequest(this.linkId, request.requestId, value ? toArray(value) : []),
      (error) => {
        // Some drivers include values in error messages. Only pass a status for credential reads.
        const message = request.characteristic === GattProfile.INGEST_KEY ? 'Ingest Key read failed' : messageOf(error);
        this.native.rejectRequest(this.linkId, request.requestId, message, gattStatusOf(error) ?? -1);
      },
    );
  }

  private start(op: string, ref: GattRef, frames: ReadonlyArray<ReadonlyArray<number>>): Promise<Bytes | void> {
    switch (op) {
      case 'read':
        return this.link.read(ref);
      case 'writeWithoutResponse':
        return this.link.writeWithoutResponse(ref, frames);
      case 'enableNotifications':
        return this.link.enableNotifications(ref, this.listener);
      case 'disableNotifications':
        return this.link.disableNotifications(ref);
      default:
        throw new BleLinkError(`unsupported link operation '${op}'`);
    }
  }
}

/** Connects one JS context to the native module. */
export class Bridge {
  readonly native: NativeGateway;
  private readonly links = new WeakMap<BleLink, Registration>();
  private readonly registrations = new Map<number, Registration>();
  private readonly attachments = new Map<number, Attachment>();
  private nextAttachmentId = 0;

  constructor(native: NativeGateway) {
    this.native = native;
    native.onLinkRequest((request) => this.serve(request));
    native.onAttachmentState((event) => this.onAttachmentState(event));
  }

  async configure(options: GatewayOptions): Promise<SpotflowGateway> {
    return new SpotflowGateway(this, await this.native.configure(options));
  }

  async attach(gatewayId: number, link: BleLink): Promise<Attachment> {
    const registration = this.register(link);
    const attachmentId = ++this.nextAttachmentId;
    const attachment = new Attachment(link.address, () => this.native.detach(attachmentId));
    // Before the native call: its first state event may arrive before the call resolves.
    this.attachments.set(attachmentId, attachment);
    try {
      await this.native.attach(gatewayId, registration.linkId, attachmentId);
    } catch (error) {
      this.attachments.delete(attachmentId);
      throw asError(error);
    }
    return attachment;
  }

  async readDeviceId(link: BleLink): Promise<string> {
    try {
      return await this.native.readDeviceId(this.register(link).linkId);
    } catch (error) {
      throw asError(error);
    }
  }

  /** The link's registration; a link is registered once and serves all its attachments. */
  private register(link: BleLink): Registration {
    const known = this.links.get(link);
    if (known && !known.down) return known;
    const linkId = this.native.openLink(link.address, link.maxWritePayloadBytes);
    const registration = new Registration(linkId, link, this.native);
    this.links.set(link, registration);
    this.registrations.set(linkId, registration);
    const stopWatchingMtu = link.onMaxWritePayloadBytesChange?.((bytes) => {
      if (!registration.down) this.native.setMaxWritePayloadBytes(linkId, bytes);
    });
    // May run at once, if the link is already down.
    link.onDisconnect(() => {
      if (registration.down) return;
      registration.down = true;
      stopWatchingMtu?.();
      this.registrations.delete(linkId);
      this.native.linkDisconnected(linkId);
    });
    return registration;
  }

  private serve(request: NativeLinkRequest): void {
    const registration = this.registrations.get(request.linkId);
    if (registration) {
      registration.serve(request);
    } else {
      this.native.rejectRequest(request.linkId, request.requestId, 'the link has disconnected', -1);
    }
  }

  private onAttachmentState(event: NativeAttachmentState): void {
    const attachment = this.attachments.get(event.attachmentId);
    if (!attachment) return;
    const state = toState(event);
    if (state.type === 'ended') this.attachments.delete(event.attachmentId);
    attachment.update(state);
  }
}

/** An attachment state as the module reports it, in events and in diagnostics. */
type NativeStateShape = Omit<NativeAttachmentState, 'attachmentId'>;

function toState(event: NativeStateShape): AttachmentState {
  switch (event.type) {
    case 'ready':
      return { type: 'ready', deviceId: event.deviceId ?? '' };
    case 'ended': {
      const result = event.result;
      const end: AttachmentEnd = { reason: (result?.reason ?? 'DETACHED') as AttachmentEnd['reason'] };
      if (result?.error) {
        const { kind, message, gattStatus } = result.error;
        end.error = { kind: kind as DeviceError['kind'], message, gattStatus: gattStatus ?? null };
      }
      return { type: 'ended', result: end };
    }
    default:
      return { type: 'starting' };
  }
}

function toArray(value: Bytes): number[] {
  return Array.isArray(value) ? (value as number[]) : Array.from(value);
}

/** Native rejections carry the GATT status in `userInfo`; surface it as a {@link BleLinkError}. */
function asError(error: unknown): Error {
  const info = (error as { userInfo?: { gattStatus?: unknown } } | null)?.userInfo;
  const code = (error as { code?: unknown } | null)?.code;
  if (code === 'E_BLE') {
    return new BleLinkError(messageOf(error), gattStatusOf(info));
  }
  return error instanceof Error ? error : new Error(messageOf(error));
}
