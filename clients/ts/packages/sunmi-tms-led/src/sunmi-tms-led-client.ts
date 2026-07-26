import type {
  IHalClient,
  LightCapabilities,
  LightColor,
  LightOptions,
} from '@kduma-autoid/hal-client-common';

export const PLUGIN_ID = 'sunmi.tms.led';

/**
 * Client for the CPad built-in RGB LED indicator (Sunmi TMS `sunmi.tms.led`).
 * Available on CPad running Android 14 with the Sunmi Customer API service.
 *
 * Plugin-specific: calls `sunmi.tms.led.*` directly and deliberately does NOT implement the
 * unified light surface — for backend-transparent light control use `SunmiLightClient`
 * (`@kduma-autoid/hal-client-plugin-sunmi-light-facade`), which routes `light.*` through the
 * registered `light` interface and resolves a provider. Supports the `timeoutMs` option (native
 * auto-release); has no `multiFlash` at all — the CPad LED cannot cycle through colors.
 *
 * Availability (whether the CPad actually has an RGB LED) is reflected by whether the
 * `sunmi.tms.led` capability is advertised in `system.status`, not by a client call.
 */
export class SunmiTmsLedClient {
  readonly capabilities: LightCapabilities = { multiFlash: false, timeout: true };

  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  async off(): Promise<void> {
    await this.client.execute('sunmi.tms.led.off', {});
  }

  async on(color: LightColor, options?: LightOptions): Promise<void> {
    await this.client.execute('sunmi.tms.led.on', {
      color,
      timeoutMs: options?.timeoutMs ?? 0,
    });
  }

  async flash(color: LightColor, onMs: number, offMs: number, options?: LightOptions): Promise<void> {
    await this.client.execute('sunmi.tms.led.flash', {
      color,
      onMs,
      offMs,
      timeoutMs: options?.timeoutMs ?? 0,
    });
  }
}
