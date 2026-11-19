import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WsConnection } from '../ws-connection.js';
import type { WsConnectionOptions } from '../ws-connection.js';
import { WsCommandTransport } from '../ws-command-transport.js';
import { WsEventTransport } from '../ws-event-transport.js';
import { MockWsAdapterFactory, replyTo, sentMessages } from './mock-ws.js';

/** Lets pending promise callbacks run (fake timers don't flush microtasks by themselves). */
const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
};

describe('WsConnection resilience', () => {
  let factory: MockWsAdapterFactory;

  beforeEach(() => {
    vi.useFakeTimers();
    factory = new MockWsAdapterFactory();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function connected(options: Partial<WsConnectionOptions> = {}) {
    const connection = new WsConnection({ url: 'ws://x', wsAdapterFactory: factory, watchBrowserEvents: false, ...options });
    const p = connection.connect();
    factory.latest.simulateOpen();
    await flush();
    await p;
    return connection;
  }

  describe('heartbeat', () => {
    it('probes a silent connection and reconnects when the probe gets no reply', async () => {
      const connection = await connected({ heartbeatInterval: 3000, heartbeatTimeout: 1000 });
      const reasons: Array<string | undefined> = [];
      connection.onConnectionStateChange((e) => reasons.push(`${e.state}:${e.reason ?? ''}`));

      await vi.advanceTimersByTimeAsync(3000);
      expect(sentMessages(factory.latest).some((m) => m.method === 'system.ping')).toBe(true);

      await vi.advanceTimersByTimeAsync(1000);
      expect(reasons).toContain('reconnecting:timeout');
      expect(factory.adapters[0].close).toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1200);
      expect(factory.adapters).toHaveLength(2);
    });

    it('any reply to the probe keeps the connection — even an error', async () => {
      const connection = await connected({ heartbeatInterval: 3000, heartbeatTimeout: 1000 });

      await vi.advanceTimersByTimeAsync(3000);
      replyTo(factory.latest, 'command', { type: 'error', error: { code: 'unauthorized', message: 'Not authenticated' } }, 'system.ping');
      await vi.advanceTimersByTimeAsync(1500);

      expect(connection.connectionState).toBe('connected');
      expect(factory.adapters).toHaveLength(1);
    });

    it('does not probe while messages keep arriving', async () => {
      await connected({ heartbeatInterval: 3000, heartbeatTimeout: 1000 });
      for (let i = 0; i < 6; i++) {
        factory.latest.simulateMessage(JSON.stringify({ type: 'event', event: 'x', data: {} }));
        await vi.advanceTimersByTimeAsync(1000);
      }
      expect(sentMessages(factory.latest).some((m) => m.method === 'system.ping')).toBe(false);
    });
  });

  describe('lost connection', () => {
    it('rejects pending requests at once instead of waiting for their timeout', async () => {
      const connection = await connected({ heartbeatInterval: 0 });
      const pending = connection.send({ type: 'command', method: 'x' });
      factory.latest.simulateClose(1006, 'gone');
      await expect(pending).rejects.toThrow('Connection lost');
    });

    it('keeps reconnecting without a limit, with the delay capped', async () => {
      const connection = await connected({ heartbeatInterval: 0, maxReconnectDelay: 2000 });
      factory.latest.simulateClose(1006, '');
      for (let i = 0; i < 15; i++) {
        await vi.advanceTimersByTimeAsync(2400); // cap 2000 ms + 20 % jitter
        factory.latest.simulateError(new Error('refused'));
        factory.latest.simulateClose(1006, '');
        await flush();
      }
      expect(connection.connectionState).toBe('reconnecting');
      expect(factory.adapters).toHaveLength(16);
    });

    it("a failed attempt reporting both error and close schedules one retry, not two", async () => {
      await connected({ heartbeatInterval: 0 });
      factory.latest.simulateClose(1006, '');
      await vi.advanceTimersByTimeAsync(1200);
      expect(factory.adapters).toHaveLength(2);

      factory.latest.simulateError(new Error('refused'));
      factory.latest.simulateClose(1006, '');
      await flush();
      await vi.advanceTimersByTimeAsync(2400); // second delay: 2000 ms ± 20 %
      expect(factory.adapters).toHaveLength(3);
    });

    it('disconnect() does not start a reconnect', async () => {
      const connection = await connected({ heartbeatInterval: 0 });
      connection.disconnect();
      factory.adapters[0].simulateClose(1000, '');
      await vi.advanceTimersByTimeAsync(60_000);
      expect(connection.connectionState).toBe('disconnected');
      expect(factory.adapters).toHaveLength(1);
    });

    it('requests sent while reconnecting wait and go out once connected', async () => {
      const connection = await connected({ heartbeatInterval: 0 });
      factory.latest.simulateClose(1006, '');

      const reply = connection.send({ type: 'command', method: 'later' });
      await vi.advanceTimersByTimeAsync(1200);
      factory.latest.simulateOpen();
      await flush();

      replyTo(factory.latest, 'command', { result: { ok: true } }, 'later');
      await expect(reply).resolves.toMatchObject({ type: 'response', result: { ok: true } });
    });
  });

  describe('session restore', () => {
    it('re-authenticates and re-subscribes before the connection counts as connected', async () => {
      const connection = new WsConnection({ url: 'ws://x', wsAdapterFactory: factory, watchBrowserEvents: false, heartbeatInterval: 0 });
      const commands = new WsCommandTransport(connection);
      const events = new WsEventTransport(connection);

      const p = connection.connect();
      factory.latest.simulateOpen();
      await flush();
      await p;

      commands.setToken('tok');
      await flush();
      replyTo(factory.latest, 'authenticate', { result: { authenticated: true } });
      await flush();

      const sub = events.subscribe(['scale.onWeight']);
      await flush();
      replyTo(factory.latest, 'subscribe');
      await sub;

      // The socket drops; the new one must get authenticate, then subscribe, then the waiting command.
      factory.latest.simulateClose(1006, '');
      const states: string[] = [];
      connection.onConnectionStateChange((e) => states.push(e.state));
      const command = commands.execute('system.ping');

      await vi.advanceTimersByTimeAsync(1200);
      const fresh = factory.latest;
      fresh.simulateOpen();
      await flush();
      expect(sentMessages(fresh).map((m) => m.type)).toEqual(['authenticate']);
      expect(sentMessages(fresh)[0].token).toBe('tok');
      expect(connection.connectionState).toBe('reconnecting');

      replyTo(fresh, 'authenticate', { result: { authenticated: true } });
      await flush();
      expect(sentMessages(fresh).map((m) => m.type)).toEqual(['authenticate', 'subscribe']);
      expect(sentMessages(fresh)[1].events).toEqual(['scale.onWeight']);

      replyTo(fresh, 'subscribe');
      await flush();
      expect(states).toContain('connected');
      expect(sentMessages(fresh).map((m) => m.type)).toEqual(['authenticate', 'subscribe', 'command']);

      replyTo(fresh, 'command', { result: { pong: true } });
      await expect(command).resolves.toEqual({ pong: true });
    });

    it('reports a token rejected at re-authentication and skips re-subscribing', async () => {
      const connection = new WsConnection({ url: 'ws://x', wsAdapterFactory: factory, watchBrowserEvents: false, heartbeatInterval: 0 });
      const commands = new WsCommandTransport(connection);
      const events = new WsEventTransport(connection);
      const rejected = vi.fn();
      commands.onTokenRejected(rejected);

      const p = connection.connect();
      factory.latest.simulateOpen();
      await flush();
      await p;
      commands.setToken('old');
      await flush();
      replyTo(factory.latest, 'authenticate', { result: { authenticated: true } });
      const sub = events.subscribe(['x']);
      await flush();
      replyTo(factory.latest, 'subscribe');
      await sub;

      factory.latest.simulateClose(1006, '');
      await vi.advanceTimersByTimeAsync(1200);
      factory.latest.simulateOpen();
      await flush();
      replyTo(factory.latest, 'authenticate', { type: 'error', error: { code: 'unauthorized', message: 'Invalid token' } });
      await flush();

      expect(rejected).toHaveBeenCalledWith('old');
      expect(sentMessages(factory.latest).map((m) => m.type)).toEqual(['authenticate']);
      expect(connection.connectionState).toBe('connected');
    });

    it('unsubscribed events are not restored', async () => {
      const connection = new WsConnection({ url: 'ws://x', wsAdapterFactory: factory, watchBrowserEvents: false, heartbeatInterval: 0 });
      const events = new WsEventTransport(connection);
      const p = connection.connect();
      factory.latest.simulateOpen();
      await flush();
      await p;

      const sub = events.subscribe(['a']);
      await flush();
      replyTo(factory.latest, 'subscribe');
      await sub;
      const unsub = events.unsubscribe(['a']);
      await flush();
      replyTo(factory.latest, 'unsubscribe');
      await unsub;

      factory.latest.simulateClose(1006, '');
      await vi.advanceTimersByTimeAsync(1200);
      factory.latest.simulateOpen();
      await flush();
      expect(sentMessages(factory.latest)).toEqual([]);
      expect(connection.connectionState).toBe('connected');
    });
  });
});
