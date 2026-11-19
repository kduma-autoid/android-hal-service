import type {
  DescribeResponse,
  IHalClient,
  InterfaceDescriptor,
  InterfaceProvider,
  ScaleInfo,
  ScaleReading,
  ScaleStatusChange,
} from '@kduma-autoid/hal-client-common';
import { methodForProvider } from '@kduma-autoid/hal-client-common';

const SCALE_INTERFACE = 'scale';
const ON_WEIGHT_EVENT = 'scale.onWeight';
const ON_STATUS_EVENT = 'scale.onStatus';

/** System event emitted when a plugin's dynamic availability changes. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** System event emitted when interface provider order/enable changes. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

/** A scale provider's pluginId (backend), e.g. "dymo.m10". */
export type ScaleBackend = string;

/**
 * Unified scale client backed by the server-side `scale` interface. Targets the interface's default
 * provider, or a specific one pinned via {@link ScaleClient.forBackend}, selected with the
 * `method@providerId` suffix.
 *
 * Use {@link ScaleReading.stable} (computed by the provider) to decide when a reading is final; the
 * reading's `status` is what the scale itself reports and is informational only.
 */
export class ScaleClient {
  /** The active provider's pluginId (e.g. "dymo.m10"). */
  readonly backend: string;

  private readonly client: IHalClient;

  private constructor(client: IHalClient, backend: string) {
    this.client = client;
    this.backend = backend;
  }

  /** Binds to the interface's default provider; throws if `scale` has no provider. */
  static async create(client: IHalClient): Promise<ScaleClient> {
    const provider = await ScaleClient.detect(client);
    if (!provider) {
      throw new Error('No scale backend available (interface "scale" has no provider)');
    }
    return new ScaleClient(client, provider.pluginId);
  }

  /** Binds to a specific provider by pluginId (e.g. to override the default). */
  static async forBackend(client: IHalClient, pluginId: string): Promise<ScaleClient> {
    const iface = await ScaleClient.describeInterface(client);
    const provider = iface?.providers.find((p) => p.pluginId === pluginId);
    if (!provider) {
      throw new Error(`Scale provider not available: ${pluginId}`);
    }
    // describe lists user-disabled providers on purpose, so a picker can re-enable them — but
    // binding to one would produce a client whose every call comes back `unavailable`.
    if (provider.enabled === false) {
      throw new Error(`Scale provider is disabled for the scale interface: ${pluginId}`);
    }
    return new ScaleClient(client, provider.pluginId);
  }

  /** All providers of the `scale` interface, in the service's effective order. */
  static async listBackends(client: IHalClient): Promise<InterfaceProvider[]> {
    const iface = await ScaleClient.describeInterface(client);
    return iface?.providers ?? [];
  }

  /** The default provider of the `scale` interface, or null if none is available. */
  static async detect(client: IHalClient): Promise<InterfaceProvider | null> {
    const iface = await ScaleClient.describeInterface(client);
    if (!iface || iface.providers.length === 0) return null;
    return iface.providers.find((p) => p.isDefault) ?? iface.providers.find((p) => p.enabled) ?? null;
  }

  /**
   * Subscribes to backend-availability changes — hardware hot-plug (`system.plugins.changed`) and
   * interface order/enable changes (`system.interfaces.changed`). Resolves to an unsubscribe function.
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

  private static async describeInterface(client: IHalClient): Promise<InterfaceDescriptor | undefined> {
    const res = await client.execute<DescribeResponse>('system.describe', {});
    return res.interfaces?.find((i) => i.interfaceId === SCALE_INTERFACE);
  }

  /** The method name pinned to this client's backend. */
  private method(name: string): string {
    return methodForProvider(name, this.backend);
  }

  /** Scale name, connection state and the latest reading. */
  async getInfo(): Promise<ScaleInfo> {
    return this.client.execute<ScaleInfo>(this.method('scale.getInfo'), {});
  }

  /** The latest reading. Rejects with `unavailable` before the scale sent its first one. */
  async getWeight(): Promise<ScaleReading> {
    return this.client.execute<ScaleReading>(this.method('scale.getWeight'), {});
  }

  /**
   * Subscribes to readings from THIS backend (`scale.onWeight@<backend>`): sent when the weight moves
   * (`stable: false`), when it settles (`stable: true`) and when the scale's status or unit changes.
   */
  async onWeight(handler: (reading: ScaleReading) => void): Promise<() => Promise<void>> {
    return this.client.on<ScaleReading>(`${ON_WEIGHT_EVENT}@${this.backend}`, (_name, data) => handler(data));
  }

  /** Subscribes to status changes reported by THIS backend's scale (`scale.onStatus@<backend>`). */
  async onStatus(handler: (change: ScaleStatusChange) => void): Promise<() => Promise<void>> {
    return this.client.on<ScaleStatusChange>(`${ON_STATUS_EVENT}@${this.backend}`, (_name, data) => handler(data));
  }
}
