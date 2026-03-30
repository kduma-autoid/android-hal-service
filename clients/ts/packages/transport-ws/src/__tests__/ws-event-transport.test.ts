import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WsEventTransport } from '../ws-event-transport.js';
import type { WsConnection } from '../ws-connection.js';
import type { WsServerMessage } from '../types/message.js';

// Create a minimal mock of WsConnection
function createMockConnection() {
  let messageCallback: ((msg: WsServerMessage) => void) | null = null;

  const connection = {
    send: vi.fn().mockResolvedValue({ type: 'response', id: '1', result: null }),
    onMessage: vi.fn((handler: (msg: WsServerMessage) => void) => {
      messageCallback = handler;
      return () => {
        messageCallback = null;
      };
    }),
    simulateEvent(event: string, data: unknown) {
      messageCallback?.({
        type: 'event',
        event,
        data,
      });
    },
    simulateMessage(msg: WsServerMessage) {
      messageCallback?.(msg);
    },
  };

  return connection;
}

describe('WsEventTransport', () => {
  let mockConnection: ReturnType<typeof createMockConnection>;
  let transport: WsEventTransport;

  beforeEach(() => {
    mockConnection = createMockConnection();
    transport = new WsEventTransport(mockConnection as unknown as WsConnection);
  });

  describe('event dispatch', () => {
    it('should dispatch events to matching handlers', () => {
      const handler = vi.fn();
      transport.on('scanner.barcode', handler);

      mockConnection.simulateEvent('scanner.barcode', { value: '123' });

      expect(handler).toHaveBeenCalledWith('scanner.barcode', { value: '123' });
    });

    it('should support wildcard patterns', () => {
      const handler = vi.fn();
      transport.on('scanner.*', handler);

      mockConnection.simulateEvent('scanner.barcode', { value: '123' });
      mockConnection.simulateEvent('scanner.status', { ready: true });

      expect(handler).toHaveBeenCalledTimes(2);
      expect(handler).toHaveBeenCalledWith('scanner.barcode', { value: '123' });
      expect(handler).toHaveBeenCalledWith('scanner.status', { ready: true });
    });

    it('should support global wildcard', () => {
      const handler = vi.fn();
      transport.on('*', handler);

      mockConnection.simulateEvent('scanner.barcode', { value: '123' });
      mockConnection.simulateEvent('printer.status', { ready: true });

      expect(handler).toHaveBeenCalledTimes(2);
    });

    it('should not dispatch to non-matching handlers', () => {
      const handler = vi.fn();
      transport.on('scanner.barcode', handler);

      mockConnection.simulateEvent('printer.status', { ready: true });

      expect(handler).not.toHaveBeenCalled();
    });

    it('should only dispatch event-type messages', () => {
      const handler = vi.fn();
      transport.on('*', handler);

      mockConnection.simulateMessage({
        id: '123',
        type: 'response',
        result: { foo: 'bar' },
      });

      expect(handler).not.toHaveBeenCalled();
    });
  });

  describe('on/off', () => {
    it('should return unsubscribe function from on()', () => {
      const handler = vi.fn();
      const unsub = transport.on('scanner.barcode', handler);

      mockConnection.simulateEvent('scanner.barcode', { value: '1' });
      expect(handler).toHaveBeenCalledTimes(1);

      unsub();

      mockConnection.simulateEvent('scanner.barcode', { value: '2' });
      expect(handler).toHaveBeenCalledTimes(1);
    });

    it('should remove all handlers for a pattern with off()', () => {
      const handler1 = vi.fn();
      const handler2 = vi.fn();

      transport.on('scanner.barcode', handler1);
      transport.on('scanner.barcode', handler2);

      mockConnection.simulateEvent('scanner.barcode', { value: '1' });
      expect(handler1).toHaveBeenCalledTimes(1);
      expect(handler2).toHaveBeenCalledTimes(1);

      transport.off('scanner.barcode');

      mockConnection.simulateEvent('scanner.barcode', { value: '2' });
      expect(handler1).toHaveBeenCalledTimes(1);
      expect(handler2).toHaveBeenCalledTimes(1);
    });

    it('should not remove handlers for different patterns', () => {
      const handler1 = vi.fn();
      const handler2 = vi.fn();

      transport.on('scanner.barcode', handler1);
      transport.on('printer.status', handler2);

      transport.off('scanner.barcode');

      mockConnection.simulateEvent('scanner.barcode', {});
      mockConnection.simulateEvent('printer.status', {});

      expect(handler1).not.toHaveBeenCalled();
      expect(handler2).toHaveBeenCalledTimes(1);
    });
  });

  describe('subscribe/unsubscribe', () => {
    it('should send subscribe message via connection', async () => {
      await transport.subscribe(['scanner.barcode', 'scanner.status']);

      expect(mockConnection.send).toHaveBeenCalledWith({
        type: 'subscribe',
        events: ['scanner.barcode', 'scanner.status'],
      });
    });

    it('should send unsubscribe message via connection', async () => {
      await transport.unsubscribe(['scanner.barcode']);

      expect(mockConnection.send).toHaveBeenCalledWith({
        type: 'unsubscribe',
        events: ['scanner.barcode'],
      });
    });
  });

  describe('dispose', () => {
    it('should clear all handlers and unsubscribe from connection', () => {
      const handler = vi.fn();
      transport.on('scanner.barcode', handler);

      transport.dispose();

      mockConnection.simulateEvent('scanner.barcode', { value: '1' });
      expect(handler).not.toHaveBeenCalled();
    });

    it('should be safe to call dispose multiple times', () => {
      transport.dispose();
      transport.dispose();
      // No error thrown
    });
  });

  describe('token management', () => {
    it('should store and retrieve token', () => {
      expect(transport.getToken()).toBeNull();

      transport.setToken('test-token');
      expect(transport.getToken()).toBe('test-token');
    });
  });
});
