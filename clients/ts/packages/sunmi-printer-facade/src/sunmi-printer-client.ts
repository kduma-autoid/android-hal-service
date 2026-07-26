import type {
  DescribeResponse,
  IHalClient,
  InterfaceDescriptor,
  InterfaceProvider,
  IPrinter,
  PrinterCapabilities,
  PrinterFeature,
  PrinterImageStyle,
} from '@kduma-autoid/hal-client-common';
import { PROVIDER_PARAM_KEY } from '@kduma-autoid/hal-client-common';

const PRINTER_INTERFACE = 'printer';

/** System event emitted when a plugin's dynamic availability changes. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** System event emitted when interface provider order/enable changes. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

/** A printer provider's pluginId (backend), e.g. "sunmi.printerx.printer". */
export type PrinterBackend = string;

/**
 * Unified {@link IPrinter} client backed by the server-side `printer` interface. Calls
 * `printer.printEscPos/printTspl/printZpl/printImage/cut`, targeting the interface's default
 * provider — or a specific one pinned via {@link SunmiPrinterClient.forBackend}, injected through
 * the reserved `__provider` param. Each method is exposed only when the chosen provider advertises
 * the matching interface feature in `system.describe`, so the caller programs against one type and
 * the backend is transparent.
 */
export class SunmiPrinterClient implements IPrinter {
  /** The active provider's pluginId (e.g. "sunmi.printerx.printer"). */
  readonly backend: string;
  readonly capabilities: PrinterCapabilities;

  private readonly client: IHalClient;
  private readonly isDefaultProvider: boolean;

  private constructor(
    client: IHalClient,
    backend: string,
    capabilities: PrinterCapabilities,
    isDefaultProvider: boolean,
  ) {
    this.client = client;
    this.backend = backend;
    this.capabilities = capabilities;
    this.isDefaultProvider = isDefaultProvider;
  }

  /** Binds to the interface's default provider; throws if `printer` has no available provider. */
  static async create(client: IHalClient): Promise<SunmiPrinterClient> {
    const provider = await SunmiPrinterClient.detect(client);
    if (!provider) {
      throw new Error('No printer backend available (interface "printer" has no provider)');
    }
    return SunmiPrinterClient.fromProvider(client, provider);
  }

  /** Binds to a specific provider by pluginId (e.g. to override the default). */
  static async forBackend(client: IHalClient, pluginId: string): Promise<SunmiPrinterClient> {
    const iface = await SunmiPrinterClient.describePrinter(client);
    const provider = iface?.providers.find((p) => p.pluginId === pluginId);
    if (!provider) {
      throw new Error(`Printer provider not available: ${pluginId}`);
    }
    return SunmiPrinterClient.fromProvider(client, provider);
  }

  /**
   * All providers of the `printer` interface, in the service's effective order (the first enabled
   * one is the default). Useful for offering a backend picker; bind one with
   * {@link SunmiPrinterClient.forBackend}.
   */
  static async listBackends(client: IHalClient): Promise<InterfaceProvider[]> {
    const iface = await SunmiPrinterClient.describePrinter(client);
    return iface?.providers ?? [];
  }

  /** The default provider of the `printer` interface, or null if none is available. */
  static async detect(client: IHalClient): Promise<InterfaceProvider | null> {
    const iface = await SunmiPrinterClient.describePrinter(client);
    if (!iface || iface.providers.length === 0) return null;
    // Prefer the interface default; otherwise the first enabled provider (disabled ones are listed
    // by the API but are not routable).
    return iface.providers.find((p) => p.isDefault) ?? iface.providers.find((p) => p.enabled) ?? null;
  }

  /**
   * Subscribes to backend-availability changes — hardware hot-plug (`system.plugins.changed`) and
   * interface order/enable changes (`system.interfaces.changed`). Call `create()`/`detect()` again
   * from the handler to pick up the new default. Resolves to an unsubscribe function.
   */
  static async onChanged(client: IHalClient, handler: () => void): Promise<() => Promise<void>> {
    const offs = await Promise.all([
      client.on(PLUGINS_CHANGED_EVENT, () => handler()),
      client.on(INTERFACES_CHANGED_EVENT, () => handler()),
    ]);
    return async () => {
      for (const off of offs) await off();
    };
  }

  private static async describePrinter(client: IHalClient): Promise<InterfaceDescriptor | undefined> {
    const res = await client.execute<DescribeResponse>('system.describe', {});
    return res.interfaces?.find((i) => i.interfaceId === PRINTER_INTERFACE);
  }

  private static fromProvider(client: IHalClient, provider: InterfaceProvider): SunmiPrinterClient {
    const f = provider.features;
    const capabilities: PrinterCapabilities = {
      escpos: f.includes('escpos'),
      tspl: f.includes('tspl'),
      zpl: f.includes('zpl'),
      image: f.includes('image'),
      cut: f.includes('cut'),
    };
    return new SunmiPrinterClient(client, provider.pluginId, capabilities, provider.isDefault);
  }

  /** Adds the `__provider` selector unless this client is bound to the interface's default provider. */
  private params(base: Record<string, unknown>): Record<string, unknown> {
    return this.isDefaultProvider ? base : { ...base, [PROVIDER_PARAM_KEY]: this.backend };
  }

  /**
   * Builds a call bound function for a feature-gated method, or `undefined` when the backend does
   * not advertise the feature (so the method is absent, matching the {@link IPrinter} contract).
   */
  private gated<A extends unknown[]>(
    feature: PrinterFeature,
    method: string,
    toParams: (...args: A) => Record<string, unknown>,
  ): ((...args: A) => Promise<void>) | undefined {
    if (!this.capabilities[feature]) return undefined;
    return async (...args: A): Promise<void> => {
      await this.client.execute(method, this.params(toParams(...args)));
    };
  }

  get printEscPos(): ((data: string) => Promise<void>) | undefined {
    return this.gated('escpos', 'printer.printEscPos', (data: string) => ({ data }));
  }

  get printTspl(): ((data: string) => Promise<void>) | undefined {
    return this.gated('tspl', 'printer.printTspl', (data: string) => ({ data }));
  }

  get printZpl(): ((data: string) => Promise<void>) | undefined {
    return this.gated('zpl', 'printer.printZpl', (data: string) => ({ data }));
  }

  get printImage():
    | ((bitmap: string, style?: PrinterImageStyle) => Promise<void>)
    | undefined {
    return this.gated('image', 'printer.printImage', (bitmap: string, style?: PrinterImageStyle) =>
      style === undefined ? { bitmap } : { bitmap, style },
    );
  }

  get cut(): (() => Promise<void>) | undefined {
    return this.gated('cut', 'printer.cut', () => ({}));
  }
}
