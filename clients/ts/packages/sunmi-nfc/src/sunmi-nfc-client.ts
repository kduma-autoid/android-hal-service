import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { NfcModulesChangedEvent } from './types.js';
import { NFC_EVENTS } from './types.js';

export const PLUGIN_ID = 'sunmi.nfc';

export class SunmiNfcClient {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  async switchModule(sn?: string): Promise<void> {
    await this.client.execute('sunmi.nfc.switchModule', { sn: sn ?? '' });
  }

  async setWatermarkAlpha(alpha: number): Promise<void> {
    await this.client.execute('sunmi.nfc.setWatermarkAlpha', { alpha });
  }

  async onModulesChanged(handler: (data: NfcModulesChangedEvent) => void): Promise<() => Promise<void>> {
    return this.client.on<NfcModulesChangedEvent>(NFC_EVENTS.MODULES_CHANGED, (_, data) => handler(data));
  }
}
