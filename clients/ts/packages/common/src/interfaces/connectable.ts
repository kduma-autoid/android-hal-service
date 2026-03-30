export type ConnectionState =
  | 'disconnected'
  | 'connecting'
  | 'connected'
  | 'reconnecting';

export type DisconnectReason =
  | 'manual'
  | 'error'
  | 'server_close'
  | 'timeout';

export interface ConnectionStateEvent {
  state: ConnectionState;
  reason?: DisconnectReason;
  error?: Error;
}

export type ConnectionStateHandler = (event: ConnectionStateEvent) => void;

export interface IConnectable {
  connect(): Promise<void>;
  disconnect(permanent?: boolean): void;
  readonly connectionState: ConnectionState;
  onConnectionStateChange(handler: ConnectionStateHandler): () => void;
}
