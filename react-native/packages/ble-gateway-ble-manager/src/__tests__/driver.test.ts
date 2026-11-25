import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { BleManagerDriver, hasSpotflowService } from '../driver.ts';
import { FakeBleManager } from './fakeBleManager.ts';

const ID = 'aa:bb:cc:dd:ee:ff';
const SERVICE = '26530001-81e5-4861-82ae-2c92e6887922';
const TX = { service: SERVICE, characteristic: '26530005-81e5-4861-82ae-2c92e6887922' };
const RX = { service: SERVICE, characteristic: '26530006-81e5-4861-82ae-2c92e6887922' };
const DEVICE_ID = { service: SERVICE, characteristic: '26530003-81e5-4861-82ae-2c92e6887922' };

function setUp(options?: { mtu?: number }) {
  const manager = new FakeBleManager();
  const driver = new BleManagerDriver(manager);
  return { manager, driver, link: driver.link(ID, options) };
}

const ticks = (n = 20) => new Promise<void>((resolve) => {
  const step = (left: number) => (left === 0 ? resolve() : setImmediate(() => step(left - 1)));
  step(n);
});

describe('hasSpotflowService', () => {
  it('finds the service in retrieveServices() output, in either case', () => {
    assert.equal(hasSpotflowService({ services: [{ uuid: '180a' }, { uuid: SERVICE.toUpperCase() }] }), true);
    assert.equal(hasSpotflowService({ services: [{ uuid: '180a' }] }), false);
    assert.equal(hasSpotflowService({}), false);
  });
});

