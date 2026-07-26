import type { ITokenAware } from './token-aware.js';
import type { ExecuteOptions } from '../types/command.js';

export interface ICommandTransport extends ITokenAware {
  /**
   * Executes a command and returns the result body. Pass {@link ExecuteOptions.onMeta} to also
   * observe response metadata (e.g. the handling provider) without changing the return value.
   */
  execute<T = unknown>(method: string, params?: unknown, options?: ExecuteOptions): Promise<T>;
  dispose(): void;
}
