import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiNfcClient } from '../sunmi-nfc-client.js';

function createMockClient(): IHalClient {
  const unsub = vi.fn().mockResolvedValue(undefined);
  return {
    execute: vi.fn().mockResolvedValue({ status: 'ok' }),
    on: vi.fn().mockResolvedValue(unsub),
  };
}

describe('SunmiNfcClient', () => {
  let mockClient: IHalClient;
  let client: SunmiNfcClient;

  beforeEach(() => {
    mockClient = createMockClient();
    client = new SunmiNfcClient(mockClient);
  });

  describe('switchModule', () => {
    it('should call execute with provided sn', async () => {
      await client.switchModule('NFC-001');

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.nfc.switchModule',
        { sn: 'NFC-001' },
      );
    });

    it('should call execute with empty sn when not provided', async () => {
      await client.switchModule();

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.nfc.switchModule',
        { sn: '' },
      );
    });
  });

  describe('setWatermarkAlpha', () => {
    it('should call execute with correct method and params', async () => {
      await client.setWatermarkAlpha(80);

      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.nfc.setWatermarkAlpha',
        { alpha: 80 },
      );
    });

    it('should accept boundary values', async () => {
      await client.setWatermarkAlpha(0);
      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.nfc.setWatermarkAlpha',
        { alpha: 0 },
      );

      await client.setWatermarkAlpha(100);
      expect(mockClient.execute).toHaveBeenCalledWith(
        'sunmi.nfc.setWatermarkAlpha',
        { alpha: 100 },
      );
    });
  });

  describe('error propagation', () => {
    it('should propagate executor errors', async () => {
      const error = new Error('device_not_ready');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.switchModule('NFC-001')).rejects.toThrow('device_not_ready');
    });

    it('should propagate errors for all methods', async () => {
      const error = new Error('unauthorized');
      (mockClient.execute as ReturnType<typeof vi.fn>).mockRejectedValue(error);

      await expect(client.switchModule()).rejects.toThrow('unauthorized');
      await expect(client.setWatermarkAlpha(50)).rejects.toThrow('unauthorized');
    });
  });

  describe('events', () => {
    it('should delegate to client.on()', async () => {
      const handler = vi.fn();

      await client.onModulesChanged(handler);

      expect(mockClient.on).toHaveBeenCalledWith(
        'sunmi.nfc.modulesChanged',
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
      await client.onModulesChanged(handler);

      const eventData = { modules: [{ sn: 'NFC-001' }] };
      capturedHandler('sunmi.nfc.modulesChanged', eventData);

      expect(handler).toHaveBeenCalledWith(eventData);
    });

    it('should return unsubscribe function', async () => {
      const unsub = vi.fn().mockResolvedValue(undefined);
      (mockClient.on as ReturnType<typeof vi.fn>).mockResolvedValue(unsub);

      const result = await client.onModulesChanged(() => {});

      expect(result).toBe(unsub);
    });
  });
});
