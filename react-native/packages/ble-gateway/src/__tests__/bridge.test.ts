import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import type { NativeAttachmentState, NativeLinkRequest } from '../NativeSpotflowGateway.ts';
import { Bridge, type NativeGateway } from '../bridge.ts';
import { BleLinkError, GattProfile, type BleLink, type GattRef, type NotificationListener } from '../link.ts';

const ADDRESS = 'AA:BB:CC:DD:EE:FF';
const TX = { service: GattProfile.SERVICE, characteristic: GattProfile.TX_STREAM };
const subscription = { remove: () => {} } as unknown as ReturnType<NativeGateway['onLinkRequest']>;

/** Records what the bridge tells the module, and lets the test play the module's events. */
class FakeNative {
  readonly calls: unknown[][] = [];
  attachFailure: unknown;
  readDeviceIdResult: Promise<string> = Promise.resolve('device-1');
  private requestHandler: ((request: NativeLinkRequest) => void) | undefined;
  private stateHandler: ((event: NativeAttachmentState) => void) | undefined;
  private nextLinkId = 0;

  readonly module = {
    configure: async (options: unknown) => void this.calls.push(['configure', options]),
    shutdown: () => void this.calls.push(['shutdown']),
    openLink: (address: string, bytes: number) => {
      this.calls.push(['openLink', address, bytes]);
      return ++this.nextLinkId;
    },
    setMaxWritePayloadBytes: (linkId: number, bytes: number) => void this.calls.push(['mtu', linkId, bytes]),
    linkDisconnected: (linkId: number) => void this.calls.push(['disconnected', linkId]),
    resolveRequest: (linkId: number, requestId: number, value: ReadonlyArray<number>) =>
      void this.calls.push(['resolve', linkId, requestId, [...value]]),
    rejectRequest: (linkId: number, requestId: number, message: string, status: number) =>
      void this.calls.push(['reject', linkId, requestId, message, status]),
    pushNotification: (linkId: number, seq: number, value: ReadonlyArray<number>) =>
      void this.calls.push(['value', linkId, seq, [...value]]),
    notificationError: (linkId: number, message: string, status: number) =>
      void this.calls.push(['notificationError', linkId, message, status]),
    readDeviceId: (linkId: number) => {
      this.calls.push(['readDeviceId', linkId]);
      return this.readDeviceIdResult;
    },
    attach: async (linkId: number, attachmentId: number) => {
      this.calls.push(['attach', linkId, attachmentId]);
      // The module may emit the first state before the call resolves.
      this.state({ attachmentId, type: 'starting' });
      if (this.attachFailure) throw this.attachFailure;
    },
    detach: (attachmentId: number) => void this.calls.push(['detach', attachmentId]),
    diagnosticsSnapshot: async (): Promise<object | null> => null,
    onLinkRequest: (handler: (request: NativeLinkRequest) => void) => {
      this.requestHandler = handler;
      return subscription;
    },
    onAttachmentState: (handler: (event: NativeAttachmentState) => void) => {
      this.stateHandler = handler;
      return subscription;
    },
  } satisfies NativeGateway;

  request(request: Omit<NativeLinkRequest, 'frames'> & { frames?: number[][] }): void {
    this.requestHandler?.({ frames: [], ...request });
  }

  state(event: NativeAttachmentState): void {
    this.stateHandler?.(event);
  }

  last(): unknown[] | undefined {
    return this.calls[this.calls.length - 1];
  }
}

/** A driver whose operations the test settles. */
class FakeLink implements BleLink {
  readonly address = ADDRESS;
  maxWritePayloadBytes = 20;
  readonly ops: string[] = [];
  listener: NotificationListener | undefined;
  result: Promise<unknown> = Promise.resolve();
  private disconnectListeners: Array<() => void> = [];
  private mtuListeners: Array<(bytes: number) => void> = [];
  down = false;

  onDisconnect(listener: () => void) {
    if (this.down) listener();
    else this.disconnectListeners.push(listener);
    return () => {};
  }
  onMaxWritePayloadBytesChange(listener: (bytes: number) => void) {
    this.mtuListeners.push(listener);
    return () => (this.mtuListeners = this.mtuListeners.filter((l) => l !== listener));
  }
  read(ref: GattRef) {
    this.ops.push(`read ${ref.characteristic}`);
    return this.result as Promise<number[]>;
  }
  writeWithoutResponse(_ref: GattRef, frames: ReadonlyArray<ReadonlyArray<number>>) {
    this.ops.push(`write ${JSON.stringify(frames)}`);
    return this.result as Promise<void>;
  }
  enableNotifications(_ref: GattRef, listener: NotificationListener) {
    this.ops.push('enable');
    this.listener = listener;
    return this.result as Promise<void>;
  }
  disableNotifications() {
    this.ops.push('disable');
    return this.result as Promise<void>;
  }
  drop() {
    this.down = true;
    this.disconnectListeners.forEach((listener) => listener());
  }
  setMtu(bytes: number) {
    this.maxWritePayloadBytes = bytes;
    this.mtuListeners.forEach((listener) => listener(bytes));
  }
}

