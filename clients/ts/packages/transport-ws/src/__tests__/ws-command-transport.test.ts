import { describe, it, expect, vi } from 'vitest';
import { WsCommandTransport } from '../ws-command-transport.js';
import type { WsConnection } from '../ws-connection.js';
import type { WsServerMessage } from '../types/message.js';
import type { CommandMeta } from '@kduma-autoid/hal-client-common';

function mockConnection(response: WsServerMessage) {
  return {
    send: vi.fn().mockResolvedValue(response),
    authenticate: vi.fn().mockResolvedValue(undefined),
  } as unknown as WsConnection;
}

describe('WsCommandTransport.execute', () => {
  it('reports the response provider header via onMeta', async () => {
    const conn = mockConnection({
      id: '1',
      type: 'response',
      result: { emitted: true },
      provider: 'demo.beta',
    });
    const t = new WsCommandTransport(conn);
    t.setToken('tok');

    let meta: CommandMeta | undefined;
    const result = await t.execute('demo.emit', { message: 'hi' }, { onMeta: (m) => { meta = m; } });

    expect(result).toEqual({ emitted: true });
    expect(meta).toEqual({ provider: 'demo.beta' });
  });

  it('calls onMeta with empty meta when the response carries no provider', async () => {
    const conn = mockConnection({ id: '1', type: 'response', result: { ok: true } });
    const t = new WsCommandTransport(conn);
    t.setToken('tok');

    let meta: CommandMeta | undefined;
    const result = await t.execute('system.ping', {}, { onMeta: (m) => { meta = m; } });

    expect(result).toEqual({ ok: true });
    expect(meta).toEqual({});
  });

  it('returns the result body when no options are passed', async () => {
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
