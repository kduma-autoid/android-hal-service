import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type { ScreenDeviceInfo, ScreensChangedEvent } from './types.js';
import { SCREEN_EVENTS } from './types.js';

export const PLUGIN_ID = 'sunmi.screen';

export class SunmiScreenClient {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  async getDeviceInfo(): Promise<ScreenDeviceInfo> {
    return this.client.execute<ScreenDeviceInfo>('sunmi.screen.getDeviceInfo', {});
  }

  async setScreenSwitch(sn: string, enabled: boolean): Promise<void> {
    await this.client.execute('sunmi.screen.setScreenSwitch', { sn, enabled });
  }

  async setTouchSwitch(sn: string, enabled: boolean): Promise<void> {
    await this.client.execute('sunmi.screen.setTouchSwitch', { sn, enabled });
  }

  async setBrightness(sn: string, brightness: number): Promise<void> {
    await this.client.execute('sunmi.screen.setBrightness', { sn, brightness });
  }

  async onScreensChanged(handler: (data: ScreensChangedEvent) => void): Promise<() => Promise<void>> {
    return this.client.on<ScreensChangedEvent>(SCREEN_EVENTS.SCREENS_CHANGED, (_, data) => handler(data));
  }
}
