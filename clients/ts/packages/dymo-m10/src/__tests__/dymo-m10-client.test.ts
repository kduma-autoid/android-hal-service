import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { DymoM10Client } from '../dymo-m10-client.js';
import { DYMO_M10_EVENTS } from '../types.js';

const READING = { grams: 348.7, value: 12.3, unit: 'oz', status: 'stable', stable: true };

function createMockClient(reply: unknown = {}) {
  const execute = vi.fn().mockResolvedValue(reply);
  const on = vi.fn().mockResolvedValue(vi.fn().mockResolvedValue(undefined));
  return { client: { execute, on } as unknown as IHalClient, execute, on };
}

describe('DymoM10Client', () => {
  it.each([
    ['getWeight', (c: DymoM10Client) => c.getWeight(), 'dymo.m10.getWeight'],
    ['getInfo', (c: DymoM10Client) => c.getInfo(), 'dymo.m10.getInfo'],
  ])('%s calls %s', async (_label, call, method) => {
    const { client, execute } = createMockClient(READING);
    await expect(call(new DymoM10Client(client))).resolves.toEqual(READING);
    expect(execute).toHaveBeenCalledWith(method, {});
  });

  it('subscribes to its events', async () => {
    const { client, on } = createMockClient();
    const dymo = new DymoM10Client(client);
    const onWeight = vi.fn();
    await dymo.onWeight(onWeight);
    await dymo.onStatus(vi.fn());
    await dymo.onAttached(vi.fn());
    await dymo.onDetached(vi.fn());
    expect(on.mock.calls.map((c) => c[0])).toEqual([
      DYMO_M10_EVENTS.WEIGHT,
      DYMO_M10_EVENTS.STATUS,
      DYMO_M10_EVENTS.ATTACHED,
      DYMO_M10_EVENTS.DETACHED,
    ]);
    (on.mock.calls[0][1] as (n: string, d: unknown) => void)('dymo.m10.weight', READING);
    expect(onWeight).toHaveBeenCalledWith(READING);
  });
});
