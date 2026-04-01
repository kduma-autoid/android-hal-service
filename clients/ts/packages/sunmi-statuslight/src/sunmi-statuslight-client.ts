import type { IExecutor, StatusLightColor, FlashStep } from './types.js';

export const PLUGIN_ID = 'sunmi.statuslight';

export class SunmiStatusLightClient {
  private readonly executor: IExecutor;

  constructor(executor: IExecutor) {
    this.executor = executor;
  }

  async setColor(color: StatusLightColor): Promise<void> {
    await this.executor.execute('sunmi.statuslight.setColor', { color });
  }

  async turnOff(): Promise<void> {
    await this.executor.execute('sunmi.statuslight.turnOff', {});
  }

  async setFlashing(color: StatusLightColor, onMs: number, offMs: number): Promise<void> {
    await this.executor.execute('sunmi.statuslight.setFlashing', { color, onMs, offMs });
  }

  async setMultiFlashing(steps: FlashStep[]): Promise<void> {
    await this.executor.execute('sunmi.statuslight.setMultiFlashing', { steps });
  }
}
