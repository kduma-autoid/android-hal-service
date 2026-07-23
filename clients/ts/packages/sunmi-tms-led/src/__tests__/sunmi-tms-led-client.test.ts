import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiTmsLedClient } from '../sunmi-tms-led-client.js';
import { TMS_LED_COLORS } from '../types.js';

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

  describe('isSupported', () => {
    it('returns true when result is true', async () => {
      mockClient = createMockClient({ result: true });
      client = new SunmiTmsLedClient(mockClient);

      await expect(client.isSupported()).resolves.toBe(true);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.isSupported', {});
    });

    it('returns false when result is not true', async () => {
      mockClient = createMockClient({ result: false });
      client = new SunmiTmsLedClient(mockClient);

      await expect(client.isSupported()).resolves.toBe(false);
    });
  });

  describe('open', () => {
    it('fills defaults for optional fields', async () => {
      await client.open({ color: 'green' });

      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.open', {
        color: 'green',
        lightMode: 0,
        onMs: 0,
        offMs: 0,
        timeoutMs: 0,
      });
    });

    it('passes through all provided fields', async () => {
      await client.open({ color: 3, lightMode: 1, onMs: 500, offMs: 300, timeoutMs: 10000 });

      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.open', {
        color: 3,
        lightMode: 1,
        onMs: 500,
        offMs: 300,
        timeoutMs: 10000,
      });
    });
  });

  describe('close', () => {
    it('calls execute with empty params', async () => {
      await client.close();
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.close', {});
    });
  });

  describe('setColor (steady)', () => {
    it.each(TMS_LED_COLORS)('opens steady for color "%s"', async (color) => {
      await client.setColor(color);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.open', {
        color,
        lightMode: 0,
        onMs: 0,
        offMs: 0,
        timeoutMs: 0,
      });
    });
  });

  describe('setFlashing (blink)', () => {
    it('opens blink with timings', async () => {
      await client.setFlashing('blue', 500, 300);
      expect(mockClient.execute).toHaveBeenCalledWith('sunmi.tms.led.open', {
        color: 'blue',
        lightMode: 1,
        onMs: 500,
        offMs: 300,
        timeoutMs: 0,
      });
    });
  });

  describe('error propagation', () => {
    it('propagates execute errors', async () => {
      const error = new Error('unavailable');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.setColor('red')).rejects.toThrow('unavailable');
      await expect(client.close()).rejects.toThrow('unavailable');
    });
  });
});