describe('BleManagerLink', () => {
  it('uses the upper-case address and derives the frame size from the MTU', () => {
    const { link, driver } = setUp({ mtu: 247 });
    assert.equal(link.address, 'AA:BB:CC:DD:EE:FF');
    assert.equal(link.maxWritePayloadBytes, 244);
    assert.equal(driver.link(ID).maxWritePayloadBytes, 20, 'unknown MTU');
    assert.equal(driver.link(ID, { mtu: 1000 }).maxWritePayloadBytes, 512, 'protocol maximum');

    const seen: number[] = [];
    link.onMaxWritePayloadBytesChange((bytes) => seen.push(bytes));
    link.setMtu(185);
    assert.deepEqual(seen, [182]);
  });

  it('resolves a write only after every frame has reached the stack', async () => {
    const { manager, link } = setUp();
    const frames = [[1], [2], [3]];
    await link.writeWithoutResponse(RX, frames);
    assert.deepEqual(manager.gatt, ['write 1', 'write 2', 'write 3', 'rssi']);
  });

  it('keeps frames in order and ahead of the next operation, with host operations in between', async () => {
    const { manager, link } = setUp();
    const write = link.writeWithoutResponse(RX, [[1], [2]]);
    // The host's own read, issued while the gateway's write calls are queued.
    const hostRead = manager.read(ID, SERVICE, 'host');
    await write;
    await link.read(DEVICE_ID);
    await hostRead;
    const order = manager.gatt.filter((op) => op !== 'read host');
    assert.deepEqual(order, ['write 1', 'write 2', 'rssi', `read ${DEVICE_ID.characteristic}`]);
  });

  it('does not resolve a write while the last frame is still with the stack', async () => {
    const { manager, link } = setUp();
    manager.holdStack = true;
    let done = false;
    const write = link.writeWithoutResponse(RX, [[7]]).then(() => (done = true));
    await ticks();
    assert.deepEqual(manager.gatt, ['write 7']);
    assert.equal(done, false);
    manager.holdStack = false;
    manager.completeStackOperation();
    await write;
    assert.equal(done, true);
  });

  it('passes the GATT status of a failed read', async () => {
    const { manager, link } = setUp();
    manager.readFailure = `Error reading ${DEVICE_ID.characteristic} status=5`;
    await assert.rejects(link.read(DEVICE_ID), (error: { gattStatus?: number; message: string }) => {
      assert.equal(error.gattStatus, 5);
      assert.match(error.message, /status=5/);
      return true;
    });
    assert.equal(link.isDisconnected, false);
  });

  it('delivers notifications from the enable call until the disable completes', async () => {
    const { manager, link } = setUp();
    const values: number[][] = [];
    const listener = { onValue: (v: ReadonlyArray<number> | Uint8Array) => values.push([...v]), onError: () => {} };
    const enabling = link.enableNotifications(TX, listener);
    // A value that arrives before the CCCD write has completed is not lost.
    manager.notify({ peripheral: 'AA:BB:CC:DD:EE:FF', service: SERVICE, characteristic: TX.characteristic, value: [1] });
    await enabling;
    manager.notify({ peripheral: 'AA:BB:CC:DD:EE:FF', service: SERVICE.toUpperCase(), characteristic: TX.characteristic.toUpperCase(), value: [2] });
    manager.notify({ peripheral: 'AA:BB:CC:DD:EE:FF', service: SERVICE, characteristic: RX.characteristic, value: [9] });
    manager.notify({ peripheral: '11:22:33:44:55:66', service: SERVICE, characteristic: TX.characteristic, value: [9] });
    await link.disableNotifications(TX);
    manager.notify({ peripheral: 'AA:BB:CC:DD:EE:FF', service: SERVICE, characteristic: TX.characteristic, value: [3] });
    assert.deepEqual(values, [[1], [2]]);
    assert.deepEqual(manager.gatt, [`notify on ${TX.characteristic}`, `notify off ${TX.characteristic}`]);
  });

  it('rejects a failed CCCD write with its status and stops listening', async () => {
    const { manager, link } = setUp();
    manager.cccdFailure = 'Error writing descriptor status=15';
    const values: unknown[] = [];
    await assert.rejects(
      link.enableNotifications(TX, { onValue: (v) => values.push(v), onError: () => {} }),
      (error: { gattStatus?: number }) => error.gattStatus === 15,
    );
    manager.notify({ peripheral: 'AA:BB:CC:DD:EE:FF', service: SERVICE, characteristic: TX.characteristic, value: [1] });
    assert.deepEqual(values, []);
  });

  it("does not drop a newer link's subscription when an older link disables", async () => {
    const { manager, driver, link: old } = setUp();
    const values: string[] = [];
    await old.enableNotifications(TX, { onValue: () => values.push('old'), onError: () => {} });
    const fresh = driver.link(ID);
    await fresh.enableNotifications(TX, { onValue: () => values.push('new'), onError: () => {} });
    await old.disableNotifications(TX);
    manager.notify({ peripheral: 'AA:BB:CC:DD:EE:FF', service: SERVICE, characteristic: TX.characteristic, value: [1] });
    assert.deepEqual(values, ['new']);
  });

  it('signals a disconnect, then fails the operations the library drops', async () => {
    const { manager, link } = setUp();
    const events: string[] = [];
    link.onDisconnect(() => events.push('disconnected'));
    manager.holdStack = true;
    const inFlight = link.read(DEVICE_ID).catch(() => events.push('in-flight failed'));
    await ticks();
    // Queued behind the read; the library will drop it without settling it.
    const queued = link.writeWithoutResponse(RX, [[1]]).catch(() => events.push('queued failed'));
    await ticks();
    manager.disconnect('AA:BB:CC:DD:EE:FF');
    await Promise.all([inFlight, queued]);
    assert.equal(events[0], 'disconnected');
    assert.deepEqual(events.slice(1).sort(), ['in-flight failed', 'queued failed']);
    assert.equal(link.isDisconnected, true);
    await assert.rejects(link.read(DEVICE_ID), /disconnected/);

    let late = false;
    link.onDisconnect(() => (late = true));
    assert.equal(late, true, 'a listener added after the drop is called at once');
  });

  it('treats a "not connected" failure as a disconnect, signalled first', async () => {
    const { manager, link } = setUp();
    const events: string[] = [];
    link.onDisconnect(() => events.push('disconnected'));
    manager.connected = false;
    await link.read(DEVICE_ID).catch(() => events.push('failed'));
    assert.deepEqual(events, ['disconnected', 'failed']);
  });

  it('ignores disconnects of other peripherals', async () => {
    const { manager, link } = setUp();
    manager.disconnect('11:22:33:44:55:66');
    manager.connected = true;
    assert.equal(link.isDisconnected, false);
    assert.deepEqual([...(await link.read(DEVICE_ID))], [1, 2, 3]);
  });
});
