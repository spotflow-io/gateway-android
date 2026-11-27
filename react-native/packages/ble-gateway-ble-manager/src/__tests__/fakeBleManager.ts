import type { BleManagerApi } from '../driver.ts';

type ValueEvent = { peripheral: string; service: string; characteristic: string; value: number[] };

/**
 * Models the Android implementation of react-native-ble-manager 12.6–13.x (Peripheral.java): one command
 * at a time, from a FIFO queue, each started on the next tick (mainHandler.post). A write without response
 * runs as a command that queues the actual write at the back of the queue and settles its callback at once;
 * the write completes on onCharacteristicWrite. On a disconnect the library fails the callback of the
 * command in flight, drops everything queued without settling it, then emits the disconnect event.
 */
export class FakeBleManager implements BleManagerApi {
  /** GATT operations in the order they reached the stack. */
  readonly gatt: string[] = [];
  /** Fails reads with this message (e.g. 'Error reading x status=5'). */
  readFailure: string | undefined;
  /** Fails CCCD writes with this message. */
  cccdFailure: string | undefined;
  /** Completes stack operations only when the test calls {@link completeStackOperation}. */
  holdStack = false;
  connected = true;

  private readonly queue: Array<() => void> = [];
  private busy = false;
  private inFlightReject: ((reason: string) => void) | undefined;
  private heldCompletion: (() => void) | undefined;
  private valueListeners: Array<(event: ValueEvent) => void> = [];
  private disconnectListeners: Array<(event: { peripheral: string }) => void> = [];

  read(peripheralId: string, _service: string, characteristic: string): Promise<number[]> {
    return this.command((resolve, reject) => {
      this.gatt.push(`read ${characteristic}`);
      this.stack(() => (this.readFailure ? reject(this.readFailure) : resolve([1, 2, 3])));
    }, peripheralId);
  }

  writeWithoutResponse(
    peripheralId: string,
    _service: string,
    _characteristic: string,
    data: number[],
    maxByteSize = 20,
  ): Promise<void> {
    if (data.length > maxByteSize) throw new Error('the fake does not split values');
    return new Promise<void>((resolve, reject) => {
      if (!this.connected) return reject('Peripheral not connected');
      this.enqueue(() => {
        // The value is queued behind whatever is queued by now; the callback settles at once.
        this.enqueue(() => {
          this.gatt.push(`write ${data.join(',')}`);
          this.stack(() => {});
        });
        resolve();
        this.completed();
      });
    });
  }

  readRSSI(peripheralId: string): Promise<number> {
    return this.command((resolve) => {
      this.gatt.push('rssi');
      this.stack(() => resolve(-60));
    }, peripheralId);
  }

  startNotification(peripheralId: string, _service: string, characteristic: string): Promise<void> {
    return this.cccd(peripheralId, `notify on ${characteristic}`);
  }

  stopNotification(peripheralId: string, _service: string, characteristic: string): Promise<void> {
    return this.cccd(peripheralId, `notify off ${characteristic}`);
  }

  onDidUpdateValueForCharacteristic(callback: (event: ValueEvent) => void) {
    this.valueListeners.push(callback);
    return { remove: () => (this.valueListeners = this.valueListeners.filter((l) => l !== callback)) };
  }

  onDisconnectPeripheral(callback: (event: { peripheral: string }) => void) {
    this.disconnectListeners.push(callback);
    return { remove: () => (this.disconnectListeners = this.disconnectListeners.filter((l) => l !== callback)) };
  }

  notify(event: ValueEvent): void {
    this.valueListeners.forEach((listener) => listener(event));
  }

  /** Completes the stack operation held by {@link holdStack}. */
  completeStackOperation(): void {
    const complete = this.heldCompletion;
    this.heldCompletion = undefined;
    complete?.();
  }

  disconnect(peripheral: string): void {
    this.dropConnection('Device disconnected');
    this.disconnectListeners.forEach((listener) => listener({ peripheral }));
  }

  /**
   * The app calls `BleManager.disconnect()`: the library fails the command in flight with its own message,
   * and the disconnect event reaches JS only after that failure.
   */
  hostDisconnect(peripheral: string): void {
    this.dropConnection('Disconnect called before the command completed');
    setImmediate(() => this.disconnectListeners.forEach((listener) => listener({ peripheral })));
  }

  private dropConnection(inFlightFailure: string): void {
    this.connected = false;
    this.inFlightReject?.(inFlightFailure);
    this.inFlightReject = undefined;
    this.heldCompletion = undefined;
    this.queue.length = 0;
    this.busy = false;
  }

  private cccd(peripheralId: string, what: string): Promise<void> {
    return this.command((resolve, reject) => {
      this.gatt.push(what);
      this.stack(() => (this.cccdFailure ? reject(this.cccdFailure) : resolve()));
    }, peripheralId);
  }

  /** A command whose callback settles when its stack operation completes. */
  private command<T>(
    run: (resolve: (value: T) => void, reject: (reason: string) => void) => void,
    _peripheralId: string,
  ): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      if (!this.connected) return reject('Peripheral not connected');
      this.enqueue(() => {
        this.inFlightReject = reject;
        run(resolve, reject);
      });
    });
  }

  /** The stack completes the operation in flight, now or when the test says so. */
  private stack(callback: () => void): void {
    const complete = () => {
      this.inFlightReject = undefined;
      callback();
      this.completed();
    };
    if (this.holdStack) {
      this.heldCompletion = complete;
    } else {
      setImmediate(complete);
    }
  }

  private enqueue(command: () => void): void {
    this.queue.push(command);
    this.next();
  }

  private completed(): void {
    this.queue.shift();
    this.busy = false;
    this.next();
  }

  private next(): void {
    if (this.busy) return;
    const command = this.queue[0];
    if (!command) return;
    this.busy = true;
    setImmediate(command);
  }
}
