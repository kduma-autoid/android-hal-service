import { describe, it, expect, vi } from 'vitest';
import { WsCommandTransport } from '../ws-command-transport.js';
import type { WsConnection } from '../ws-connection.js';
import type { WsServerMessage } from '../types/message.js';

function mockConnection(response: WsServerMessage) {
  return {
    send: vi.fn().mockResolvedValue(response),
    authenticate: vi.fn().mockResolvedValue(undefined),
  } as unknown as WsConnection;
}

describe('WsCommandTransport.executeWithMeta', () => {
  it('surfaces the response provider header as meta.provider', async () => {
    const conn = mockConnection({
      id: '1',
      type: 'response',
      result: { emitted: true },
      provider: 'demo.beta',
    });
    const t = new WsCommandTransport(conn);
    t.setToken('tok');

    const out = await t.executeWithMeta('demo.emit', { message: 'hi' });

    expect(out.result).toEqual({ emitted: true });
    expect(out.meta.provider).toBe('demo.beta');
  });

  it('returns empty meta when the response carries no provider', async () => {
    const conn = mockConnection({ id: '1', type: 'response', result: { ok: true } });
    const t = new WsCommandTransport(conn);
    t.setToken('tok');

    const out = await t.executeWithMeta('system.ping', {});

    expect(out.result).toEqual({ ok: true });
    expect(out.meta.provider).toBeUndefined();
  });

  it('execute() returns just the result body', async () => {
    const conn = mockConnection({
      id: '1',
      type: 'response',
      result: { value: 42 },
      provider: 'sunmi.tms.led',
    });
    const t = new WsCommandTransport(conn);
    t.setToken('tok');

    const result = await t.execute('light.on', { color: 'green' });

    expect(result).toEqual({ value: 42 });
  });
});
