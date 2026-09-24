import type {
  FlashStep,
  IHalClient,
  LightCapabilities,
  LightColor,
  LightOptions,
} from '@kduma-autoid/hal-client-common';

export const PLUGIN_ID = 'sunmi.statuslight';

/**
 * Client for the Sunmi FLEX 3 status LED (`sunmi.statuslight`).
 *
 * Plugin-specific: calls `sunmi.statuslight.*` directly and deliberately does NOT implement the
 * unified light surface — for backend-transparent light control use `SunmiLightClient`
 * (`@kduma-autoid/hal-client-plugin-sunmi-light-facade`), which routes `light.*` through the
 * registered `light` interface and resolves a provider. Supports `multiFlash` (cycling multiple
 * colors); does NOT support the `timeoutMs` option — passing a positive `timeoutMs` throws.
 *
 * Availability (whether the USB dongle is connected) is reflected by whether the
 * `sunmi.statuslight` capability is advertised in `system.status`, not by a client call.
 */
export class SunmiStatusLightClient {
  readonly capabilities: LightCapabilities = { multiFlash: true, timeout: false };

  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  async off(): Promise<void> {
    await this.client.execute('sunmi.statuslight.off', {});
  }

  async on(color: LightColor, options?: LightOptions): Promise<void> {
    this.assertNoTimeout(options);
    await this.client.execute('sunmi.statuslight.on', { color });
  }

  async flash(color: LightColor, onMs: number, offMs: number, options?: LightOptions): Promise<void> {
    this.assertNoTimeout(options);
    await this.client.execute('sunmi.statuslight.flash', { color, onMs, offMs });
  }

  multiFlash(steps: FlashStep[]): Promise<void>;
  multiFlash(colors: LightColor[], onMs: number, offMs: number): Promise<void>;
  async multiFlash(
    stepsOrColors: FlashStep[] | LightColor[],
    onMs?: number,
    offMs?: number,
  ): Promise<void> {
    if (typeof onMs === 'number' && typeof offMs === 'number') {
      await this.client.execute('sunmi.statuslight.multiFlash', {
        colors: stepsOrColors as LightColor[],
        onMs,
        offMs,
      });
    } else {
      await this.client.execute('sunmi.statuslight.multiFlash', {
        steps: stepsOrColors as FlashStep[],
      });
    }
  }

  private assertNoTimeout(options?: LightOptions): void {
    if (options?.timeoutMs && options.timeoutMs > 0) {
      throw new Error('timeoutMs is not supported by sunmi.statuslight');
    }
  }
}
