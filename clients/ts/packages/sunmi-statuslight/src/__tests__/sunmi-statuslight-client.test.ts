import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient, LightColor } from '@kduma-autoid/hal-client-common';
import { SunmiStatusLightClient } from '../sunmi-statuslight-client.js';
import { LIGHT_COLORS } from '../types.js';

function createMockClient(): IHalClient {
  const unsub = vi.fn().mockResolvedValue(undefined);
  return {
    execute: vi.fn().mockResolvedValue({ status: 'ok' }),
    on: vi.fn().mockResolvedValue(unsub),
  };
}

describe('SunmiStatusLightClient', () => {
  let mockClient: IHalClient;
  let client: SunmiStatusLightClient;

  beforeEach(() => {
    mockClient = createMockClient();
    client = new SunmiStatusLightClient(mockClient);
  });

  it('advertises capabilities (multiFlash yes, timeout no)', () => {
    expect(client.capabilities).toEqual({ multiFlash: true, timeout: false });
  });

  describe('isSupported', () => {
    it('maps result to boolean', async () => {
      (mockClient.execute as ReturnType<typeof vi.fn>).mockResolvedValue({ result: true });
      await expect(client.isSupported()).resolves.toBe(true);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.isSupported', {});
    });
  });

  describe('on', () => {
    it.each(LIGHT_COLORS)('turns on color "%s"', async (color) => {
      await client.on(color);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.on', { color });
    });

    it('throws when timeoutMs is supplied (unsupported)', async () => {
      await expect(client.on('red', { timeoutMs: 1000 })).rejects.toThrow(/not supported/);
      expect(mockClient.execute).not.toHaveBeenCalled();
    });

    it('allows timeoutMs of 0', async () => {
      await client.on('red', { timeoutMs: 0 });
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.on', { color: 'red' });
    });
  });

  describe('off', () => {
    it('calls execute with empty params', async () => {
      await client.off();
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.off', {});
    });
  });

  describe('flash', () => {
    it('calls execute with color and timings', async () => {
      await client.flash('blue', 500, 300);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.flash', {
        color: 'blue',
        onMs: 500,
        offMs: 300,
      });
    });

    it('throws when timeoutMs is supplied', async () => {
      await expect(client.flash('blue', 500, 300, { timeoutMs: 2000 })).rejects.toThrow(/not supported/);
    });
  });

  describe('multiFlash (hybrid signature)', () => {
    it('accepts a colors array with uniform timing', async () => {
      await client.multiFlash(['red', 'green', 'blue'], 400, 100);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.multiFlash', {
        colors: ['red', 'green', 'blue'],
        onMs: 400,
        offMs: 100,
      });
    });

    it('accepts an explicit steps array', async () => {
      const steps = [
        { color: 'red' as LightColor, onMs: 400, offMs: 100 },
        { color: 'green' as LightColor, onMs: 300, offMs: 100 },
      ];
      await client.multiFlash(steps);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.statuslight.multiFlash', { steps });
    });
  });

  describe('onConnectionChanged', () => {
    it('subscribes to the event and maps the payload to a boolean', async () => {
      let captured: ((eventName: string, data: unknown) => void) | null = null;
      const unsub = vi.fn().mockResolvedValue(undefined);
      const on = vi.fn(async (_event: string, handler: (n: string, d: unknown) => void) => {
        captured = handler;
        return unsub;
      });
      const c = { execute: vi.fn().mockResolvedValue({ status: 'ok' }), on } as unknown as IHalClient;
      const cl = new SunmiStatusLightClient(c);

      const received: boolean[] = [];
      const off = await cl.onConnectionChanged((v) => received.push(v));

      expect(on).toHaveBeenCalledWith('sunmi.statuslight.connectionChanged', expect.any(Function));
      captured!('sunmi.statuslight.connectionChanged', { connected: true });
      captured!('sunmi.statuslight.connectionChanged', { connected: false });
      expect(received).toEqual([true, false]);
      expect(off).toBe(unsub);
    });
  });

  describe('error propagation', () => {
    it('propagates execute errors', async () => {
      const error = new Error('unauthorized');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.on('red')).rejects.toThrow('unauthorized');
      await expect(client.off()).rejects.toThrow('unauthorized');
      await expect(client.flash('red', 500, 500)).rejects.toThrow('unauthorized');
      await expect(client.multiFlash(['red'], 500, 500)).rejects.toThrow('unauthorized');
    });
  });
});
