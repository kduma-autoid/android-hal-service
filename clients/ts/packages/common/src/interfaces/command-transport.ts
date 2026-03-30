import type { ITokenAware } from './token-aware.js';

export interface ICommandTransport extends ITokenAware {
  execute<T = unknown>(method: string, params?: unknown): Promise<T>;
  dispose(): void;
}
