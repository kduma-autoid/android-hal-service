import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { ScaleClient } from '../scale-client.js';

interface FakeProvider {
  pluginId: string;
  isDefault?: boolean;
  enabled?: boolean;
}

const DYMO: FakeProvider = { pluginId: 'dymo.m10', isDefault: true };
const OTHER: FakeProvider = { pluginId: 'other.scale' };

const READING = { grams: 1250, value: 1250, unit: 'g', status: 'stable', stable: true };

function clientWith(providers: FakeProvider[], reply: unknown = {}) {
  const execute = vi.fn(async (method: string) => {
    if (method === 'system.describe') {
      return {
        plugins: [],
        interfaces: providers.length
          ? [
              {
                kind: 'interface',
                interfaceId: 'scale',
                version: 1,
                features: [],
                methods: [],
                events: [],
                providers: providers.map((p) => ({
                  pluginId: p.pluginId,
                  source: 'builtin',
                  priority: 0,
                  isDefault: !!p.isDefault,
                  enabled: p.enabled ?? true,
                  features: [],
                })),
              },
            ]
          : [],
      };
    }
    return reply;
  });
  const on = vi.fn().mockResolvedValue(vi.fn());
  return { client: { execute, on } as unknown as IHalClient, execute, on };
}

describe('ScaleClient (scale interface)', () => {
  it('binds to the default provider', async () => {
    const scale = await ScaleClient.create(clientWith([OTHER, DYMO]).client);
    expect(scale.backend).toBe('dymo.m10');
  });

  it('throws when the interface has no provider', async () => {
    await expect(ScaleClient.create(clientWith([]).client)).rejects.toThrow(/No scale backend/);
  });

  it('lists backends in service order', async () => {
    const backends = await ScaleClient.listBackends(clientWith([DYMO, OTHER]).client);
    expect(backends.map((b) => b.pluginId)).toEqual(['dymo.m10', 'other.scale']);
  });

  it('refuses to bind a disabled or unknown provider', async () => {
    const { client } = clientWith([DYMO, { ...OTHER, enabled: false }]);
    await expect(ScaleClient.forBackend(client, 'other.scale')).rejects.toThrow(/disabled/);
    await expect(ScaleClient.forBackend(client, 'nope')).rejects.toThrow(/not available/);
  });

  it('pins getWeight and getInfo to the bound backend', async () => {
    const { client, execute } = clientWith([DYMO], READING);
    const scale = await ScaleClient.create(client);
    await expect(scale.getWeight()).resolves.toEqual(READING);
    await scale.getInfo();
    expect(execute).toHaveBeenCalledWith('scale.getWeight@dymo.m10', {});
    expect(execute).toHaveBeenCalledWith('scale.getInfo@dymo.m10', {});
  });

  it('subscribes to events of the bound backend only', async () => {
    const { client, on } = clientWith([DYMO]);
    const scale = await ScaleClient.create(client);
    const handler = vi.fn();
    await scale.onWeight(handler);
    await scale.onStatus(vi.fn());
    expect(on).toHaveBeenCalledWith('scale.onWeight@dymo.m10', expect.any(Function));
    expect(on).toHaveBeenCalledWith('scale.onStatus@dymo.m10', expect.any(Function));

    const listener = on.mock.calls[0][1] as (name: string, data: unknown) => void;
    listener('scale.onWeight', READING);
    expect(handler).toHaveBeenCalledWith(READING);
  });
});
