import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiPrinterClient } from '../sunmi-printer-client.js';

interface FakeProvider {
  pluginId: string;
  features: string[];
  isDefault?: boolean;
}

function clientWithPrinter(providers: FakeProvider[]): IHalClient {
  const execute = vi.fn(async (method: string) => {
    if (method === 'system.describe') {
      return {
        plugins: [],
        interfaces: providers.length
          ? [
              {
                kind: 'interface',
                interfaceId: 'printer',
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
                  features: p.features,
                })),
              },
            ]
          : [],
      };
    }
    return { status: 'ok' };
  });
  return { execute, on: vi.fn().mockResolvedValue(vi.fn()) } as unknown as IHalClient;
}

// Mirrors the real sunmi.printerx.printer binding: everything except ZPL.
const PRINTERX: FakeProvider = {
  pluginId: 'sunmi.printerx.printer',
  features: ['escpos', 'tspl', 'image', 'cut'],
  isDefault: true,
};

describe('SunmiPrinterClient (printer interface)', () => {
  describe('detect / create', () => {
    it('binds to the default provider with capabilities from features', async () => {
      const printer = await SunmiPrinterClient.create(clientWithPrinter([PRINTERX]));
      expect(printer.backend).toBe('sunmi.printerx.printer');
      expect(printer.capabilities).toEqual({
        escpos: true,
        tspl: true,
        zpl: false,
        image: true,
        cut: true,
      });
    });

    it('throws when the interface has no provider', async () => {
      await expect(SunmiPrinterClient.create(clientWithPrinter([]))).rejects.toThrow(
        /No printer backend/,
      );
    });
  });

  describe('feature-gated methods', () => {
    it('exposes supported methods and omits unsupported ones', async () => {
      const printer = await SunmiPrinterClient.create(clientWithPrinter([PRINTERX]));
      expect(printer.printEscPos).toBeTypeOf('function');
      expect(printer.printTspl).toBeTypeOf('function');
      expect(printer.printImage).toBeTypeOf('function');
      expect(printer.cut).toBeTypeOf('function');
      // No `zpl` feature -> method absent.
      expect(printer.printZpl).toBeUndefined();
    });

    it('calls printer.* on the default provider without a __provider selector', async () => {
      const client = clientWithPrinter([PRINTERX]);
      const printer = await SunmiPrinterClient.create(client);

      await printer.printEscPos!('G0A=');
      expect(client.execute).toHaveBeenCalledWith('printer.printEscPos', { data: 'G0A=' });

      await printer.cut!();
      expect(client.execute).toHaveBeenCalledWith('printer.cut', {});

      await printer.printImage!('iVBOR', { algorithm: 'BINARIZATION', value: 200 });
      expect(client.execute).toHaveBeenCalledWith('printer.printImage', {
        bitmap: 'iVBOR',
        style: { algorithm: 'BINARIZATION', value: 200 },
      });
    });

    it('omits the style key when printImage is called without a style', async () => {
      const client = clientWithPrinter([PRINTERX]);
      const printer = await SunmiPrinterClient.create(client);
      await printer.printImage!('iVBOR');
      expect(client.execute).toHaveBeenCalledWith('printer.printImage', { bitmap: 'iVBOR' });
    });
  });

  describe('forBackend', () => {
    it('injects __provider when bound to a non-default provider', async () => {
      const client = clientWithPrinter([
        PRINTERX,
        { pluginId: 'other.printer', features: ['escpos'] },
      ]);
      const other = await SunmiPrinterClient.forBackend(client, 'other.printer');
      expect(other.backend).toBe('other.printer');

      await other.printEscPos!('G0A=');
      expect(client.execute).toHaveBeenCalledWith('printer.printEscPos', {
        data: 'G0A=',
        __provider: 'other.printer',
      });
    });

    it('throws for an unknown provider', async () => {
      const client = clientWithPrinter([PRINTERX]);
      await expect(SunmiPrinterClient.forBackend(client, 'nope.printer')).rejects.toThrow(
        /not available/,
      );
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
      const off = await SunmiPrinterClient.onChanged(client, handler);

      expect(on).toHaveBeenCalledWith('system.plugins.changed', expect.any(Function));
      expect(on).toHaveBeenCalledWith('system.interfaces.changed', expect.any(Function));
      handlers[0]();
      expect(handler).toHaveBeenCalledTimes(1);

      await off();
      expect(unsub).toHaveBeenCalledTimes(2);
    });
  });
});
