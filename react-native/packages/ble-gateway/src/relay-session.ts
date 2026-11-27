import type { Attachment, SpotflowGateway } from './bridge.ts';
import type { BleLink } from './link.ts';
import { notify } from './notify.ts';

/** Session lifecycle, separate from the attachment's own readiness and cloud status. */
export type RelaySessionPhase = 'idle' | 'starting' | 'attached' | 'stopping';

/** The managed attachment's status. Use {@link RelaySession.stop} to detach it. */
export type RelayAttachmentView = Readonly<Pick<Attachment, 'address' | 'state' | 'onStateChange' | 'ended'>>;

type Gateway = Pick<SpotflowGateway, 'attach'>;

interface Attempt {
  gateway: Gateway;
  result: Promise<RelayAttachmentView | undefined>;
  attachment?: Attachment;
}

function attachmentView(attachment: Attachment): RelayAttachmentView {
  return Object.freeze({
    address: attachment.address,
    get state() { return attachment.state; },
    onStateChange: (listener: Parameters<Attachment['onStateChange']>[0]) => attachment.onStateChange(listener),
    ended: attachment.ended,
  });
}

/**
 * Optional attach/detach coordinator for one live BLE connection. Use one session per link and make
 * a new one after reconnecting. The app decides when to start or stop and still owns the connection.
 */
export class RelaySession {
  private readonly link: BleLink;
  private readonly listeners = new Set<(phase: RelaySessionPhase) => void>();
  private currentPhase: RelaySessionPhase = 'idle';
  private attempt?: Attempt;
  private cleanup?: Promise<void>;

  constructor(link: BleLink) {
    this.link = link;
  }

  /** `idle` means there's no relay work pending on this link. */
  get phase(): RelaySessionPhase {
    return this.currentPhase;
  }

  /** Observes later phase changes. Returns a function that unsubscribes. */
  onPhaseChange(listener: (phase: RelaySessionPhase) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Starts an attachment after any previous one has cleaned up. Repeated starts with the same
   * gateway handle share the result; passing another handle for an ongoing start or attachment
   * rejects. Call stop first to switch handles. Resolves to undefined if stopped before the view
   * was returned. Otherwise, attach errors reject.
   */
  start(gateway: Gateway): Promise<RelayAttachmentView | undefined> {
    if (this.attempt && this.attempt.attachment?.state.type !== 'ended') {
      if (this.attempt.gateway !== gateway) {
        return Promise.reject(new Error('Stop the relay session before starting it with a different gateway handle'));
      }
      return this.attempt.result;
    }

    const previous = this.cleanup ?? Promise.resolve();
    const attempt: Attempt = { gateway, result: previous.then(() => this.attach(attempt)) };
    this.attempt = attempt;

    const cleanup = attempt.result.catch(() => {}).then(async () => {
      await attempt.attachment?.ended;
      if (this.attempt === attempt) this.attempt = undefined;
      if (this.cleanup === cleanup) {
        this.cleanup = undefined;
        this.setPhase('idle');
      }
    });
    this.cleanup = cleanup;
    if (this.phase !== 'stopping') this.setPhase('starting');
    return attempt.result;
  }

  /**
   * Cancels starts already requested or detaches the current attachment without disconnecting BLE.
   * Waits for cleanup of that work. A later start can still queue a new attachment.
   */
  stop(): Promise<void> {
    const cleanup = this.cleanup ?? Promise.resolve();
    const attempt = this.attempt;
    if (attempt) {
      this.attempt = undefined;
      this.setPhase('stopping');
      attempt.attachment?.detach();
    }
    return cleanup;
  }

  private async attach(attempt: Attempt): Promise<RelayAttachmentView | undefined> {
    if (this.attempt !== attempt) return;
    this.setPhase('starting');
    if (this.attempt !== attempt) return;

    let attachment: Attachment;
    try {
      attachment = await attempt.gateway.attach(this.link);
    } catch (error) {
      if (this.attempt !== attempt) return;
      this.attempt = undefined;
      throw error;
    }
    attempt.attachment = attachment;
    if (this.attempt !== attempt) {
      attachment.detach();
      return;
    }
    if (attachment.state.type !== 'ended') this.setPhase('attached');
    if (this.attempt === attempt) return attachmentView(attachment);
  }

  private setPhase(phase: RelaySessionPhase): void {
    if (this.currentPhase === phase) return;
    this.currentPhase = phase;
    for (const listener of this.listeners) {
      if (this.currentPhase !== phase) break; // A listener started or stopped the session again.
      notify(listener, phase);
    }
  }
}
