import type {
  DescribeResponse,
  FlashStep,
  IHalClient,
  InterfaceDescriptor,
  InterfaceProvider,
  LightCapabilities,
  LightColor,
  LightOptions,
  MultiFlash,
} from '@kduma-autoid/hal-client-common';
import { methodForProvider } from '@kduma-autoid/hal-client-common';

const LIGHT_INTERFACE = 'light';

/** System event emitted when a plugin's dynamic availability changes. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** System event emitted when interface provider order/enable changes. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

/** A light provider's pluginId (backend), e.g. "sunmi.tms.led" or "sunmi.statuslight". */
export type LightBackend = string;

/**
 * Unified light client backed by the server-side `light` interface. Calls
 * `light.on/off/flash/multiFlash`, targeting the interface's default provider — or a specific one
 * pinned via {@link SunmiLightClient.forBackend}, selected with the `method@providerId` suffix.
 * Feature flags (`timeout`, `multiFlash`) come from the chosen provider's advertised interface
 * features in `system.describe`, so the caller programs against one type and the backend is
 * transparent.
 */
export class SunmiLightClient {
  /** The active provider's pluginId (e.g. "sunmi.tms.led"). */
  readonly backend: string;
  readonly capabilities: LightCapabilities;

  private readonly client: IHalClient;
  private readonly isDefaultProvider: boolean;

  private constructor(
    client: IHalClient,
    backend: string,
    capabilities: LightCapabilities,
    isDefaultProvider: boolean,
  ) {
    this.client = client;
    this.backend = backend;
    this.capabilities = capabilities;
    this.isDefaultProvider = isDefaultProvider;
  }

  /** Binds to the interface's default provider; throws if `light` has no available provider. */
  static async create(client: IHalClient): Promise<SunmiLightClient> {
    const provider = await SunmiLightClient.detect(client);
    if (!provider) {
      throw new Error('No Sunmi light backend available (interface "light" has no provider)');
    }
    return SunmiLightClient.fromProvider(client, provider);
  }

  /** Binds to a specific provider by pluginId (e.g. to override the default). */
  static async forBackend(client: IHalClient, pluginId: string): Promise<SunmiLightClient> {
    const iface = await SunmiLightClient.describeLight(client);
    const provider = iface?.providers.find((p) => p.pluginId === pluginId);
    if (!provider) {
      throw new Error(`Light provider not available: ${pluginId}`);
    }
    return SunmiLightClient.fromProvider(client, provider);
  }

  /**
   * All providers of the `light` interface, in the service's effective order (the first enabled
   * one is the default). Useful for offering a backend picker; bind one with
   * {@link SunmiLightClient.forBackend}.
   */
  static async listBackends(client: IHalClient): Promise<InterfaceProvider[]> {
    const iface = await SunmiLightClient.describeLight(client);
    return iface?.providers ?? [];
  }

  /** The default provider of the `light` interface, or null if none is available. */
  static async detect(client: IHalClient): Promise<InterfaceProvider | null> {
    const iface = await SunmiLightClient.describeLight(client);
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

  private static async describeLight(client: IHalClient): Promise<InterfaceDescriptor | undefined> {
    const res = await client.execute<DescribeResponse>('system.describe', {});
    return res.interfaces?.find((i) => i.interfaceId === LIGHT_INTERFACE);
  }

  private static fromProvider(client: IHalClient, provider: InterfaceProvider): SunmiLightClient {
    const capabilities: LightCapabilities = {
      multiFlash: provider.features.includes('multiFlash'),
      timeout: provider.features.includes('timeout'),
    };
    return new SunmiLightClient(client, provider.pluginId, capabilities, provider.isDefault);
  }

  /**
   * Appends the `@provider` selector to a method name unless this client is bound to the
   * interface's default provider, in which case the bare name routes to that default.
   */
  private method(name: string): string {
    return methodForProvider(name, this.isDefaultProvider ? null : this.backend);
  }

  async off(): Promise<void> {
    await this.client.execute(this.method('light.off'), {});
  }

  async on(color: LightColor, options?: LightOptions): Promise<void> {
    const base: Record<string, unknown> = { color };
    if (this.capabilities.timeout) {
      base.timeoutMs = options?.timeoutMs ?? 0;
    } else if (options?.timeoutMs) {
      throw new Error(`timeoutMs is not supported by ${this.backend}`);
    }
    await this.client.execute(this.method('light.on'), base);
  }

  async flash(color: LightColor, onMs: number, offMs: number, options?: LightOptions): Promise<void> {
    const base: Record<string, unknown> = { color, onMs, offMs };
    if (this.capabilities.timeout) {
      base.timeoutMs = options?.timeoutMs ?? 0;
    } else if (options?.timeoutMs) {
      throw new Error(`timeoutMs is not supported by ${this.backend}`);
    }
    await this.client.execute(this.method('light.flash'), base);
  }

  get multiFlash(): MultiFlash | undefined {
    if (!this.capabilities.multiFlash) return undefined;
    const impl = async (
      a: FlashStep[] | LightColor[],
      onMs?: number,
      offMs?: number,
    ): Promise<void> => {
      const payload =
        onMs === undefined
          ? { steps: a as FlashStep[] }
          : { colors: a as LightColor[], onMs, offMs };
      await this.client.execute(this.method('light.multiFlash'), payload);
    };
    return impl as unknown as MultiFlash;
  }
}
