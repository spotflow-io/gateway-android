import assert from 'node:assert/strict';
import { describe, it, mock } from 'node:test';
import { Attachment } from '../bridge.ts';
import type { BleLink } from '../link.ts';
import { RelaySession, type RelayAttachmentView, type RelaySessionPhase } from '../relay-session.ts';

const flush = () => new Promise<void>((resolve) => setImmediate(resolve));

function setup() {
  const unexpectedOperation = () => { throw new Error('the session must not manage BLE'); };
  const link: BleLink = {
    address: 'AA:BB:CC:DD:EE:FF',
    maxWritePayloadBytes: 20,
    onDisconnect: unexpectedOperation,
    read: unexpectedOperation,
    writeWithoutResponse: unexpectedOperation,
    enableNotifications: unexpectedOperation,
    disableNotifications: unexpectedOperation,
  };
  const detach = mock.fn();
  const attachment = new Attachment(link.address, detach);
  const gateway = { attach: mock.fn(async (_link: BleLink) => attachment) };
  const session = new RelaySession(link);
  const finish = () => attachment.update({ type: 'ended', result: { reason: 'DETACHED' } });
  return { link, detach, attachment, gateway, session, finish };
}

describe('RelaySession', () => {
  it('shares concurrent and repeated starts on a live link', async () => {
    const { link, gateway, session, finish } = setup();
    const phases: RelaySessionPhase[] = [];
    session.onPhaseChange((phase) => phases.push(phase));
    assert.equal(session.phase, 'idle');
    const start = session.start(gateway);
    assert.equal(session.phase, 'starting');
    assert.equal(session.start(gateway), start);
    assert.ok(await start);
    assert.equal(session.start(gateway), start);
    assert.equal(session.phase, 'attached');
    assert.equal(gateway.attach.mock.callCount(), 1);
    assert.deepEqual(gateway.attach.mock.calls[0]!.arguments, [link]);
    finish();
    await flush();
    assert.equal(session.phase, 'idle');
    assert.deepEqual(phases, ['starting', 'attached', 'idle']);
  });

  it('exposes a live, read-only view without the detach method', async () => {
    const { link, attachment, gateway, session, finish } = setup();
    const view = await session.start(gateway);
    assert.ok(view);
    assert.notEqual(view, attachment);
    assert.equal(view.address, link.address);
    assert.equal('detach' in view, false);
    assert.equal('update' in view, false);
    assert.equal(Object.isFrozen(view), true);
    assert.deepEqual(view.state, { type: 'starting' });

    const onStateChange = mock.fn();
    const unsubscribe = view.onStateChange(onStateChange);
    const ready = { type: 'ready', deviceId: 'device-1' } as const;
    attachment.update(ready);
    assert.deepEqual(view.state, ready);
    assert.deepEqual(onStateChange.mock.calls[0]!.arguments, [ready]);
    unsubscribe();
    finish();
    assert.deepEqual(await view.ended, { reason: 'DETACHED' });
    assert.equal(view.state.type, 'ended');
    assert.equal(onStateChange.mock.callCount(), 1);
  });

  it('rejects a different gateway handle during a pending or active attachment', async () => {
    const { gateway, session, finish } = setup();
    const next = setup();
    const start = session.start(gateway);
    await assert.rejects(session.start(next.gateway), /different gateway handle/);
    await start;
    await assert.rejects(session.start(next.gateway), /different gateway handle/);
    assert.equal(gateway.attach.mock.callCount(), 1);
    assert.equal(next.gateway.attach.mock.callCount(), 0);
    finish();
    await flush();
    assert.equal(session.phase, 'idle');
  });

  it('can cancel a start before it calls the gateway', async () => {
    const { gateway, session } = setup();
    const start = session.start(gateway);
    await session.stop();
    assert.equal(await start, undefined);
    assert.equal(gateway.attach.mock.callCount(), 0);
    assert.equal(session.phase, 'idle');
  });

  it('detaches a late attachment and waits for its cleanup before restarting', async () => {
    const { link, detach, attachment, gateway, session, finish } = setup();
    const phases: RelaySessionPhase[] = [];
    session.onPhaseChange((phase) => phases.push(phase));
    let completeAttach!: (result: Attachment) => void;
    gateway.attach.mock.mockImplementationOnce(() => new Promise((resolve) => { completeAttach = resolve; }));
    const start = session.start(gateway);
    await flush();
    assert.equal(gateway.attach.mock.callCount(), 1);

    const stop = session.stop();
    const next = setup();
    const restart = session.start(next.gateway);
    assert.equal(session.phase, 'stopping');
    completeAttach(attachment);
    assert.equal(await start, undefined);
    assert.equal(detach.mock.callCount(), 1);
    assert.equal(next.gateway.attach.mock.callCount(), 0);

    finish();
    await stop;
    assert.equal((await restart)?.ended, next.attachment.ended);
    assert.deepEqual(next.gateway.attach.mock.calls[0]!.arguments, [link]);
    assert.equal(session.phase, 'attached');
    next.finish();
    await flush();
    assert.equal(session.phase, 'idle');
    assert.deepEqual(phases, ['starting', 'stopping', 'starting', 'attached', 'idle']);
  });

  it('requests detach only once and stays stopping until cleanup finishes', async () => {
    const { detach, gateway, session, finish } = setup();
    const onPhaseChange = mock.fn();
    const unsubscribe = session.onPhaseChange(onPhaseChange);
    await session.start(gateway);
    const stop = session.stop();
    assert.equal(session.stop(), stop);
    assert.equal(detach.mock.callCount(), 1);
    assert.equal(session.phase, 'stopping');
    unsubscribe();
    finish();
    await stop;
    assert.equal(session.phase, 'idle');
    assert.deepEqual(onPhaseChange.mock.calls.map(({ arguments: args }) => args[0]), ['starting', 'attached', 'stopping']);
  });

  it('lets an observer stop as a queued start begins', async () => {
    const { gateway, session, finish } = setup();
    await session.start(gateway);
    const stop = session.stop();
    const next = setup();
    const start = session.start(next.gateway);
    let stopAgain: Promise<void> | undefined;
    session.onPhaseChange((phase) => { if (phase === 'starting') stopAgain = session.stop(); });

    finish();
    await stop;
    assert.equal(await start, undefined);
    assert.ok(stopAgain);
    await stopAgain;
    assert.equal(next.gateway.attach.mock.callCount(), 0);
    assert.equal(session.phase, 'idle');
  });

  it('releases a failed start and allows immediate retry', async () => {
    const { attachment, gateway, session, finish } = setup();
    gateway.attach.mock.mockImplementationOnce(async () => { throw new Error('attach failed'); });
    await assert.rejects(session.start(gateway), /attach failed/);
    assert.equal((await session.start(gateway))?.ended, attachment.ended);
    assert.equal(gateway.attach.mock.callCount(), 2);
    finish();
    await flush();
    assert.equal(session.phase, 'idle');
  });

  it('allows a new start as soon as the old attachment reports that it ended', async () => {
    const { attachment, gateway, session, finish } = setup();
    await session.start(gateway);
    const next = setup();
    let restart: Promise<RelayAttachmentView | undefined> | undefined;
    attachment.onStateChange(() => { restart = session.start(next.gateway); });
    finish();
    assert.equal((await restart)?.ended, next.attachment.ended);
    next.finish();
    await flush();
    assert.equal(session.phase, 'idle');
  });
});
