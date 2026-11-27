import type {
  BleLink,
  Bytes,
  GattRef,
  NotificationListener,
} from '@spotflow/react-native-ble-gateway';

interface Subscription {
  remove(): void;
}

/** The parts of `react-native-ble-manager` the driver uses. */
export interface BleManagerApi {
  read(peripheralId: string, serviceUUID: string, characteristicUUID: string): Promise<number[]>;
  writeWithoutResponse(
    peripheralId: string,
    serviceUUID: string,
    characteristicUUID: string,
    data: number[],
    maxByteSize?: number,
    queueSleepTime?: number,
  ): Promise<void>;
  readRSSI(peripheralId: string): Promise<number>;
  startNotification(peripheralId: string, serviceUUID: string, characteristicUUID: string): Promise<void>;
  stopNotification(peripheralId: string, serviceUUID: string, characteristicUUID: string): Promise<void>;
  onDidUpdateValueForCharacteristic(
    callback: (event: { peripheral: string; service: string; characteristic: string; value: number[] }) => void,
  ): Subscription;
  onDisconnectPeripheral(callback: (event: { peripheral: string }) => void): Subscription;
}

export interface BleManagerLinkOptions {
  /**
   * The ATT MTU of the connection: the value `BleManager.requestMTU()` resolved with. 23 (20-byte
   * frames) if omitted. Report later exchanges with {@link BleManagerLink.setMtu}.
   */
  mtu?: number;
}

const SPOTFLOW_SERVICE = '26530001-81e5-4861-82ae-2c92e6887922';

/**
 * Whether `BleManager.retrieveServices()` found the Spotflow service on the peripheral. Use it to decide
 * whether to attach; the advertisement may not list the service.
 */
export function hasSpotflowService(peripheral: { services?: ReadonlyArray<{ uuid: string }> }): boolean {
  return peripheral.services?.some((service) => service.uuid.toLowerCase() === SPOTFLOW_SERVICE) ?? false;
}

const MIN_MTU = 23;
const MAX_FRAME_BYTES = 512;

/**
 * Failure messages of `react-native-ble-manager` that mean the link is gone. "Disconnect called" fails the
 * operation in flight when the host itself calls `BleManager.disconnect()`.
 */
const DISCONNECTED = /device disconnected|disconnect called|not connected|peripheral not found|gatt is null/i;

/** A GATT status in a `react-native-ble-manager` error message, e.g. `... status=5`. */
const GATT_STATUS = /status\s*=\s*(\d+)/i;

function linkError(message: string, gattStatus?: number): Error {
  return Object.assign(new Error(message), { name: 'BleLinkError', gattStatus });
}

function toError(failure: unknown): Error {
  const message = typeof failure === 'string' ? failure : failure instanceof Error ? failure.message : String(failure);
  const status = GATT_STATUS.exec(message)?.[1];
  return linkError(message, status === undefined ? undefined : Number(status));
}

function key(peripheral: string, service: string, characteristic: string): string {
  return `${peripheral.toUpperCase()}|${service.toLowerCase()}|${characteristic.toLowerCase()}`;
}

/**
 * Serves `BleLink`s over `react-native-ble-manager`. One driver per `BleManager`: it listens to the
 * library's value and disconnect events once, and routes them to the links.
 */
export class BleManagerDriver {
  private readonly manager: BleManagerApi;
  private readonly live = new Map<string, Set<BleManagerLink>>();
  private readonly subscriptions = new Map<string, { link: BleManagerLink; listener: NotificationListener }>();
  private events: Subscription[] = [];

  constructor(manager: BleManagerApi) {
    this.manager = manager;
  }

  /** A link over the connection to `peripheralId`, which must be connected with its services retrieved. */
  link(peripheralId: string, options: BleManagerLinkOptions = {}): BleManagerLink {
    this.listen();
    const link = new BleManagerLink(this, this.manager, peripheralId, options.mtu ?? MIN_MTU);
    const address = link.address;
    let links = this.live.get(address);
    if (!links) this.live.set(address, (links = new Set()));
    links.add(link);
    return link;
  }

  /** @internal */
  subscribe(link: BleManagerLink, ref: GattRef, listener: NotificationListener): void {
    this.subscriptions.set(key(link.address, ref.service, ref.characteristic), { link, listener });
  }

  /** @internal */
  unsubscribe(link: BleManagerLink, ref: GattRef): void {
    const k = key(link.address, ref.service, ref.characteristic);
    if (this.subscriptions.get(k)?.link === link) this.subscriptions.delete(k);
  }

  /** @internal */
  forget(link: BleManagerLink): void {
    const links = this.live.get(link.address);
    if (links?.delete(link) && links.size === 0) this.live.delete(link.address);
    for (const [k, subscription] of this.subscriptions) {
      if (subscription.link === link) this.subscriptions.delete(k);
    }
  }

  /**
   * Stops listening to the library's events. Every link of this driver ends as if disconnected, because
   * nothing would report its disconnect any more; the BLE connections stay up. A later {@link link} call
   * starts listening again.
   */
  dispose(): void {
    const events = this.events;
    this.events = [];
    events.forEach((subscription) => subscription.remove());
    [...this.live.values()].flatMap((links) => [...links]).forEach((link) => link.markDisconnected());
  }

  private listen(): void {
    if (this.events.length > 0) return;
    this.events = [
      this.manager.onDidUpdateValueForCharacteristic((event) => {
        this.subscriptions.get(key(event.peripheral, event.service, event.characteristic))?.listener.onValue(event.value);
      }),
      this.manager.onDisconnectPeripheral((event) => {
        const links = this.live.get(event.peripheral.toUpperCase());
        links && [...links].forEach((link) => link.markDisconnected());
      }),
    ];
  }
}

