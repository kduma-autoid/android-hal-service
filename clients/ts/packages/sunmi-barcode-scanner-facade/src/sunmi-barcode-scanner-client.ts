import type {
  DescribeResponse,
  IHalClient,
  InterfaceDescriptor,
  InterfaceProvider,
  IBarcodeScanner,
  ScanResult,
} from '@kduma-autoid/hal-client-common';
import { PROVIDER_PARAM_KEY } from '@kduma-autoid/hal-client-common';

const BARCODE_SCANNER_INTERFACE = 'barcodeScanner';
const ON_SCAN_EVENT = 'barcodeScanner.onScan';

/** System event emitted when a plugin's dynamic availability changes. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** System event emitted when interface provider order/enable changes. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

/** A barcode-scanner provider's pluginId (backend), e.g. "sunmi.scanner.inner". */
export type BarcodeScannerBackend = string;

/**
 * Unified {@link IBarcodeScanner} client backed by the server-side `barcodeScanner` interface.
 * Calls `barcodeScanner.trigger/stop`, targeting the interface's default provider — or a specific one pinned via
 * {@link SunmiBarcodeScannerClient.forBackend}, injected through the reserved `__provider` param.
 *
 * {@link SunmiBarcodeScannerClient.onScan} subscribes to `barcodeScanner.onScan` filtered to THIS
 * backend by event source (`barcodeScanner.onScan@<backend>`), so a client bound to the built-in
 * scanner never sees an external scanner's barcodes.
 */
export class SunmiBarcodeScannerClient implements IBarcodeScanner {
  /** The active provider's pluginId (e.g. "sunmi.scanner.inner"). */
  readonly backend: string;

  private readonly client: IHalClient;
  private readonly isDefaultProvider: boolean;

  private constructor(client: IHalClient, backend: string, isDefaultProvider: boolean) {
    this.client = client;
    this.backend = backend;
    this.isDefaultProvider = isDefaultProvider;
  }

  /** Binds to the interface's default provider; throws if `barcodeScanner` has no provider. */
  static async create(client: IHalClient): Promise<SunmiBarcodeScannerClient> {
    const provider = await SunmiBarcodeScannerClient.detect(client);
    if (!provider) {
      throw new Error('No barcode scanner backend available (interface "barcodeScanner" has no provider)');
    }
    return SunmiBarcodeScannerClient.fromProvider(client, provider);
  }

  /** Binds to a specific provider by pluginId (e.g. to override the default). */
  static async forBackend(client: IHalClient, pluginId: string): Promise<SunmiBarcodeScannerClient> {
    const iface = await SunmiBarcodeScannerClient.describeBarcodeScanner(client);
    const provider = iface?.providers.find((p) => p.pluginId === pluginId);
    if (!provider) {
      throw new Error(`Barcode scanner provider not available: ${pluginId}`);
    }
    return SunmiBarcodeScannerClient.fromProvider(client, provider);
  }

  /**
   * All providers of the `barcodeScanner` interface, in the service's effective order (the first enabled
   * one is the default). Useful for offering a backend picker; bind one with
   * {@link SunmiBarcodeScannerClient.forBackend}.
   */
  static async listBackends(client: IHalClient): Promise<InterfaceProvider[]> {
    const iface = await SunmiBarcodeScannerClient.describeBarcodeScanner(client);
    return iface?.providers ?? [];
  }

  /** The default provider of the `barcodeScanner` interface, or null if none is available. */
  static async detect(client: IHalClient): Promise<InterfaceProvider | null> {
    const iface = await SunmiBarcodeScannerClient.describeBarcodeScanner(client);
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

  private static async describeBarcodeScanner(client: IHalClient): Promise<InterfaceDescriptor | undefined> {
    const res = await client.execute<DescribeResponse>('system.describe', {});
    return res.interfaces?.find((i) => i.interfaceId === BARCODE_SCANNER_INTERFACE);
  }

  private static fromProvider(client: IHalClient, provider: InterfaceProvider): SunmiBarcodeScannerClient {
    return new SunmiBarcodeScannerClient(client, provider.pluginId, provider.isDefault);
  }

  /** Adds the `__provider` selector unless this client is bound to the interface's default provider. */
  private params(base: Record<string, unknown>): Record<string, unknown> {
    return this.isDefaultProvider ? base : { ...base, [PROVIDER_PARAM_KEY]: this.backend };
  }

  async trigger(): Promise<void> {
    await this.client.execute('barcodeScanner.trigger', this.params({}));
  }

  async stop(): Promise<void> {
    await this.client.execute('barcodeScanner.stop', this.params({}));
  }

  async onScan(handler: (scan: ScanResult) => void): Promise<() => Promise<void>> {
    // Source-filtered subscription: only this backend's decoded barcodes.
    return this.client.on<ScanResult>(`${ON_SCAN_EVENT}@${this.backend}`, (_name, data) =>
      handler(data),
    );
  }
}
