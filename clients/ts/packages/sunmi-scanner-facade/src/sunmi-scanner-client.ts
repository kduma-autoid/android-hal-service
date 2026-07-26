import type {
  DescribeResponse,
  IHalClient,
  InterfaceDescriptor,
  InterfaceProvider,
  IScanner,
  ScanResult,
} from '@kduma-autoid/hal-client-common';
import { PROVIDER_PARAM_KEY } from '@kduma-autoid/hal-client-common';

const SCANNER_INTERFACE = 'scanner';
const ON_SCAN_EVENT = 'scanner.onScan';

/** System event emitted when a plugin's dynamic availability changes. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** System event emitted when interface provider order/enable changes. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

/** A scanner provider's pluginId (backend), e.g. "sunmi.scanner.inner". */
export type ScannerBackend = string;

/**
 * Unified {@link IScanner} client backed by the server-side `scanner` interface. Calls
 * `scanner.trigger/stop`, targeting the interface's default provider — or a specific one pinned via
 * {@link SunmiScannerClient.forBackend}, injected through the reserved `__provider` param.
 *
 * {@link SunmiScannerClient.onScan} subscribes to `scanner.onScan` filtered to THIS backend by
 * event source (`scanner.onScan@<backend>`), so a client bound to the built-in scanner never sees
 * an external scanner's barcodes.
 */
export class SunmiScannerClient implements IScanner {
  /** The active provider's pluginId (e.g. "sunmi.scanner.inner"). */
  readonly backend: string;

  private readonly client: IHalClient;
  private readonly isDefaultProvider: boolean;

  private constructor(client: IHalClient, backend: string, isDefaultProvider: boolean) {
    this.client = client;
    this.backend = backend;
    this.isDefaultProvider = isDefaultProvider;
  }

  /** Binds to the interface's default provider; throws if `scanner` has no available provider. */
  static async create(client: IHalClient): Promise<SunmiScannerClient> {
    const provider = await SunmiScannerClient.detect(client);
    if (!provider) {
      throw new Error('No scanner backend available (interface "scanner" has no provider)');
    }
    return SunmiScannerClient.fromProvider(client, provider);
  }

  /** Binds to a specific provider by pluginId (e.g. to override the default). */
  static async forBackend(client: IHalClient, pluginId: string): Promise<SunmiScannerClient> {
    const iface = await SunmiScannerClient.describeScanner(client);
    const provider = iface?.providers.find((p) => p.pluginId === pluginId);
    if (!provider) {
      throw new Error(`Scanner provider not available: ${pluginId}`);
    }
    return SunmiScannerClient.fromProvider(client, provider);
  }

  /**
   * All providers of the `scanner` interface, in the service's effective order (the first enabled
   * one is the default). Useful for offering a backend picker; bind one with
   * {@link SunmiScannerClient.forBackend}.
   */
  static async listBackends(client: IHalClient): Promise<InterfaceProvider[]> {
    const iface = await SunmiScannerClient.describeScanner(client);
    return iface?.providers ?? [];
  }

  /** The default provider of the `scanner` interface, or null if none is available. */
  static async detect(client: IHalClient): Promise<InterfaceProvider | null> {
    const iface = await SunmiScannerClient.describeScanner(client);
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

  private static async describeScanner(client: IHalClient): Promise<InterfaceDescriptor | undefined> {
    const res = await client.execute<DescribeResponse>('system.describe', {});
    return res.interfaces?.find((i) => i.interfaceId === SCANNER_INTERFACE);
  }

  private static fromProvider(client: IHalClient, provider: InterfaceProvider): SunmiScannerClient {
    return new SunmiScannerClient(client, provider.pluginId, provider.isDefault);
  }

  /** Adds the `__provider` selector unless this client is bound to the interface's default provider. */
  private params(base: Record<string, unknown>): Record<string, unknown> {
    return this.isDefaultProvider ? base : { ...base, [PROVIDER_PARAM_KEY]: this.backend };
  }

  async trigger(): Promise<void> {
    await this.client.execute('scanner.trigger', this.params({}));
  }

  async stop(): Promise<void> {
    await this.client.execute('scanner.stop', this.params({}));
  }

  async onScan(handler: (scan: ScanResult) => void): Promise<() => Promise<void>> {
    // Source-filtered subscription: only this backend's decoded barcodes.
    return this.client.on<ScanResult>(`${ON_SCAN_EVENT}@${this.backend}`, (_name, data) =>
      handler(data),
    );
  }
}
