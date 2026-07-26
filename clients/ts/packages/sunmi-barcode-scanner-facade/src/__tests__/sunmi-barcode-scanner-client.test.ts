import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiBarcodeScannerClient } from '../sunmi-barcode-scanner-client.js';

interface FakeProvider {
  pluginId: string;
  isDefault?: boolean;
}

function clientWithScanner(providers: FakeProvider[]): IHalClient {
  const execute = vi.fn(async (method: string) => {
    if (method === 'system.describe') {
      return {
        plugins: [],
        interfaces: providers.length
          ? [
              {
                kind: 'interface',
                interfaceId: 'barcodeScanner',
                version: 1,
                features: [],
                methods: [],
                events: [],
                providers: providers.map((p) => ({
                  pluginId: p.pluginId,
                  source: 'builtin',
                  priority: 0,
                  isDefault: !!p.isDefault,
                  enabled: true,
                  features: [],
                })),
              },
            ]
          : [],
      };
    }
    return { status: 'scanning' };
  });
  return { execute, on: vi.fn().mockResolvedValue(vi.fn()) } as unknown as IHalClient;
}

const INNER: FakeProvider = { pluginId: 'sunmi.scanner.inner', isDefault: true };
const CAMERA: FakeProvider = { pluginId: 'sunmi.scanner.camera' };

describe('SunmiBarcodeScannerClient (barcodeScanner interface)', () => {
  describe('detect / create', () => {
    it('binds to the default provider (inner)', async () => {
      const scanner = await SunmiBarcodeScannerClient.create(clientWithScanner([INNER, CAMERA]));
      expect(scanner.backend).toBe('sunmi.scanner.inner');
    });

    it('throws when the interface has no provider', async () => {
      await expect(SunmiBarcodeScannerClient.create(clientWithScanner([]))).rejects.toThrow(
        /No barcode scanner backend/,
      );
    });

    it('lists all backends in service order', async () => {
      const backends = await SunmiBarcodeScannerClient.listBackends(clientWithScanner([INNER, CAMERA]));
      expect(backends.map((b) => b.pluginId)).toEqual(['sunmi.scanner.inner', 'sunmi.scanner.camera']);
      expect(await SunmiBarcodeScannerClient.listBackends(clientWithScanner([]))).toEqual([]);
    });
  });

  describe('calls', () => {
    it('calls barcodeScanner.* on the default provider without a __provider selector', async () => {
      const client = clientWithScanner([INNER]);
      const scanner = await SunmiBarcodeScannerClient.create(client);

      await scanner.trigger();
      expect(client.execute).toHaveBeenCalledWith('barcodeScanner.trigger', {});

      await scanner.stop();
      expect(client.execute).toHaveBeenCalledWith('barcodeScanner.stop', {});
    });

    it('injects __provider when bound to a non-default provider', async () => {
      const client = clientWithScanner([INNER, CAMERA]);
      const cam = await SunmiBarcodeScannerClient.forBackend(client, 'sunmi.scanner.camera');
      expect(cam.backend).toBe('sunmi.scanner.camera');

      await cam.trigger();
      expect(client.execute).toHaveBeenCalledWith('barcodeScanner.trigger', {
        __provider: 'sunmi.scanner.camera',
      });
    });
  });

  describe('onScan', () => {
    it('subscribes to barcodeScanner.onScan filtered to the bound backend', async () => {
      const off = vi.fn().mockResolvedValue(undefined);
      let captured: ((name: string, data: unknown) => void) | undefined;
      const on = vi.fn(async (_event: string, handler: (name: string, data: unknown) => void) => {
        captured = handler;
        return off;
      });
      const execute = vi.fn(async () => ({
        plugins: [],
        interfaces: [
          {
            kind: 'interface',
            interfaceId: 'barcodeScanner',
            version: 1,
            features: [],
            methods: [],
            events: [],
            providers: [
              { pluginId: 'sunmi.scanner.inner', source: 'builtin', priority: 0, isDefault: true, enabled: true, features: [] },
            ],
          },
        ],
      }));
      const client = { execute, on } as unknown as IHalClient;

      const scanner = await SunmiBarcodeScannerClient.create(client);
      const received: unknown[] = [];
      const unsub = await scanner.onScan((scan) => received.push(scan));

      expect(on).toHaveBeenCalledWith('barcodeScanner.onScan@sunmi.scanner.inner', expect.any(Function));

      captured?.('barcodeScanner.onScan', { data: '5901234123457', format: 'EAN13' });
      expect(received).toEqual([{ data: '5901234123457', format: 'EAN13' }]);

      await unsub();
      expect(off).toHaveBeenCalledTimes(1);
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
      const offAll = await SunmiBarcodeScannerClient.onChanged(client, handler);

      expect(on).toHaveBeenCalledWith('system.plugins.changed', expect.any(Function));
      expect(on).toHaveBeenCalledWith('system.interfaces.changed', expect.any(Function));
      handlers[0]();
      expect(handler).toHaveBeenCalledTimes(1);

      await offAll();
      expect(unsub).toHaveBeenCalledTimes(2);
    });
  });
});
