import type {
  DescribeResponse,
  IHalClient,
  InterfaceDescriptor,
  InterfaceProvider,
  MagneticCardReaderCapabilities,
  MagneticCardReaderInfo,
  MagneticCardSwipeOptions,
  MagneticCardTracks,
  MagneticCardWrite,
} from '@kduma-autoid/hal-client-common';
import { methodForProvider } from '@kduma-autoid/hal-client-common';

const MAGNETIC_CARD_READER_INTERFACE = 'magneticCardReader';
const ON_SWIPE_EVENT = 'magneticCardReader.onSwipe';

/** System event emitted when a plugin's dynamic availability changes. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** System event emitted when interface provider order/enable changes. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

/** A magnetic card reader provider's pluginId (backend), e.g. "msr605x" or "sunmi.card". */
export type MagneticCardReaderBackend = string;

/**
 * Unified magnetic card reader client backed by the server-side `magneticCardReader` interface.
 * Targets the interface's default provider, or a specific one pinned via
 * {@link MagneticCardReaderClient.forBackend}, selected with the `method@providerId` suffix.
 *
 * Optional operations are exposed only when the bound provider advertises the matching interface
 * feature: `read`/`cancel` need `read` (active readers such as `msr605x`), `write` needs `write`.
 * Passive readers (e.g. `sunmi.card`) only offer {@link getInfo} and {@link onSwipe}.
 *
 * The client is a thin adapter — track data is passed through as-is (without sentinels) and is
 * validated by the backend.
 */
export class MagneticCardReaderClient {
  /** The active provider's pluginId (e.g. "msr605x"). */
  readonly backend: string;
  readonly capabilities: MagneticCardReaderCapabilities;

  private readonly client: IHalClient;

  private constructor(client: IHalClient, backend: string, capabilities: MagneticCardReaderCapabilities) {
    this.client = client;
    this.backend = backend;
    this.capabilities = capabilities;
  }

  /** Binds to the interface's default provider; throws if `magneticCardReader` has no provider. */
  static async create(client: IHalClient): Promise<MagneticCardReaderClient> {
    const provider = await MagneticCardReaderClient.detect(client);
    if (!provider) {
      throw new Error('No magnetic card reader backend available (interface "magneticCardReader" has no provider)');
    }
    return MagneticCardReaderClient.fromProvider(client, provider);
  }

  /** Binds to a specific provider by pluginId (e.g. to override the default). */
  static async forBackend(client: IHalClient, pluginId: string): Promise<MagneticCardReaderClient> {
    const iface = await MagneticCardReaderClient.describeInterface(client);
    const provider = iface?.providers.find((p) => p.pluginId === pluginId);
    if (!provider) {
      throw new Error(`Magnetic card reader provider not available: ${pluginId}`);
    }
    // describe lists user-disabled providers on purpose, so a picker can re-enable them — but
    // binding to one would produce a client whose every call comes back `unavailable`.
    if (provider.enabled === false) {
      throw new Error(`Magnetic card reader provider is disabled for the magneticCardReader interface: ${pluginId}`);
    }
    return MagneticCardReaderClient.fromProvider(client, provider);
  }

  /**
   * All providers of the `magneticCardReader` interface, in the service's effective order (the first
   * enabled one is the default). Useful for a backend picker; bind one with
   * {@link MagneticCardReaderClient.forBackend}.
   */
  static async listBackends(client: IHalClient): Promise<InterfaceProvider[]> {
    const iface = await MagneticCardReaderClient.describeInterface(client);
    return iface?.providers ?? [];
  }

  /** The default provider of the `magneticCardReader` interface, or null if none is available. */
  static async detect(client: IHalClient): Promise<InterfaceProvider | null> {
    const iface = await MagneticCardReaderClient.describeInterface(client);
    if (!iface || iface.providers.length === 0) return null;
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

  private static async describeInterface(client: IHalClient): Promise<InterfaceDescriptor | undefined> {
    const res = await client.execute<DescribeResponse>('system.describe', {});
    return res.interfaces?.find((i) => i.interfaceId === MAGNETIC_CARD_READER_INTERFACE);
  }

  private static fromProvider(client: IHalClient, provider: InterfaceProvider): MagneticCardReaderClient {
    const f = provider.features;
    return new MagneticCardReaderClient(client, provider.pluginId, {
      read: f.includes('read'),
      write: f.includes('write'),
    });
  }

  /**
   * The method name for a call, always pinned to this client's backend, so calls and `onSwipe`
   * stay on the same provider even if the interface default changes underneath a live client.
   */
  private method(name: string): string {
    return methodForProvider(name, this.backend);
  }

  /** Reader name and connection state. */
  async getInfo(): Promise<MagneticCardReaderInfo> {
    return this.client.execute<MagneticCardReaderInfo>(this.method('magneticCardReader.getInfo'), {});
  }

  /** Waits for a card swipe and returns its tracks. Present only with the `read` feature. */
  get read(): ((options?: MagneticCardSwipeOptions) => Promise<MagneticCardTracks>) | undefined {
    if (!this.capabilities.read) return undefined;
    return (options?: MagneticCardSwipeOptions) =>
      this.client.execute<MagneticCardTracks>(this.method('magneticCardReader.read'), { ...options });
  }

  /**
   * Aborts a pending {@link read} or {@link write}. Resolves to whether anything was pending.
   * Present only with the `read` feature.
   */
  get cancel(): (() => Promise<boolean>) | undefined {
    if (!this.capabilities.read) return undefined;
    return async () => {
      const res = await this.client.execute<{ cancelled?: boolean }>(this.method('magneticCardReader.cancel'), {});
      return res?.cancelled === true;
    };
  }

  /** Waits for a card swipe and encodes the given tracks. Present only with the `write` feature. */
  get write(): ((tracks: MagneticCardWrite, options?: MagneticCardSwipeOptions) => Promise<void>) | undefined {
    if (!this.capabilities.write) return undefined;
    return async (tracks: MagneticCardWrite, options?: MagneticCardSwipeOptions) => {
      await this.client.execute(this.method('magneticCardReader.write'), { ...tracks, ...options });
    };
  }

  /** Subscribes to swipes reported by THIS backend (`magneticCardReader.onSwipe@<backend>`). */
  async onSwipe(handler: (tracks: MagneticCardTracks) => void): Promise<() => Promise<void>> {
    return this.client.on<MagneticCardTracks>(`${ON_SWIPE_EVENT}@${this.backend}`, (_name, data) => handler(data));
  }
}
