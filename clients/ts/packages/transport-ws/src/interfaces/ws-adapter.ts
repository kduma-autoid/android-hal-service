export type WebSocketReadyState = 'CONNECTING' | 'OPEN' | 'CLOSING' | 'CLOSED';

export interface IWebSocketAdapter {
  readonly readyState: WebSocketReadyState;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onOpen(handler: () => void): void;
  onClose(handler: (code: number, reason: string) => void): void;
  onMessage(handler: (data: string) => void): void;
  onError(handler: (error: unknown) => void): void;
}

export interface IWebSocketAdapterFactory {
  create(url: string): IWebSocketAdapter;
}