const settle = () => new Promise<void>((resolve) => setImmediate(resolve));

function setUp() {
  const native = new FakeNative();
  return { native, bridge: new Bridge(native.module) };
}

describe('Bridge', () => {
  it('passes default device mode and explicit gateway mode to native', async () => {
    const { native, bridge } = setUp();
    await bridge.configure({});
    assert.deepEqual(native.last(), ['configure', {}]);
    await bridge.configure({ ingestKeySource: 'gateway', ingestKey: 'test-key' });
    assert.deepEqual(native.last(), ['configure', { ingestKeySource: 'gateway', ingestKey: 'test-key' }]);
  });

  it('forwards an ingest key read without propagating driver error text', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    await bridge.attach(link);
    const ref = { service: GattProfile.SERVICE, characteristic: GattProfile.INGEST_KEY };
    link.result = Promise.resolve([33, 126]);
    native.request({ linkId: 1, requestId: 1, op: 'read', ...ref });
    await settle();
    assert.deepEqual(native.last(), ['resolve', 1, 1, [33, 126]]);
    link.result = Promise.reject(new BleLinkError('sensitive value from driver', 5));
    native.request({ linkId: 1, requestId: 2, op: 'read', ...ref });
    await settle();
    assert.deepEqual(native.last(), ['reject', 1, 2, 'Ingest Key read failed', 5]);
  });

  it('registers a link once and serves all its attachments over it', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    await bridge.readDeviceId(link);
    await bridge.attach(link);
    await bridge.attach(link);
    assert.deepEqual(
      native.calls.filter((c) => c[0] === 'openLink' || c[0] === 'attach' || c[0] === 'readDeviceId'),
      [['openLink', ADDRESS, 20], ['readDeviceId', 1], ['attach', 1, 1], ['attach', 1, 2]],
    );
  });

  it('performs requests on the link and settles them by ID', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    await bridge.attach(link);

    link.result = Promise.resolve(new Uint8Array([7, 8]));
    native.request({ linkId: 1, requestId: 1, op: 'read', ...{ service: GattProfile.SERVICE, characteristic: GattProfile.DEVICE_ID } });
    await settle();
    assert.deepEqual(native.last(), ['resolve', 1, 1, [7, 8]]);

    link.result = Promise.resolve();
    native.request({ linkId: 1, requestId: 2, op: 'writeWithoutResponse', service: GattProfile.SERVICE, characteristic: GattProfile.RX_STREAM, frames: [[1, 2], [3]] });
    await settle();
    assert.equal(link.ops[1], 'write [[1,2],[3]]');
    assert.deepEqual(native.last(), ['resolve', 1, 2, []]);

    link.result = Promise.reject(Object.assign(new Error('Error reading x status=5'), { gattStatus: 5 }));
    native.request({ linkId: 1, requestId: 3, op: 'read', service: GattProfile.SERVICE, characteristic: GattProfile.DEVICE_ID });
    await settle();
    assert.deepEqual(native.last(), ['reject', 1, 3, 'Error reading x status=5', 5]);

    link.result = Promise.reject('plain string failure');
    native.request({ linkId: 1, requestId: 4, op: 'disableNotifications', ...TX });
    await settle();
    assert.deepEqual(native.last(), ['reject', 1, 4, 'plain string failure', -1]);
  });

  it('numbers notification values per link, across attachments', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    await bridge.attach(link);
    native.request({ linkId: 1, requestId: 1, op: 'enableNotifications', ...TX });
    await settle();
    link.listener?.onValue([1]);
    link.listener?.onValue(new Uint8Array([2]));
    native.request({ linkId: 1, requestId: 2, op: 'disableNotifications', ...TX });
    await bridge.attach(link);
    native.request({ linkId: 1, requestId: 3, op: 'enableNotifications', ...TX });
    await settle();
    link.listener?.onValue([3]);
    link.listener?.onError(new BleLinkError('subscription failed', 133));
    assert.deepEqual(
      native.calls.filter((c) => c[0] === 'value' || c[0] === 'notificationError'),
      [['value', 1, 0, [1]], ['value', 1, 1, [2]], ['value', 1, 2, [3]], ['notificationError', 1, 'subscription failed', 133]],
    );
  });

  it('reports a disconnect, then registers the next link afresh', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    await bridge.attach(link);
    link.drop();
    assert.deepEqual(native.last(), ['disconnected', 1]);
    native.request({ linkId: 1, requestId: 9, op: 'read', ...TX });
    assert.deepEqual(native.last(), ['reject', 1, 9, 'the link has disconnected', -1]);

    const reconnected = new FakeLink();
    await bridge.attach(reconnected);
    assert.deepEqual(native.calls.find((c) => c[0] === 'openLink' && c !== native.calls[0]), ['openLink', ADDRESS, 20]);
  });

  it('reports a link that is already down at once', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    link.down = true;
    await bridge.attach(link);
    assert.deepEqual(native.calls.slice(0, 2), [['openLink', ADDRESS, 20], ['disconnected', 1]]);
  });

  it('forwards MTU changes until the link drops', async () => {
    const { native, bridge } = setUp();
    const link = new FakeLink();
    await bridge.attach(link);
    link.setMtu(244);
    link.drop();
    link.setMtu(100);
    assert.deepEqual(native.calls.filter((c) => c[0] === 'mtu'), [['mtu', 1, 244]]);
  });

  it('follows the attachment lifecycle and resolves ended once', async () => {
    const { native, bridge } = setUp();
    const attachment = await bridge.attach(new FakeLink());
    const seen: string[] = [];
    attachment.onStateChange((state) => seen.push(state.type));
    assert.deepEqual(attachment.state, { type: 'starting' });

    native.state({ attachmentId: 1, type: 'ready', deviceId: 'device-1' });
    assert.deepEqual(attachment.state, { type: 'ready', deviceId: 'device-1' });

    attachment.detach();
    assert.deepEqual(native.last(), ['detach', 1]);
    native.state({ attachmentId: 1, type: 'ended', result: { reason: 'FAILED', error: { kind: 'BLUETOOTH', message: 'boom' } } });
    native.state({ attachmentId: 1, type: 'ready', deviceId: 'late' });
    assert.deepEqual(await attachment.ended, { reason: 'FAILED', error: { kind: 'BLUETOOTH', message: 'boom' } });
    assert.deepEqual(seen, ['ready', 'ended']);
    assert.equal(attachment.state.type, 'ended');

    const calls = native.calls.length;
    attachment.detach();
    assert.equal(native.calls.length, calls, 'detaching an ended attachment does nothing');
  });

  it('reports diagnostics with the same attachment states as the events', async () => {
    const { native, bridge } = setUp();
    const gateway = await bridge.configure({ ingestKeySource: 'gateway', ingestKey: 'key' });
    await assert.rejects(gateway.diagnosticsSnapshot(), /shut down/);

    const device = {
      address: ADDRESS,
      deviceId: 'device-1',
      attachmentState: { type: 'ended', result: { reason: 'DISCONNECTED', error: null } },
      cloudError: null,
    };
    const bufferUsage = { ramBytes: 1, ramMaxBytes: 2, flashBytes: 3, flashMaxBytes: 4, flashAvailable: true };
    native.module.diagnosticsSnapshot = async () => ({ devices: [device], bufferUsage });
    const report = await gateway.diagnosticsSnapshot();
    assert.deepEqual(report.devices[0]?.attachmentState, { type: 'ended', result: { reason: 'DISCONNECTED' } });
    assert.deepEqual(report.bufferUsage, bufferUsage);
  });

  it('rejects attach when the module refuses it', async () => {
    const { native, bridge } = setUp();
    native.attachFailure = Object.assign(new Error('the link has disconnected'), { code: 'E_LINK_DOWN' });
    await assert.rejects(bridge.attach(new FakeLink()), /the link has disconnected/);
  });

  it('surfaces the GATT status of a failed device ID read', async () => {
    const { native, bridge } = setUp();
    native.readDeviceIdResult = Promise.reject(
      Object.assign(new Error('read Device ID failed: 5'), { code: 'E_BLE', userInfo: { gattStatus: 5 } }),
    );
    await assert.rejects(bridge.readDeviceId(new FakeLink()), (error: unknown) => {
      assert.ok(error instanceof BleLinkError);
      assert.equal(error.gattStatus, 5);
      return true;
    });
  });
});
