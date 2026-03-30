import type { IExecutor, StatusLightColor, FlashStep, StatusLightResponse } from './types.js';

export class SunmiStatusLightClient {
  private readonly executor: IExecutor;

  constructor(executor: IExecutor) {
    this.executor = executor;
  }

  async setColor(color: StatusLightColor): Promise<StatusLightResponse> {
    return this.executor.execute<StatusLightResponse>(
      'sunmi.statuslight.setColor',
      { color },
    );
  }

  async turnOff(): Promise<StatusLightResponse> {
    return this.executor.execute<StatusLightResponse>(
      'sunmi.statuslight.turnOff',
      {},
    );
  }

  async setFlashing(color: StatusLightColor, onMs: number, offMs: number): Promise<StatusLightResponse> {
    return this.executor.execute<StatusLightResponse>(
      'sunmi.statuslight.setFlashing',
      { color, onMs, offMs },
    );
  }

  async setMultiFlashing(steps: FlashStep[]): Promise<StatusLightResponse> {
    return this.executor.execute<StatusLightResponse>(
      'sunmi.statuslight.setMultiFlashing',
      { steps },
    );
  }
}
