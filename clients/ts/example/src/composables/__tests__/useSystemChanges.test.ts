import { describe, it, expect, vi, beforeEach } from 'vitest';
import { effectScope, isShallow, ref, shallowRef } from 'vue';
import { EventSubscriberAdapter } from '@kduma-autoid/hal-client-common';
import type { IEventTransport } from '@kduma-autoid/hal-client-common';

/**
 * A transport like the WS one: handlers in a list, removed by entry identity, and a server-side
 * subscription per pattern. Subscribing takes a moment, as a round trip does.
 */
class FakeTransport implements IEventTransport {
  handlers: { pattern: string; handler: Function }[] = [];
  server = new Set<string>();
  async subscribe(events: string[]) {
    await new Promise((r) => setTimeout(r, 5));
    events.forEach((e) => this.server.add(e));
  }
  async unsubscribe(events: string[]) {
    events.forEach((e) => this.server.delete(e));
  }
  on(pattern: string, handler: Function) {
    const entry = { pattern, handler };
    this.handlers.push(entry);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== entry);
    };
  }
  off() {}
  dispose() {}
  setToken() {}
  getToken() {
    return 't';
  }
  emit(event: string) {
    this.handlers.filter((h) => h.pattern === event).forEach((h) => h.handler(event, {}));
  }
}

let transport: FakeTransport;
const client = shallowRef<{ on: EventSubscriberAdapter['on'] } | null>(null);
const isConnected = ref(false);
vi.mock('../useHalClient', () => ({ useHalClient: () => ({ client, isConnected }) }));

const { useSystemChanges } = await import('../useSystemChanges');
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

function connect() {
  transport = new FakeTransport();
  const adapter = new EventSubscriberAdapter(transport);
  client.value = { on: adapter.on.bind(adapter) };
  isConnected.value = true;
}

beforeEach(() => {
  client.value = null;
  isConnected.value = false;
});

describe('useSystemChanges', () => {
  it('calls back once per burst of change events', async () => {
    connect();
    const onChange = vi.fn();
    const scope = effectScope();
    scope.run(() => useSystemChanges(onChange, 10));
    await wait(20);

    transport.emit('system.plugins.changed');
    transport.emit('system.plugins.changed');
    transport.emit('system.interfaces.changed');
    await wait(30);

    expect(onChange).toHaveBeenCalledTimes(1);
    scope.stop();
  });

  it('leaves nothing behind when the view goes away', async () => {
    connect();
    for (let i = 0; i < 4; i++) {
      const scope = effectScope();
      scope.run(() => useSystemChanges(() => {}));
      await wait(20);
      scope.stop();
      await wait(5);
    }
    expect(transport.handlers).toHaveLength(0);
    expect(transport.server.size).toBe(0);
  });

  it('keeps listening when connecting triggers it twice in a row', async () => {
    // Connecting sets the client and then the connection state: two subscribes, the first still in
    // flight when the second starts. Reusing one handler function let the stale one's off() remove
    // the live one from the client's handler set, unsubscribing on the server while still listening.
    const onChange = vi.fn();
    const scope = effectScope();
    scope.run(() => useSystemChanges(onChange, 1));
    transport = new FakeTransport();
    const adapter = new EventSubscriberAdapter(transport);
    client.value = { on: adapter.on.bind(adapter) };
    isConnected.value = true;
    await wait(1);
    isConnected.value = false;
    await wait(1);
    isConnected.value = true;
    await wait(30);

    expect(transport.server.has('system.plugins.changed')).toBe(true);
    expect(transport.handlers).toHaveLength(2);
    scope.stop();
    await wait(5);
    expect(transport.server.size).toBe(0);
  });

  it('stops listening on disconnect', async () => {
    connect();
    const onChange = vi.fn();
    const scope = effectScope();
    scope.run(() => useSystemChanges(onChange, 1));
    await wait(20);
    isConnected.value = false;
    await wait(5);

    expect(transport.handlers).toHaveLength(0);
    scope.stop();
  });
});

describe('useHalClient', () => {
  it('holds the client in a shallow ref', async () => {
    // A deep ref wraps the client in a reactive Proxy; the WS event transport then never matched a
    // handler entry on removal and every unsubscribe left its listener behind.
    vi.doUnmock('../useHalClient');
    const real = await vi.importActual<typeof import('../useHalClient')>('../useHalClient');
    expect(isShallow(real.useHalClient().client)).toBe(true);
  });
});
