import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiLightClient } from '../sunmi-light-client.js';

interface FakeProvider {
  pluginId: string;
  features: string[];
  isDefault?: boolean;
}

function clientWithLight(providers: FakeProvider[]): IHalClient {
  const execute = vi.fn(async (method: string) => {
    if (method === 'system.describe') {
      return {
        plugins: [],
        interfaces: providers.length
          ? [
              {
                kind: 'interface',
                interfaceId: 'light',
                version: 1,
                features: [],
                methods: [],
                events: [],
                providers: providers.map((p) => ({
                  pluginId: p.pluginId,
                  source: 'builtin',
                  priority: 0,
                  isDefault: !!p.isDefault,
                  features: p.features,
                })),
              },
            ]
          : [],
      };
    }
    return { status: 'ok' };
  });
  return { execute, on: vi.fn().mockResolvedValue(vi.fn()) } as unknown as IHalClient;
}

const TMS_LED: FakeProvider = { pluginId: 'sunmi.tms.led', features: ['timeout'], isDefault: true };
const STATUSLIGHT: FakeProvider = { pluginId: 'sunmi.statuslight', features: ['multiFlash'] };

describe('SunmiLightClient (light interface)', () => {
  describe('detect / create', () => {
    it('binds to the default provider (tms.led)', async () => {
      const light = await SunmiLightClient.create(clientWithLight([TMS_LED, STATUSLIGHT]));
      expect(light.backend).toBe('sunmi.tms.led');
      expect(light.capabilities).toEqual({ multiFlash: false, timeout: true });
    });

    it('binds to the only provider (statuslight)', async () => {
      const light = await SunmiLightClient.create(clientWithLight([{ ...STATUSLIGHT, isDefault: true }]));
      expect(light.backend).toBe('sunmi.statuslight');
      expect(light.capabilities).toEqual({ multiFlash: true, timeout: false });
    });

    it('throws when the interface has no provider', async () => {
      await expect(SunmiLightClient.create(clientWithLight([]))).rejects.toThrow(/No Sunmi light backend/);
    });
  });

  describe('calls', () => {
    it('calls light.* on the default provider without a __provider selector', async () => {
      const client = clientWithLight([TMS_LED]);
      const light = await SunmiLightClient.create(client);

      await light.on('green', { timeoutMs: 5000 });
      expect(client.execute).toHaveBeenCalledWith('light.on', { color: 'green', timeoutMs: 5000 });

      await light.off();
      expect(client.execute).toHaveBeenCalledWith('light.off', {});
    });

    it('injects __provider when bound to a non-default provider', async () => {
      const client = clientWithLight([TMS_LED, STATUSLIGHT]);
      const flex = await SunmiLightClient.forBackend(client, 'sunmi.statuslight');
      expect(flex.backend).toBe('sunmi.statuslight');

      await flex.on('red');
      expect(client.execute).toHaveBeenCalledWith('light.on', {
        color: 'red',
        __provider: 'sunmi.statuslight',
      });
    });

    it('exposes multiFlash only when the provider supports it', async () => {
      const tms = await SunmiLightClient.create(clientWithLight([TMS_LED]));
      expect(tms.multiFlash).toBeUndefined();

      const flexClient = clientWithLight([{ ...STATUSLIGHT, isDefault: true }]);
      const flex = await SunmiLightClient.create(flexClient);
      expect(flex.multiFlash).toBeTypeOf('function');

      await flex.multiFlash!(['red', 'green'], 400, 100);
      expect(flexClient.execute).toHaveBeenCalledWith('light.multiFlash', {
        colors: ['red', 'green'],
        onMs: 400,
        offMs: 100,
      });
    });

    it('throws when timeoutMs is used on a provider without the timeout feature', async () => {
      const flex = await SunmiLightClient.create(clientWithLight([{ ...STATUSLIGHT, isDefault: true }]));
      await expect(flex.on('red', { timeoutMs: 1000 })).rejects.toThrow(/not supported/);
    });
  });

  describe('onChanged', () => {
    it('subscribes to plugins.changed and interfaces.changed', async () => {
      const unsub = vi.fn().mockResolvedValue(undefined);
      const handlers: Array<() => void> = [];
      const on = vi.fn(async (_event: string, handler: () => void) => {
        handlers.push(handler);
        return unsub;
      });
      const client = { execute: vi.fn(), on } as unknown as IHalClient;

      const handler = vi.fn();
      const off = await SunmiLightClient.onChanged(client, handler);

      expect(on).toHaveBeenCalledWith('system.plugins.changed', expect.any(Function));
      expect(on).toHaveBeenCalledWith('system.interfaces.changed', expect.any(Function));
      handlers[0]();
      expect(handler).toHaveBeenCalledTimes(1);

      await off();
      expect(unsub).toHaveBeenCalledTimes(2);
    });
  });
});