/** A {@link BleLink} over one `react-native-ble-manager` connection. Create a new one after reconnecting. */
export class BleManagerLink implements BleLink {
  readonly address: string;
  private readonly driver: BleManagerDriver;
  private readonly manager: BleManagerApi;
  private readonly peripheralId: string;
  private mtu: number;
  private down = false;
  private readonly pending = new Set<(error: Error) => void>();
  private readonly disconnectListeners = new Set<() => void>();
  private readonly mtuListeners = new Set<(bytes: number) => void>();

  /** Apps get links from {@link BleManagerDriver.link} or `bleManagerLink()`, not by constructing them. */
  constructor(driver: BleManagerDriver, manager: BleManagerApi, peripheralId: string, mtu: number) {
    this.driver = driver;
    this.manager = manager;
    this.peripheralId = peripheralId;
    this.address = peripheralId.toUpperCase();
    this.mtu = mtu;
  }

  get maxWritePayloadBytes(): number {
    return Math.min(Math.max(this.mtu - 3, MIN_MTU - 3), MAX_FRAME_BYTES);
  }

  /** Whether the link has dropped. A dropped link stays dropped. */
  get isDisconnected(): boolean {
    return this.down;
  }

  /** Reports a new ATT MTU, e.g. after another `BleManager.requestMTU()`. */
  setMtu(mtu: number): void {
    this.mtu = mtu;
    const bytes = this.maxWritePayloadBytes;
    this.mtuListeners.forEach((listener) => listener(bytes));
  }

  onMaxWritePayloadBytesChange(listener: (bytes: number) => void): () => void {
    this.mtuListeners.add(listener);
    return () => this.mtuListeners.delete(listener);
  }

  onDisconnect(listener: () => void): () => void {
    if (this.down) {
      listener();
      return () => {};
    }
    this.disconnectListeners.add(listener);
    return () => this.disconnectListeners.delete(listener);
  }

  read(ref: GattRef): Promise<Bytes> {
    return this.run(() => this.manager.read(this.peripheralId, ref.service, ref.characteristic));
  }

  writeWithoutResponse(ref: GattRef, frames: ReadonlyArray<ReadonlyArray<number>>): Promise<void> {
    return this.run(async () => {
      // Each promise resolves once its write is queued, not sent. Issuing them together keeps their
      // order: the library queues each write when it runs the call that carries it.
      // maxByteSize = the frame's length, so the library never splits a frame.
      const writes = frames.map((frame) =>
        this.manager.writeWithoutResponse(this.peripheralId, ref.service, ref.characteristic, frame as number[], frame.length, 0),
      );
      const outcomes = await Promise.allSettled(writes);
      // A fence: the library runs this only after the last write's onCharacteristicWrite, so resolving
      // after it means the frames have been handed to the stack. The RSSI itself is not used.
      try {
        await this.manager.readRSSI(this.peripheralId);
      } catch (failure) {
        if (DISCONNECTED.test(toError(failure).message)) throw failure;
      }
      const failed = outcomes.find((outcome): outcome is PromiseRejectedResult => outcome.status === 'rejected');
      if (failed) throw failed.reason;
    });
  }

  async enableNotifications(ref: GattRef, listener: NotificationListener): Promise<void> {
    if (this.down) throw linkError('device disconnected');
    // Before the CCCD write, so no early value is missed.
    this.driver.subscribe(this, ref, listener);
    try {
      await this.run(() => this.manager.startNotification(this.peripheralId, ref.service, ref.characteristic));
    } catch (error) {
      this.driver.unsubscribe(this, ref);
      throw error;
    }
  }

  async disableNotifications(ref: GattRef): Promise<void> {
    try {
      await this.run(() => this.manager.stopNotification(this.peripheralId, ref.service, ref.characteristic));
    } finally {
      this.driver.unsubscribe(this, ref);
    }
  }

  /**
   * The link dropped: signals it first, then fails every operation still pending, because the library
   * drops its queued operations on a disconnect without settling them.
   * @internal
   */
  markDisconnected(): void {
    if (this.down) return;
    this.down = true;
    this.driver.forget(this);
    const listeners = [...this.disconnectListeners];
    this.disconnectListeners.clear();
    this.mtuListeners.clear();
    listeners.forEach((listener) => listener());
    const pending = [...this.pending];
    this.pending.clear();
    pending.forEach((reject) => reject(linkError('device disconnected')));
  }

  /** Runs one library operation; settles it on a disconnect, and turns failures into link errors. */
  private run<T>(operation: () => Promise<T>): Promise<T> {
    if (this.down) return Promise.reject(linkError('device disconnected'));
    return new Promise<T>((resolve, reject) => {
      this.pending.add(reject);
      const settle = (): boolean => this.pending.delete(reject);
      let started: Promise<T>;
      try {
        started = operation();
      } catch (failure) {
        started = Promise.reject(failure);
      }
      started.then(
        (value) => {
          if (settle()) resolve(value);
        },
        (failure: unknown) => {
          if (!this.pending.has(reject)) return;
          const error = toError(failure);
          // The library fails in-flight operations before it reports the disconnect; report it first.
          if (DISCONNECTED.test(error.message)) {
            this.markDisconnected();
          } else if (settle()) {
            reject(error);
          }
        },
      );
    });
  }
}
