import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { TmsLedColor, TmsLedOpenOptions } from './types.js';

export const PLUGIN_ID = 'sunmi.tms.led';

/**
 * Client for the CPad built-in RGB LED indicator (Sunmi TMS `sunmi.tms.led`).
 * Available on CPad running Android 14 with the Sunmi Customer API service.
 */
export class SunmiTmsLedClient {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  /** Returns true if the device supports the RGB LED indicator. */
  async isSupported(): Promise<boolean> {
    const res = await this.client.execute<{ result: boolean }>('sunmi.tms.led.isSupported', {});
    return res?.result === true;
  }

  /** Turns on the LED with full control over mode, timing and auto-release timeout. */
  async open(options: TmsLedOpenOptions): Promise<void> {
    await this.client.execute('sunmi.tms.led.open', {
      color: options.color,
      lightMode: options.lightMode ?? 0,
      onMs: options.onMs ?? 0,
      offMs: options.offMs ?? 0,
      timeoutMs: options.timeoutMs ?? 0,
    });
  }

  /** Turns off the LED indicator. */
  async close(): Promise<void> {
    await this.client.execute('sunmi.tms.led.close', {});
  }

  /** Convenience: steady-on in the given color. */
  async setColor(color: TmsLedColor | number, timeoutMs = 0): Promise<void> {
    await this.open({ color, lightMode: 0, timeoutMs });
  }

  /** Convenience: blink the given color. */
  async setFlashing(
    color: TmsLedColor | number,
    onMs: number,
    offMs: number,
    timeoutMs = 0,
  ): Promise<void> {
    await this.open({ color, lightMode: 1, onMs, offMs, timeoutMs });
  }
}
