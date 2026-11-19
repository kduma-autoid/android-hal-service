import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { DymoM10Info, DymoM10Reading, DymoM10StatusChange } from './types.js';
import { DYMO_M10_EVENTS } from './types.js';

export const PLUGIN_ID = 'dymo.m10';

/**
 * Client for the native API of the `dymo.m10` plugin (Dymo M10 USB postal scale).
 * For a backend-independent API use the `scale` interface facade instead.
 */
export class DymoM10Client {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  /** Connection state, USB permission and the latest reading. */
  async getInfo(): Promise<DymoM10Info> {
    return this.client.execute<DymoM10Info>('dymo.m10.getInfo', {});
  }

  /** The latest reading. Rejects with `unavailable` before the scale sent its first one. */
  async getWeight(): Promise<DymoM10Reading> {
    return this.client.execute<DymoM10Reading>('dymo.m10.getWeight', {});
  }

  async onWeight(handler: (reading: DymoM10Reading) => void): Promise<() => Promise<void>> {
    return this.client.on<DymoM10Reading>(DYMO_M10_EVENTS.WEIGHT, (_, data) => handler(data));
  }

  async onStatus(handler: (change: DymoM10StatusChange) => void): Promise<() => Promise<void>> {
    return this.client.on<DymoM10StatusChange>(DYMO_M10_EVENTS.STATUS, (_, data) => handler(data));
  }

  async onAttached(handler: () => void): Promise<() => Promise<void>> {
    return this.client.on(DYMO_M10_EVENTS.ATTACHED, () => handler());
  }

  async onDetached(handler: () => void): Promise<() => Promise<void>> {
    return this.client.on(DYMO_M10_EVENTS.DETACHED, () => handler());
  }
}
