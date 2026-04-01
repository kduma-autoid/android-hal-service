import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { StatusLightColor, FlashStep } from './types.js';

export const PLUGIN_ID = 'sunmi.statuslight';

export class SunmiStatusLightClient {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  async setColor(color: StatusLightColor): Promise<void> {
    await this.client.execute('sunmi.statuslight.setColor', { color });
  }

  async turnOff(): Promise<void> {
    await this.client.execute('sunmi.statuslight.turnOff', {});
  }

  async setFlashing(color: StatusLightColor, onMs: number, offMs: number): Promise<void> {
    await this.client.execute('sunmi.statuslight.setFlashing', { color, onMs, offMs });
  }

  async setMultiFlashing(steps: FlashStep[]): Promise<void> {
    await this.client.execute('sunmi.statuslight.setMultiFlashing', { steps });
  }
}
