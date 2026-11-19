import { vi } from 'vitest';
import type { IWebSocketAdapter, IWebSocketAdapterFactory, WebSocketReadyState } from '../interfaces/ws-adapter.js';

/** Controllable WebSocket adapter for tests. */
export class MockWsAdapter implements IWebSocketAdapter {
  private openHandler: (() => void) | null = null;
  private closeHandler: ((code: number, reason: string) => void) | null = null;
  private messageHandler: ((data: string) => void) | null = null;
  private errorHandler: ((error: unknown) => void) | null = null;
  private _readyState: WebSocketReadyState = 'CONNECTING';

  get readyState(): WebSocketReadyState {
    return this._readyState;
  }

  send = vi.fn();
  close = vi.fn(() => {
    this._readyState = 'CLOSED';
  });

  onOpen(handler: () => void): void {
    this.openHandler = handler;
  }

  onClose(handler: (code: number, reason: string) => void): void {
    this.closeHandler = handler;
  }

  onMessage(handler: (data: string) => void): void {
    this.messageHandler = handler;
  }

  onError(handler: (error: unknown) => void): void {
    this.errorHandler = handler;
  }

  // Test helpers
  simulateOpen(): void {
    this._readyState = 'OPEN';
    this.openHandler?.();
  }

  simulateClose(code = 1000, reason = ''): void {
    this._readyState = 'CLOSED';
    this.closeHandler?.(code, reason);
  }

  simulateMessage(data: string): void {
    this.messageHandler?.(data);
  }

  simulateError(error: unknown): void {
    this.errorHandler?.(error);
  }
}

export class MockWsAdapterFactory implements IWebSocketAdapterFactory {
  adapters: MockWsAdapter[] = [];

  create(_url: string): IWebSocketAdapter {
    const adapter = new MockWsAdapter();
    this.adapters.push(adapter);
    return adapter;
  }

  get latest(): MockWsAdapter {
    return this.adapters[this.adapters.length - 1];
  }
}

/** The messages a mock adapter was asked to send, parsed. */
export function sentMessages(adapter: MockWsAdapter): Array<Record<string, unknown>> {
  return adapter.send.mock.calls.map((c) => JSON.parse(c[0] as string) as Record<string, unknown>);
}

/** Replies to the last message of `type` (optionally a command `method`) that the adapter sent. */
export function replyTo(adapter: MockWsAdapter, type: string, reply: Record<string, unknown> = {}, method?: string): void {
  const msg = [...sentMessages(adapter)].reverse().find((m) => m.type === type && (method === undefined || m.method === method));
  if (!msg) throw new Error(`no ${type}${method ? ` ${method}` : ''} sent`);
  adapter.simulateMessage(JSON.stringify({ id: msg.id, type: 'response', result: {}, ...reply }));
}
