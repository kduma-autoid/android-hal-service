import type {
  IWebSocketAdapter,
  IWebSocketAdapterFactory,
  WebSocketReadyState,
} from './interfaces/ws-adapter.js';

const READY_STATE_MAP: Record<number, WebSocketReadyState> = {
  0: 'CONNECTING',
  1: 'OPEN',
  2: 'CLOSING',
  3: 'CLOSED',
};

export class DefaultWsAdapter implements IWebSocketAdapter {
  private readonly ws: WebSocket;

  constructor(url: string) {
    this.ws = new globalThis.WebSocket(url);
  }

  get readyState(): WebSocketReadyState {
    return READY_STATE_MAP[this.ws.readyState] ?? 'CLOSED';
  }

  send(data: string): void {
    this.ws.send(data);
  }

  close(code?: number, reason?: string): void {
    this.ws.close(code, reason);
  }

  onOpen(handler: () => void): void {
    this.ws.addEventListener('open', () => handler());
  }

  onClose(handler: (code: number, reason: string) => void): void {
    this.ws.addEventListener('close', (event: CloseEvent) => {
      handler(event.code, event.reason);
    });
  }

  onMessage(handler: (data: string) => void): void {
    this.ws.addEventListener('message', (event: MessageEvent) => {
      handler(typeof event.data === 'string' ? event.data : String(event.data));
    });
  }

  onError(handler: (error: unknown) => void): void {
    this.ws.addEventListener('error', (event: Event) => {
      handler(event);
    });
  }
}

export class DefaultWsAdapterFactory implements IWebSocketAdapterFactory {
  create(url: string): IWebSocketAdapter {
    return new DefaultWsAdapter(url);
  }
}
