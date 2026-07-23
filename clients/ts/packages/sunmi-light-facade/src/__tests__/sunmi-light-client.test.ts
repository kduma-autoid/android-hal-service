import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiLightClient } from '../sunmi-light-client.js';

function clientWithStatus(capabilitiesByPlugin: Record<string, string[]>): IHalClient {
  const plugins = Object.fromEntries(
    Object.entries(capabilitiesByPlugin).map(([id, capabilities]) => [
      id,
      { version: 1, capabilities, source: 'builtin' },
    ]),
  );
  const execute = vi.fn(async (method: string) => {
    if (method === 'system.status') return { uptime: 1, plugins, transports: {} };
    return { status: 'ok' };
  });
  return { execute, on: vi.fn().mockResolvedValue(vi.fn()) } as unknown as IHalClient;
}

describe('SunmiLightClient (facade)', () => {
  describe('detect / create', () => {
    it('prefers sunmi.tms.led when both are present', async () => {
      const client = clientWithStatus({
        'sunmi.tms.led': ['sunmi.tms.led'],
        'sunmi.statuslight': ['sunmi.statuslight'],
      });
      const light = await SunmiLightClient.create(client);
      expect(light.backend).toBe('sunmi.tms.led');
      expect(light.capabilities).toEqual({ multiFlash: false, timeout: true });
    });

    it('falls back to sunmi.statuslight when tms.led is absent', async () => {
      const client = clientWithStatus({ 'sunmi.statuslight': ['sunmi.statuslight'] });
      const light = await SunmiLightClient.create(client);
      expect(light.backend).toBe('sunmi.statuslight');
      expect(light.capabilities).toEqual({ multiFlash: true, timeout: false });
    });

    it('throws when no backend is available', async () => {
      const client = clientWithStatus({ 'sunmi.printer': ['sunmi.printer'] });
      await expect(SunmiLightClient.create(client)).rejects.toThrow(/No Sunmi light backend/);
    });
  });

  describe('delegation', () => {
    it('delegates on/off to the tms.led backend', async () => {
      const client = clientWithStatus({ 'sunmi.tms.led': ['sunmi.tms.led'] });
      const light = await SunmiLightClient.create(client);

      await light.on('green', { timeoutMs: 5000 });
      expect(client.execute).toHaveBeenCalledWith('sunmi.tms.led.on', { color: 'green', timeoutMs: 5000 });

      await light.off();
      expect(client.execute).toHaveBeenCalledWith('sunmi.tms.led.off', {});
    });

    it('exposes multiFlash only for the statuslight backend', async () => {
      const tms = await SunmiLightClient.create(clientWithStatus({ 'sunmi.tms.led': ['sunmi.tms.led'] }));
      expect(tms.multiFlash).toBeUndefined();

      const flexClient = clientWithStatus({ 'sunmi.statuslight': ['sunmi.statuslight'] });
      const flex = await SunmiLightClient.create(flexClient);
      expect(flex.multiFlash).toBeTypeOf('function');

      await flex.multiFlash!(['red', 'green'], 400, 100);
      expect(flexClient.execute).toHaveBeenCalledWith('sunmi.statuslight.multiFlash', {
        colors: ['red', 'green'],
        onMs: 400,
        offMs: 100,
      });
    });

    it('exposes onConnectionChanged only for the statuslight backend', async () => {
      const tms = await SunmiLightClient.create(clientWithStatus({ 'sunmi.tms.led': ['sunmi.tms.led'] }));
      expect(tms.onConnectionChanged).toBeUndefined();

      const flex = await SunmiLightClient.create(clientWithStatus({ 'sunmi.statuslight': ['sunmi.statuslight'] }));
      expect(flex.onConnectionChanged).toBeTypeOf('function');
    });

    it('propagates the timeout-unsupported error from statuslight', async () => {
      const flex = await SunmiLightClient.create(clientWithStatus({ 'sunmi.statuslight': ['sunmi.statuslight'] }));
      await expect(flex.on('red', { timeoutMs: 1000 })).rejects.toThrow(/not supported/);
    });
  });
});
