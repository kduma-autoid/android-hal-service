import type {
  IHalClient,
  ILight,
  LightCapabilities,
  LightColor,
  LightOptions,
  MultiFlash,
  StatusResponse,
} from '@kduma-autoid/hal-client-common';
import {
  SunmiTmsLedClient,
  PLUGIN_ID as TMS_LED_PLUGIN_ID,
} from '@kduma-autoid/hal-client-plugin-sunmi-tms-led';
import {
  SunmiStatusLightClient,
  PLUGIN_ID as STATUSLIGHT_PLUGIN_ID,
} from '@kduma-autoid/hal-client-plugin-sunmi-statuslight';

export type LightBackend = typeof TMS_LED_PLUGIN_ID | typeof STATUSLIGHT_PLUGIN_ID;

/** Preference order: the CPad built-in LED wins over the FLEX status light. */
const BACKEND_PREFERENCE: LightBackend[] = [TMS_LED_PLUGIN_ID, STATUSLIGHT_PLUGIN_ID];

/**
 * Facade over the two Sunmi light backends (`sunmi.tms.led` on CPad and
 * `sunmi.statuslight` on FLEX). Detects which one the connected service exposes,
 * prefers the CPad LED, and delegates the unified {@link ILight} surface to it.
 */
export class SunmiLightClient implements ILight {
  readonly backend: LightBackend;
  private readonly delegate: ILight;

  private constructor(delegate: ILight, backend: LightBackend) {
    this.delegate = delegate;
    this.backend = backend;
  }

  /** Wraps a specific backend without probing the service. */
  static forBackend(client: IHalClient, backend: LightBackend): SunmiLightClient {
    const delegate: ILight =
      backend === TMS_LED_PLUGIN_ID
        ? new SunmiTmsLedClient(client)
        : new SunmiStatusLightClient(client);
    return new SunmiLightClient(delegate, backend);
  }

  /**
   * Detects the available light backend via `system.status` and returns a facade
   * bound to it. Throws if neither backend is present.
   */
  static async create(client: IHalClient): Promise<SunmiLightClient> {
    const backend = await SunmiLightClient.detect(client);
    if (!backend) {
      throw new Error(
        'No Sunmi light backend available (neither sunmi.tms.led nor sunmi.statuslight)',
      );
    }
    return SunmiLightClient.forBackend(client, backend);
  }

  /** Returns the preferred available backend, or null if none is present. */
  static async detect(client: IHalClient): Promise<LightBackend | null> {
    const status = await client.execute<StatusResponse>('system.status', {});
    const caps = new Set(
      Object.values(status?.plugins ?? {}).flatMap((p) => p.capabilities ?? []),
    );
    return BACKEND_PREFERENCE.find((b) => caps.has(b)) ?? null;
  }

  get capabilities(): LightCapabilities {
    return this.delegate.capabilities;
  }

  isSupported(): Promise<boolean> {
    return this.delegate.isSupported();
  }

  off(): Promise<void> {
    return this.delegate.off();
  }

  on(color: LightColor, options?: LightOptions): Promise<void> {
    return this.delegate.on(color, options);
  }

  flash(color: LightColor, onMs: number, offMs: number, options?: LightOptions): Promise<void> {
    return this.delegate.flash(color, onMs, offMs, options);
  }

  /** Present (non-undefined) only when the active backend supports multiFlash. */
  get multiFlash(): MultiFlash | undefined {
    const fn = this.delegate.multiFlash;
    return fn ? (fn.bind(this.delegate) as MultiFlash) : undefined;
  }
}
