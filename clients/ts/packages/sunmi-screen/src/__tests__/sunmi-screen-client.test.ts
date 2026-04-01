import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiScreenClient } from '../sunmi-screen-client.js';

function createMockClient(): IHalClient {
  const unsub = vi.fn().mockResolvedValue(undefined);
  return {
    execute: vi.fn().mockResolvedValue({ status: 'ok' }),
    on: vi.fn().mockResolvedValue(unsub),
  };
}

describe('SunmiScreenClient', () => {
  let mockClient: IHalClient;
  let client: SunmiScreenClient;

  beforeEach(() => {
    mockClient = createMockClient();
    client = new SunmiScreenClient(mockClient);
  });

  describe('getDeviceInfo', () => {
    it('should call execute with correct method and return info', async () => {
      const mockInfo = { info: { sn: 'SCR-001', resolution: '1920x1080' } };
      (mockClient.execute as ReturnType<typeof vi.fn>).mockResolvedValue(mockInfo);

      const result = await client.getDeviceInfo();

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.getDeviceInfo',
        {},
      );
      expect(result).toEqual(mockInfo);
    });
  });

  describe('setScreenSwitch', () => {
    it('should call execute with sn and enabled=true', async () => {
      await client.setScreenSwitch('SCR-001', true);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setScreenSwitch',
        { sn: 'SCR-001', enabled: true },
      );
    });

    it('should call execute with sn and enabled=false', async () => {
      await client.setScreenSwitch('SCR-001', false);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setScreenSwitch',
        { sn: 'SCR-001', enabled: false },
      );
    });
  });

  describe('setTouchSwitch', () => {
    it('should call execute with sn and enabled=true', async () => {
      await client.setTouchSwitch('SCR-001', true);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setTouchSwitch',
        { sn: 'SCR-001', enabled: true },
      );
    });

    it('should call execute with sn and enabled=false', async () => {
      await client.setTouchSwitch('SCR-001', false);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setTouchSwitch',
        { sn: 'SCR-001', enabled: false },
      );
    });
  });

  describe('setBrightness', () => {
    it('should call execute with correct method and params', async () => {
      await client.setBrightness('SCR-001', 80);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setBrightness',
        { sn: 'SCR-001', brightness: 80 },
      );
    });

    it('should accept boundary values', async () => {
      await client.setBrightness('SCR-001', 0);
      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setBrightness',
        { sn: 'SCR-001', brightness: 0 },
      );

      await client.setBrightness('SCR-001', 100);
      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.screen.setBrightness',
        { sn: 'SCR-001', brightness: 100 },
      );
    });
  });

  describe('error propagation', () => {
    it('should propagate executor errors', async () => {
      const error = new Error('device_not_ready');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.getDeviceInfo()).rejects.toThrow('device_not_ready');
    });

    it('should propagate errors for all methods', async () => {
      const error = new Error('unauthorized');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.setScreenSwitch('SCR-001', true)).rejects.toThrow('unauthorized');
      await expect(client.setTouchSwitch('SCR-001', true)).rejects.toThrow('unauthorized');
      await expect(client.setBrightness('SCR-001', 50)).rejects.toThrow('unauthorized');
    });
  });

  describe('events', () => {
    it('should delegate to client.on()', async () => {
      const handler = vi.fn();

      await client.onScreensChanged(handler);

      expect(mockClient.on).toHaveBeenCalledWith(
        'sunmi.screen.screensChanged',
        expect.any(Function),
      );
    });

    it('should pass event data to handler without event name', async () => {
      let capturedHandler: (eventName: string, data: unknown) => void = () => {};
      (mockClient.on as ReturnType<typeof vi.fn>).mockImplementation(async (_event, handler) => {
        capturedHandler = handler;
        return async () => {};
      });

      const handler = vi.fn();
      await client.onScreensChanged(handler);

      const eventData = { sn: 'SCR-001', type: 1, value: 0, extra: '' };
      capturedHandler('sunmi.screen.screensChanged', eventData);

      expect(handler).toHaveBeenCalledWith(eventData);
    });

    it('should return unsubscribe function', async () => {
      const unsub = vi.fn().mockResolvedValue(undefined);
      (mockClient.on as ReturnType<typeof vi.fn>).mockResolvedValue(unsub);

      const result = await client.onScreensChanged(() => {});

      expect(result).toBe(unsub);
    });
  });
});
