import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IExecutor, StatusLightColor } from '../types.js';
import { SunmiStatusLightClient } from '../sunmi-statuslight-client.js';
import { STATUS_LIGHT_COLORS } from '../types.js';

function createMockExecutor(): IExecutor {
  return {
    execute: vi.fn().mockResolvedValue({ status: 'ok' }),
  };
}

describe('SunmiStatusLightClient', () => {
  let executor: IExecutor;
  let client: SunmiStatusLightClient;

  beforeEach(() => {
    executor = createMockExecutor();
    client = new SunmiStatusLightClient(executor);
  });

  describe('setColor', () => {
    it('should call execute with correct method and params', async () => {
      const result = await client.setColor('red');

      expect(executor.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setColor',
        { color: 'red' },
      );
      expect(result).toEqual({ status: 'ok' });
    });

    it.each(STATUS_LIGHT_COLORS)('should support color "%s"', async (color) => {
      await client.setColor(color);

      expect(executor.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setColor',
        { color },
      );
    });
  });

  describe('turnOff', () => {
    it('should call execute with correct method and empty params', async () => {
      const result = await client.turnOff();

      expect(executor.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.turnOff',
        {},
      );
      expect(result).toEqual({ status: 'ok' });
    });
  });

  describe('setFlashing', () => {
    it('should call execute with correct method and params', async () => {
      const result = await client.setFlashing('blue', 500, 300);

      expect(executor.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setFlashing',
        { color: 'blue', onMs: 500, offMs: 300 },
      );
      expect(result).toEqual({ status: 'ok' });
    });
  });

  describe('setMultiFlashing', () => {
    it('should call execute with correct method and steps', async () => {
      const steps = [
        { color: 'red' as StatusLightColor, onMs: 400, offMs: 100 },
        { color: 'green' as StatusLightColor, onMs: 400, offMs: 100 },
        { color: 'blue' as StatusLightColor, onMs: 400, offMs: 100 },
      ];

      const result = await client.setMultiFlashing(steps);

      expect(executor.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setMultiFlashing',
        { steps },
      );
      expect(result).toEqual({ status: 'ok' });
    });

    it('should handle empty steps array', async () => {
      await client.setMultiFlashing([]);

      expect(executor.execute).toHaveBeenCalledWith(
        'sunmi.statuslight.setMultiFlashing',
        { steps: [] },
      );
    });
  });

  describe('error propagation', () => {
    it('should propagate executor errors', async () => {
      const error = new Error('device_not_ready');
      (executor.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.setColor('red')).rejects.toThrow('device_not_ready');
    });

    it('should propagate errors for all methods', async () => {
      const error = new Error('unauthorized');
      (executor.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.turnOff()).rejects.toThrow('unauthorized');
      await expect(client.setFlashing('red', 500, 500)).rejects.toThrow('unauthorized');
      await expect(client.setMultiFlashing([])).rejects.toThrow('unauthorized');
    });
  });
});
