import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { SunmiCardInfo, SunmiCardSwipeEvent } from './types.js';
import { SUNMI_CARD_EVENTS } from './types.js';

export const PLUGIN_ID = 'sunmi.card';

/**
 * Client for the native API of the `sunmi.card` plugin — the passive magnetic stripe reader of the
 * Sunmi FLEX. The reader listens continuously; swipes arrive as {@link onSwipe} events. For a
 * backend-independent API use the `magneticCardReader` interface facade instead.
 */
export class SunmiCardClient {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  /** Reader name, version, serial number and connection state. */
  async getInfo(): Promise<SunmiCardInfo> {
    return this.client.execute<SunmiCardInfo>('sunmi.card.getInfo', {});
  }

  /** Subscribes to card swipes (raw reader output plus parsed tracks, with sentinels). */
  async onSwipe(handler: (data: SunmiCardSwipeEvent) => void): Promise<() => Promise<void>> {
    return this.client.on<SunmiCardSwipeEvent>(SUNMI_CARD_EVENTS.SWIPE, (_, data) => handler(data));
  }
}
