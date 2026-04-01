import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { StatusLightColor } from '../types.js';
import { SunmiStatusLightClient } from '../sunmi-statuslight-client.js';
import { STATUS_LIGHT_COLORS } from '../types.js';

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

  describe('setColor', () => {
    it('should call execute with correct method and params', async () => {
      await client.setColor('red');

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setColor',
        { color: 'red' },
      );
    });

    it.each(STATUS_LIGHT_COLORS)('should support color "%s"', async (color) => {
      await client.setColor(color);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setColor',
        { color },
      );
    });
  });

  describe('turnOff', () => {
    it('should call execute with correct method and empty params', async () => {
      await client.turnOff();

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.turnOff',
        {},
      );
    });
  });

  describe('setFlashing', () => {
    it('should call execute with correct method and params', async () => {
      await client.setFlashing('blue', 500, 300);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setFlashing',
        { color: 'blue', onMs: 500, offMs: 300 },
      );
    });
  });

  describe('setMultiFlashing', () => {
    it('should call execute with correct method and steps', async () => {
      const steps = [
        { color: 'red' as StatusLightColor, onMs: 400, offMs: 100 },
        { color: 'green' as StatusLightColor, onMs: 400, offMs: 100 },
        { color: 'blue' as StatusLightColor, onMs: 400, offMs: 100 },
      ];

      await client.setMultiFlashing(steps);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setMultiFlashing',
        { steps },
      );
    });

    it('should handle empty steps array', async () => {
      await client.setMultiFlashing([]);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setMultiFlashing',
        { steps: [] },
      );
    });
  });

  describe('error propagation', () => {
    it('should propagate mockClient errors', async () => {
      const error = new Error('device_not_ready');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.setColor('red')).rejects.toThrow('device_not_ready');
    });

    it('should propagate errors for all methods', async () => {
      const error = new Error('unauthorized');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.turnOff()).rejects.toThrow('unauthorized');
      await expect(client.setFlashing('red', 500, 500)).rejects.toThrow('unauthorized');
      await expect(client.setMultiFlashing([])).rejects.toThrow('unauthorized');
    });
  });
});
