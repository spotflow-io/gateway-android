// The TurboModule spec: codegen generates the Android base class (NativeSpotflowGatewaySpec) from this
// file. Keep it in step with android/src/main/java/io/spotflow/reactnative/SpotflowGatewayModule.kt.
import type { CodegenTypes, TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativeGatewayOptions = {
  ingestKeySource?: string;
  ingestKey?: string;
  endpoint?: { host: string; port?: number };
  ramBufferBytes?: number;
  flashBufferBytes?: number;
  otaEnabled?: boolean;
  otaMaxArtifactBytes?: number;
};

/** A GATT operation for the JS driver of link `linkId`. `frames` is empty except for writes. */
export type NativeLinkRequest = {
  linkId: number;
  requestId: number;
  op: string;
  service: string;
  characteristic: string;
  frames: ReadonlyArray<ReadonlyArray<number>>;
};

/** `type` is starting, ready (with `deviceId`) or ended (with `result`). */
export type NativeAttachmentState = {
  attachmentId: number;
  type: string;
  deviceId?: string;
  result?: { reason: string; error?: { kind: string; message: string } | null };
};

export interface Spec extends TurboModule {
  configure(options: NativeGatewayOptions): Promise<void>;
  shutdown(): void;

  /** Registers a link the JS driver serves; returns its ID. */
  openLink(address: string, maxWritePayloadBytes: number): number;
  setMaxWritePayloadBytes(linkId: number, bytes: number): void;
  linkDisconnected(linkId: number): void;
  resolveRequest(linkId: number, requestId: number, value: ReadonlyArray<number>): void;
  /** `gattStatus` is -1 when the stack reported none. */
  rejectRequest(linkId: number, requestId: number, message: string, gattStatus: number): void;
  /** `seq` counts the link's values from 0, so the module detects a lost or reordered one. */
  pushNotification(linkId: number, seq: number, value: ReadonlyArray<number>): void;
  notificationError(linkId: number, message: string, gattStatus: number): void;

  readDeviceId(linkId: number): Promise<string>;
  attach(linkId: number, attachmentId: number): Promise<void>;
  detach(attachmentId: number): void;
  diagnosticsSnapshot(): Promise<CodegenTypes.UnsafeObject | null>;

  readonly onLinkRequest: CodegenTypes.EventEmitter<NativeLinkRequest>;
  readonly onAttachmentState: CodegenTypes.EventEmitter<NativeAttachmentState>;
}

export default TurboModuleRegistry.get<Spec>('SpotflowGateway');
