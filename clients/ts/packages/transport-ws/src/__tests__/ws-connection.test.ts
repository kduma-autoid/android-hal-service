import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WsConnection } from '../ws-connection.js';
import type {
  IWebSocketAdapter,
  IWebSocketAdapterFactory,
  WebSocketReadyState,
} from '../interfaces/ws-adapter.js';

// Mock WebSocket adapter
class MockWsAdapter implements IWebSocketAdapter {
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

class MockWsAdapterFactory implements IWebSocketAdapterFactory {
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

describe('WsConnection', () => {
  let factory: MockWsAdapterFactory;

  beforeEach(() => {
    factory = new MockWsAdapterFactory();
  });

  describe('connect', () => {
    it('should resolve when WebSocket opens', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();

      await expect(connectPromise).resolves.toBeUndefined();
      expect(connection.connectionState).toBe('connected');
    });

    it('should reject when WebSocket errors during connect', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateError(new Error('Connection refused'));

      await expect(connectPromise).rejects.toThrow('WebSocket connection failed');
      expect(connection.connectionState).toBe('disconnected');
    });

    it('should track connection state changes', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const states: string[] = [];
      connection.onConnectionStateChange((event) => {
        states.push(event.state);
      });

      const connectPromise = connection.connect();
      expect(states).toContain('connecting');

      factory.latest.simulateOpen();
      await connectPromise;

      expect(states).toContain('connected');
    });
  });

  describe('disconnect', () => {
    it('should close WebSocket and set state to disconnected', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      connection.disconnect();
      expect(connection.connectionState).toBe('disconnected');
    });

    it('should reject pending requests on permanent disconnect', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
        requestTimeout: 5000,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      const sendPromise = connection.send({ type: 'command', method: 'test', params: '{}' });
      connection.disconnect(true);

      await expect(sendPromise).rejects.toThrow('Connection closed permanently');
    });
  });

  describe('send', () => {
    it('should send message and resolve with response', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      const adapter = factory.latest;

      // Capture the sent message to extract the id
      adapter.send.mockImplementation((data: string) => {
        const msg = JSON.parse(data);
        // Simulate server response with matching id
        setTimeout(() => {
          adapter.simulateMessage(JSON.stringify({
            id: msg.id,
            type: 'response',
            result: { success: true },
          }));
        }, 0);
      });

      const response = await connection.send({ type: 'command', method: 'test', params: '{}' });

      expect(response.type).toBe('response');
      expect(response).toHaveProperty('result');
      expect((response as { result: unknown }).result).toEqual({ success: true });
    });

    it('should reject on timeout', async () => {
      vi.useFakeTimers();

      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
        requestTimeout: 1000,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      const sendPromise = connection.send({ type: 'command', method: 'test', params: '{}' });

      vi.advanceTimersByTime(1001);

      await expect(sendPromise).rejects.toThrow('timed out');

      vi.useRealTimers();
    });

    it('should reject when not connected', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      await expect(connection.send({ type: 'command', method: 'test', params: '{}' }))
        .rejects.toThrow('Not connected');
    });
  });

  describe('auto-reconnect', () => {
    it('should attempt reconnect after unexpected close', async () => {
      vi.useFakeTimers();

      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: true,
        maxReconnectAttempts: 3,
      });

      const states: string[] = [];
      connection.onConnectionStateChange((event) => {
        states.push(event.state);
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      // Simulate server close
      factory.latest.simulateClose(1006, 'Abnormal');

      expect(states).toContain('reconnecting');

      // Advance past the first reconnect delay (1000ms)
      vi.advanceTimersByTime(1001);

      // A new adapter should have been created
      expect(factory.adapters.length).toBe(2);

      vi.useRealTimers();
    });

    it('should not reconnect when autoReconnect is false', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      factory.latest.simulateClose(1006, 'Abnormal');

      expect(connection.connectionState).toBe('disconnected');
      expect(factory.adapters.length).toBe(1);
    });

    it('should unsubscribe from connection state changes', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const states: string[] = [];
      const unsub = connection.onConnectionStateChange((event) => {
        states.push(event.state);
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      unsub();

      connection.disconnect();
      // Should not have recorded the disconnect state change
      expect(states).not.toContain('disconnected');
    });
  });

  describe('onMessage', () => {
    it('should dispatch unsolicited messages to handlers', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      const messages: unknown[] = [];
      connection.onMessage((msg) => {
        messages.push(msg);
      });

      factory.latest.simulateMessage(JSON.stringify({
        type: 'event',
        event: 'scanner.barcode',
        data: { value: '123' },
      }));

      expect(messages).toHaveLength(1);
      expect(messages[0]).toEqual({
        type: 'event',
        event: 'scanner.barcode',
        data: { value: '123' },
      });
    });

    it('should allow unsubscribing from messages', async () => {
      const connection = new WsConnection({
        url: 'ws://localhost:8080',
        wsAdapterFactory: factory,
        autoReconnect: false,
      });

      const connectPromise = connection.connect();
      factory.latest.simulateOpen();
      await connectPromise;

      const messages: unknown[] = [];
      const unsub = connection.onMessage((msg) => {
        messages.push(msg);
      });

      factory.latest.simulateMessage(JSON.stringify({
        type: 'event',
        event: 'test1',
        data: null,
      }));

      unsub();

      factory.latest.simulateMessage(JSON.stringify({
        type: 'event',
        event: 'test2',
        data: null,
      }));

      expect(messages).toHaveLength(1);
    });
  });
});
