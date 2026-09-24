import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiTmsLedClient } from '../sunmi-tms-led-client.js';
import { LIGHT_COLORS } from '../types.js';

function createMockClient(result: unknown = { status: 'ok' }): IHalClient {
  const unsub = vi.fn().mockResolvedValue(undefined);
  return {
    execute: vi.fn().mockResolvedValue(result),
    on: vi.fn().mockResolvedValue(unsub),
  };
}

describe('SunmiTmsLedClient', () => {
  let mockClient: IHalClient;
  let client: SunmiTmsLedClient;

  beforeEach(() => {
    mockClient = createMockClient();
    client = new SunmiTmsLedClient(mockClient);
  });

  it('advertises capabilities (timeout yes, multiFlash no)', () => {
    expect(client.capabilities).toEqual({ multiFlash: false, timeout: true });
  });

  describe('on', () => {
    it.each(LIGHT_COLORS)('turns on steady color "%s" with default timeout 0', async (color) => {
      await client.on(color);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.on', { color, timeoutMs: 0 });
    });

    it('passes through timeoutMs', async () => {
      await client.on('green', { timeoutMs: 10000 });
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.on', { color: 'green', timeoutMs: 10000 });
    });
  });

  describe('flash', () => {
    it('blinks with timings and default timeout 0', async () => {
      await client.flash('blue', 500, 300);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.flash', {
        color: 'blue',
        onMs: 500,
        offMs: 300,
        timeoutMs: 0,
      });
    });

    it('passes through timeoutMs', async () => {
      await client.flash('red', 200, 200, { timeoutMs: 5000 });
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.flash', {
        color: 'red',
        onMs: 200,
        offMs: 200,
        timeoutMs: 5000,
      });
    });
  });

  describe('off', () => {
    it('calls execute with empty params', async () => {
      await client.off();
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.off', {});
    });
  });

  describe('error propagation', () => {
    it('propagates execute errors', async () => {
      const error = new Error('unavailable');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.on('red')).rejects.toThrow('unavailable');
      await expect(client.off()).rejects.toThrow('unavailable');
    });
  });
});
