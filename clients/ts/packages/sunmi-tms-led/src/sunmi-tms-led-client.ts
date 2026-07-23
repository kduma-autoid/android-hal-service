import type {
  IHalClient,
  ILight,
  LightCapabilities,
  LightColor,
  LightOptions,
} from '@kduma-autoid/hal-client-common';

export const PLUGIN_ID = 'sunmi.tms.led';

/**
 * Client for the CPad built-in RGB LED indicator (Sunmi TMS `sunmi.tms.led`).
 * Available on CPad running Android 14 with the Sunmi Customer API service.
 *
 * Implements the unified {@link ILight} surface. Supports the `timeoutMs` option
 * (native auto-release); does not support `multiFlash`.
 */
export class SunmiTmsLedClient implements ILight {
  readonly capabilities: LightCapabilities = { multiFlash: false, timeout: true };

  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  async isSupported(): Promise<boolean> {
    const res = await this.client.execute<{ result: boolean }>('sunmi.tms.led.isSupported', {});
    return res?.result === true;
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
