import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { MagneticCardReaderClient } from '../magnetic-card-reader-client.js';

interface FakeProvider {
  pluginId: string;
  isDefault?: boolean;
  enabled?: boolean;
  features?: string[];
}

const MSR605X: FakeProvider = { pluginId: 'msr605x', isDefault: true, features: ['read', 'write'] };
const SUNMI_CARD: FakeProvider = { pluginId: 'sunmi.card', features: [] };

function clientWith(providers: FakeProvider[], reply: unknown = {}): IHalClient {
  const execute = vi.fn(async (method: string) => {
    if (method === 'system.describe') {
      return {
        plugins: [],
        interfaces: providers.length
          ? [
              {
                kind: 'interface',
                interfaceId: 'magneticCardReader',
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
                  features: p.features ?? [],
                })),
              },
            ]
          : [],
      };
    }
    return reply;
  });
  return { execute, on: vi.fn().mockResolvedValue(vi.fn()) } as unknown as IHalClient;
}

describe('MagneticCardReaderClient (magneticCardReader interface)', () => {
  describe('binding', () => {
    it('binds to the default provider with its features as capabilities', async () => {
      const reader = await MagneticCardReaderClient.create(clientWith([MSR605X, SUNMI_CARD]));
      expect(reader.backend).toBe('msr605x');
      expect(reader.capabilities).toEqual({ read: true, write: true });
    });

    it('throws when the interface has no provider', async () => {
      await expect(MagneticCardReaderClient.create(clientWith([]))).rejects.toThrow(/No magnetic card reader backend/);
    });

    it('lists backends in service order', async () => {
      const backends = await MagneticCardReaderClient.listBackends(clientWith([MSR605X, SUNMI_CARD]));
      expect(backends.map((b) => b.pluginId)).toEqual(['msr605x', 'sunmi.card']);
    });

    it('refuses to bind a disabled provider', async () => {
      const client = clientWith([MSR605X, { ...SUNMI_CARD, enabled: false }]);
      await expect(MagneticCardReaderClient.forBackend(client, 'sunmi.card')).rejects.toThrow(/disabled/);
    });

    it('refuses to bind an unknown provider', async () => {
      await expect(MagneticCardReaderClient.forBackend(clientWith([MSR605X]), 'nope')).rejects.toThrow(/not available/);
    });
  });

  describe('feature gating', () => {
    it('a passive reader exposes no read, cancel or write', async () => {
      const reader = await MagneticCardReaderClient.forBackend(clientWith([MSR605X, SUNMI_CARD]), 'sunmi.card');
      expect(reader.capabilities).toEqual({ read: false, write: false });
      expect(reader.read).toBeUndefined();
      expect(reader.cancel).toBeUndefined();
      expect(reader.write).toBeUndefined();
    });

    it('an active reader exposes all three', async () => {
      const reader = await MagneticCardReaderClient.create(clientWith([MSR605X]));
      expect(reader.read).toBeTypeOf('function');
      expect(reader.cancel).toBeTypeOf('function');
      expect(reader.write).toBeTypeOf('function');
    });
  });

  describe('calls are pinned to the backend', () => {
    it('read passes the timeout and returns the tracks', async () => {
      const tracks = { track1: 'B4111^DOE/JOHN^2512', track2: '4111=2512', track3: '' };
      const client = clientWith([MSR605X], tracks);
      const reader = await MagneticCardReaderClient.create(client);

      await expect(reader.read!({ timeoutMs: 5000 })).resolves.toEqual(tracks);
      expect(client.execute).toHaveBeenLastCalledWith('magneticCardReader.read@msr605x', { timeoutMs: 5000 });
    });

    it('write sends the tracks and options unchanged', async () => {
      const client = clientWith([MSR605X], { status: 'ok' });
      const reader = await MagneticCardReaderClient.create(client);

      await reader.write!({ track1: 'ABC', track2: '123' }, { timeoutMs: 8000 });
      expect(client.execute).toHaveBeenLastCalledWith('magneticCardReader.write@msr605x', {
        track1: 'ABC',
        track2: '123',
        timeoutMs: 8000,
      });
    });

    it('cancel reports whether anything was pending', async () => {
      const client = clientWith([MSR605X], { cancelled: true });
      const reader = await MagneticCardReaderClient.create(client);

      await expect(reader.cancel!()).resolves.toBe(true);
      expect(client.execute).toHaveBeenLastCalledWith('magneticCardReader.cancel@msr605x', {});
    });

    it('getInfo works on a passive reader', async () => {
      const client = clientWith([MSR605X, SUNMI_CARD], { name: 'Sunmi MSR', connected: true });
      const reader = await MagneticCardReaderClient.forBackend(client, 'sunmi.card');

      await expect(reader.getInfo()).resolves.toEqual({ name: 'Sunmi MSR', connected: true });
      expect(client.execute).toHaveBeenLastCalledWith('magneticCardReader.getInfo@sunmi.card', {});
    });
  });

  it('onSwipe subscribes only to this backend', async () => {
    const client = clientWith([MSR605X, SUNMI_CARD]);
    const reader = await MagneticCardReaderClient.forBackend(client, 'sunmi.card');
    const handler = vi.fn();

    await reader.onSwipe(handler);
    expect(client.on).toHaveBeenCalledWith('magneticCardReader.onSwipe@sunmi.card', expect.any(Function));

    const cb = vi.mocked(client.on).mock.calls[0][1] as (name: string, data: unknown) => void;
    cb('magneticCardReader.onSwipe', { track1: '', track2: '123', track3: '' });
    expect(handler).toHaveBeenCalledWith({ track1: '', track2: '123', track3: '' });
  });
});
